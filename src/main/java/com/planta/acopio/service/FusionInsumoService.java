package com.planta.acopio.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.dto.DuplicadosInsumoResponse;
import com.planta.acopio.dto.FusionInsumoResponse;
import com.planta.acopio.dto.VistaPreviaFusionResponse;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fusion de insumos duplicados (V41): "azucar" y "Azúcar" son el mismo insumo y su stock y kardex no deben estar
 * partidos. Fusionar `origen` en `destino` reasigna TODO el historial del origen (movimientos, retiros, recetas, lotes
 * de produccion) al destino, suma el stock y deja el origen inactivo, con stock 0 y apuntando al destino: no se borra
 * nada y queda auditado en fusion_insumo.
 *
 * Es la unica operacion que reescribe `movimiento_insumo.insumo_id` (el libro es append-only): esta autorizada
 * expresamente, es solo ADMIN y se hace en una transaccion que bloquea ambos insumos y verifica al final la
 * invariante stock = SUM(movimientos). Cualquier fallo revierte todo.
 */
@Service
public class FusionInsumoService {

    /** insumo.stock es NUMERIC(12,2). */
    private static final BigDecimal MAX_STOCK = new BigDecimal("9999999999.99");

    private final JdbcTemplate jdbc;
    private final InsumoRepository insumoRepository;
    private final UsuarioRepository usuarioRepository;
    private final ObjectMapper json;

    public FusionInsumoService(JdbcTemplate jdbc, InsumoRepository insumoRepository, UsuarioRepository usuarioRepository,
                               ObjectMapper json) {
        this.jdbc = jdbc;
        this.insumoRepository = insumoRepository;
        this.usuarioRepository = usuarioRepository;
        this.json = json;
    }

    private record Fila(UUID id, String nombre, String unidad, boolean activo, BigDecimal stock, UUID fusionadoEn) {}

    // ------------------------------------------------------------------------------------------ consultas

    @Transactional(readOnly = true)
    public DuplicadosInsumoResponse duplicados() {
        Map<String, List<DuplicadosInsumoResponse.Insumo>> porClave = new LinkedHashMap<>();
        jdbc.query("SELECT i.id, i.nombre, i.unidad_medida, i.activo, i.stock, i.nombre_normalizado, "
                + "(SELECT count(*) FROM movimiento_insumo m WHERE m.insumo_id = i.id) AS movimientos "
                + "FROM insumo i WHERE i.fusionado_en_id IS NULL AND i.nombre_normalizado IN "
                + "(SELECT nombre_normalizado FROM insumo WHERE fusionado_en_id IS NULL GROUP BY nombre_normalizado HAVING count(*) > 1) "
                + "ORDER BY i.nombre_normalizado, i.nombre", rs -> {
            porClave.computeIfAbsent(rs.getString("nombre_normalizado"), k -> new ArrayList<>())
                    .add(new DuplicadosInsumoResponse.Insumo(rs.getObject("id", UUID.class), rs.getString("nombre"),
                            rs.getString("unidad_medida"), rs.getBoolean("activo"), rs.getBigDecimal("stock"), rs.getLong("movimientos")));
        });
        List<DuplicadosInsumoResponse.Grupo> grupos = porClave.entrySet().stream()
                .map(e -> new DuplicadosInsumoResponse.Grupo(e.getKey(), e.getValue())).toList();
        boolean indice = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = current_schema() AND indexname = 'uq_insumo_nombre_normalizado')",
                Boolean.class));
        return new DuplicadosInsumoResponse(indice, grupos);
    }

    @Transactional(readOnly = true)
    public VistaPreviaFusionResponse vistaPrevia(UUID origenId, UUID destinoId) {
        if (origenId.equals(destinoId)) {
            throw new ValidacionNegocioException("Elige dos insumos distintos para fusionar.");
        }
        return analizar(leer(origenId), leer(destinoId));
    }

    @Transactional(readOnly = true)
    public List<FusionInsumoResponse> historial() {
        return jdbc.query("SELECT f.*, u.nombre AS usuario_nombre FROM fusion_insumo f JOIN usuario u ON u.id = f.usuario_id "
                + "ORDER BY f.creado_en DESC, f.id LIMIT 50", (rs, n) -> respuesta(rs.getObject("id", UUID.class), rs));
    }

    // -------------------------------------------------------------------------------------------- fusionar

    @Transactional
    public FusionInsumoResponse fusionar(UUID origenId, UUID destinoId) {
        if (origenId.equals(destinoId)) {
            throw new ValidacionNegocioException("Elige dos insumos distintos para fusionar.");
        }
        // Bloqueo de ambos insumos (en orden de id: dos fusiones o producciones concurrentes nunca se cruzan).
        List<UUID> orden = new ArrayList<>(List.of(origenId, destinoId));
        orden.sort(UUID::compareTo);
        for (UUID id : orden) {
            insumoRepository.bloquearStock(id).orElseThrow(() -> new RecursoNoEncontradoException("Insumo no encontrado: " + id));
        }
        Fila origen = leer(origenId);
        Fila destino = leer(destinoId);
        VistaPreviaFusionResponse vp = analizar(origen, destino);
        if (!vp.fusionable()) {
            throw new ConflictoNegocioException("No se puede fusionar: " + String.join(" ", vp.impedimentos()));
        }

        Map<String, List<UUID>> detalle = new LinkedHashMap<>();
        detalle.put("movimientos", ids("SELECT id FROM movimiento_insumo WHERE insumo_id = ?", origenId));
        detalle.put("retiros", ids("SELECT id FROM salida_inventario WHERE insumo_id = ?", origenId));
        detalle.put("recetas", ids("SELECT id FROM receta_insumo WHERE insumo_id = ?", origenId));
        detalle.put("lotesProduccion", ids("SELECT id FROM lote_produccion_insumo WHERE insumo_id = ?", origenId));
        detalle.put("informes", ids("SELECT id FROM informe_produccion_insumo WHERE insumo_id = ?", origenId));
        detalle.put("lotes", ids("SELECT id FROM lote_insumo WHERE insumo_id = ?", origenId));

        // Los lotes del origen pasan al destino. El codigo es unico por insumo: si choca (p. ej. los dos tienen su lote INICIAL),
        // el lote que llega conserva su identidad con un sufijo (INICIAL-2) y su historial no se toca.
        java.util.Set<String> usados = new java.util.HashSet<>(jdbc.queryForList("SELECT codigo FROM lote_insumo WHERE insumo_id = ?", String.class, destinoId));
        for (java.util.Map<String, Object> lote : jdbc.queryForList("SELECT id, codigo FROM lote_insumo WHERE insumo_id = ? ORDER BY creado_en, id", origenId)) {
            String codigo = (String) lote.get("codigo");
            String nuevo = codigo;
            for (int n = 2; usados.contains(nuevo); n++) {
                String sufijo = "-" + n;
                nuevo = codigo.substring(0, Math.min(codigo.length(), 40 - sufijo.length())) + sufijo;
            }
            usados.add(nuevo);
            jdbc.update("UPDATE lote_insumo SET insumo_id = ?, codigo = ? WHERE id = ?", destinoId, nuevo, lote.get("id"));
        }

        jdbc.update("UPDATE movimiento_insumo SET insumo_id = ? WHERE insumo_id = ?", destinoId, origenId);
        jdbc.update("UPDATE salida_inventario SET insumo_id = ? WHERE insumo_id = ?", destinoId, origenId);
        jdbc.update("UPDATE receta_insumo SET insumo_id = ? WHERE insumo_id = ?", destinoId, origenId);
        jdbc.update("UPDATE lote_produccion_insumo SET insumo_id = ? WHERE insumo_id = ?", destinoId, origenId);
        jdbc.update("UPDATE informe_produccion_insumo SET insumo_id = ? WHERE insumo_id = ?", destinoId, origenId);
        jdbc.update("UPDATE insumo SET stock = 0, activo = FALSE, fusionado_en_id = ? WHERE id = ?", destinoId, origenId);
        jdbc.update("UPDATE insumo SET stock = ? WHERE id = ?", vp.stockResultante(), destinoId);

        // Invariante: el stock del destino es la suma de su libro (que ahora incluye el historial del origen).
        BigDecimal libro = jdbc.queryForObject("SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_insumo WHERE insumo_id = ?",
                BigDecimal.class, destinoId);
        BigDecimal enLotes = jdbc.queryForObject("SELECT COALESCE(SUM(cantidad_disponible), 0) FROM lote_insumo WHERE insumo_id = ?", BigDecimal.class, destinoId);
        if (enLotes.compareTo(vp.stockResultante()) != 0) {
            throw new IllegalStateException("La fusión dejaría el stock (" + vp.stockResultante() + ") distinto de la suma de sus lotes ("
                    + enLotes + "). No se guardó nada.");
        }
        if (libro.compareTo(vp.stockResultante()) != 0) {
            throw new IllegalStateException("La fusión dejaría el stock (" + vp.stockResultante() + ") distinto de la suma de movimientos ("
                    + libro + "). No se guardó nada.");
        }

        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fusion_insumo (id, insumo_origen_id, insumo_destino_id, nombre_origen, nombre_destino, unidad_medida, "
                        + "stock_origen, stock_destino_antes, stock_destino_despues, movimientos, retiros, recetas, lotes_produccion, informes, "
                        + "detalle_json, usuario_id, creado_en, lotes) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, origenId, destinoId, origen.nombre(), destino.nombre(), destino.unidad(),
                origen.stock(), destino.stock(), vp.stockResultante(),
                detalle.get("movimientos").size(), detalle.get("retiros").size(), detalle.get("recetas").size(),
                detalle.get("lotesProduccion").size(), detalle.get("informes").size(),
                aJson(detalle), usuarioAutenticado().getId(), LocalDateTime.now(ZoneOffset.UTC), detalle.get("lotes").size());

        // Si ya no quedan duplicados, la base empieza a garantizar la unicidad del nombre.
        jdbc.queryForObject("SELECT asegurar_indice_unico_insumo()", Boolean.class);

        return jdbc.queryForObject("SELECT f.*, u.nombre AS usuario_nombre FROM fusion_insumo f JOIN usuario u ON u.id = f.usuario_id "
                + "WHERE f.id = ?", (rs, n) -> respuesta(id, rs), id);
    }

    // ----------------------------------------------------------------------------------------------- apoyo

    private VistaPreviaFusionResponse analizar(Fila o, Fila d) {
        List<String> impedimentos = new ArrayList<>();
        if (o.fusionadoEn() != null) impedimentos.add("«" + o.nombre() + "» ya fue fusionado en otro insumo.");
        if (d.fusionadoEn() != null) impedimentos.add("«" + d.nombre() + "» ya fue fusionado en otro insumo.");
        if (!d.activo() && d.fusionadoEn() == null) impedimentos.add("El insumo que se conserva («" + d.nombre() + "») está desactivado: reactívalo primero.");
        if (!o.unidad().trim().equalsIgnoreCase(d.unidad().trim())) {
            impedimentos.add("Las unidades de medida son distintas («" + o.unidad() + "» y «" + d.unidad() + "»): no se pueden sumar.");
        }
        List<String> recetas = jdbc.queryForList("SELECT r.nombre FROM receta_insumo a JOIN receta_insumo b ON b.receta_id = a.receta_id "
                + "JOIN receta r ON r.id = a.receta_id WHERE a.insumo_id = ? AND b.insumo_id = ? ORDER BY r.nombre", String.class, o.id(), d.id());
        if (!recetas.isEmpty()) {
            impedimentos.add("Ambos insumos están en la misma receta (" + String.join(", ", recetas)
                    + "): quita uno de los dos de la receta antes de fusionar.");
        }
        long lotesAmbos = count("SELECT count(*) FROM lote_produccion_insumo a JOIN lote_produccion_insumo b ON b.lote_id = a.lote_id "
                + "WHERE a.insumo_id = ? AND b.insumo_id = ?", o.id(), d.id());
        long salidasAmbos = count("SELECT count(*) FROM movimiento_insumo a JOIN movimiento_insumo b ON b.lote_produccion_id = a.lote_produccion_id "
                + "WHERE a.insumo_id = ? AND b.insumo_id = ? AND a.tipo_movimiento = 'SALIDA_PRODUCCION' AND b.tipo_movimiento = 'SALIDA_PRODUCCION'", o.id(), d.id());
        if (lotesAmbos > 0 || salidasAmbos > 0) {
            impedimentos.add("Hay lotes de producción que consumieron los dos insumos; fusionarlos duplicaría el consumo de un mismo lote.");
        }
        long informesAmbos = count("SELECT count(*) FROM informe_produccion_insumo a JOIN informe_produccion_insumo b ON b.informe_id = a.informe_id "
                + "WHERE a.insumo_id = ? AND b.insumo_id = ?", o.id(), d.id());
        if (informesAmbos > 0) {
            impedimentos.add("Hay informes de producción que usan los dos insumos.");
        }
        BigDecimal resultante = o.stock().add(d.stock());
        if (resultante.compareTo(MAX_STOCK) > 0) {
            impedimentos.add("La suma de los stocks supera el máximo registrable.");
        }
        return new VistaPreviaFusionResponse(o.id(), o.nombre(), d.id(), d.nombre(), d.unidad(), o.stock(), d.stock(), resultante,
                count("SELECT count(*) FROM movimiento_insumo WHERE insumo_id = ?", o.id()),
                count("SELECT count(*) FROM salida_inventario WHERE insumo_id = ?", o.id()),
                count("SELECT count(*) FROM receta_insumo WHERE insumo_id = ?", o.id()),
                count("SELECT count(*) FROM lote_produccion_insumo WHERE insumo_id = ?", o.id()),
                count("SELECT count(*) FROM informe_produccion_insumo WHERE insumo_id = ?", o.id()),
                impedimentos, impedimentos.isEmpty());
    }

    private Fila leer(UUID id) {
        List<Fila> filas = jdbc.query("SELECT id, nombre, unidad_medida, activo, stock, fusionado_en_id FROM insumo WHERE id = ?",
                (rs, n) -> new Fila(rs.getObject("id", UUID.class), rs.getString("nombre"), rs.getString("unidad_medida"),
                        rs.getBoolean("activo"), rs.getBigDecimal("stock"), rs.getObject("fusionado_en_id", UUID.class)), id);
        if (filas.isEmpty()) {
            throw new RecursoNoEncontradoException("Insumo no encontrado: " + id);
        }
        return filas.get(0);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private List<UUID> ids(String sql, UUID insumoId) {
        return jdbc.query(sql + " ORDER BY id", (rs, n) -> rs.getObject(1, UUID.class), insumoId);
    }

    private String aJson(Map<String, List<UUID>> detalle) {
        try {
            return json.writeValueAsString(detalle);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo registrar el detalle de la fusión", e);
        }
    }

    private static FusionInsumoResponse respuesta(UUID id, ResultSet rs) throws SQLException {
        return new FusionInsumoResponse(id, rs.getObject("insumo_origen_id", UUID.class), rs.getString("nombre_origen"),
                rs.getObject("insumo_destino_id", UUID.class), rs.getString("nombre_destino"), rs.getString("unidad_medida"),
                rs.getBigDecimal("stock_origen"), rs.getBigDecimal("stock_destino_antes"), rs.getBigDecimal("stock_destino_despues"),
                rs.getInt("movimientos"), rs.getInt("retiros"), rs.getInt("recetas"), rs.getInt("lotes_produccion"), rs.getInt("informes"),
                rs.getString("usuario_nombre"), deUtc(rs.getTimestamp("creado_en")));
    }

    /**
     * La aplicacion guarda las marcas de tiempo en UTC (hibernate.jdbc.time_zone=UTC) y las muestra en hora local; como esta
     * clase escribe con JDBC directo, aplica la misma convencion para que la auditoria se lea igual que el resto del sistema.
     */
    private static LocalDateTime deUtc(java.sql.Timestamp t) {
        return t.toLocalDateTime().atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    private Usuario usuarioAutenticado() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));
    }
}
