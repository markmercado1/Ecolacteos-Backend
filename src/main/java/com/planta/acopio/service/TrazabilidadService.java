package com.planta.acopio.service;

import com.planta.acopio.dto.LoteInsumoResponse;
import com.planta.acopio.dto.TrazabilidadResponse;
import com.planta.acopio.entity.LoteInsumo;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.LoteInsumoRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Buscador de trazabilidad por codigo de lote (solo lectura). Por el codigo del lote de insumo (o el codigo que puso el proveedor):
 * origen, proveedor, documento con su recibo, todos sus movimientos y en que lotes de produccion se uso (hacia adelante). Por el id de
 * un lote de produccion: que lotes de insumo consumio cada insumo (hacia atras).
 */
@Service
public class TrazabilidadService {

    private static final int MAX_RESULTADOS = 20;

    private final LoteInsumoRepository loteRepository;
    private final LoteInsumoConsultaService consultaService;
    private final AdjuntoService adjuntoService;
    private final JdbcTemplate jdbc;

    public TrazabilidadService(LoteInsumoRepository loteRepository, LoteInsumoConsultaService consultaService, AdjuntoService adjuntoService,
                               JdbcTemplate jdbc) {
        this.loteRepository = loteRepository;
        this.consultaService = consultaService;
        this.adjuntoService = adjuntoService;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public TrazabilidadResponse buscar(String consulta) {
        String q = consulta == null ? "" : consulta.trim();
        if (q.length() < 2) {
            throw new ValidacionNegocioException("Escribe al menos 2 caracteres del código de lote.");
        }
        String patron = "%" + q.toLowerCase().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        List<TrazabilidadResponse.LoteInsumoTrazado> lotesInsumo = new ArrayList<>();
        for (LoteInsumo l : loteRepository.buscarPorCodigo(patron, PageRequest.of(0, MAX_RESULTADOS))) {
            lotesInsumo.add(trazar(l));
        }
        List<TrazabilidadResponse.LoteProduccionTrazado> lotesProduccion = new ArrayList<>();
        try {
            lotesProduccion.addAll(trazarProduccion(UUID.fromString(q)));
        } catch (IllegalArgumentException noEsUuid) {
            // La consulta no es un id de lote de produccion: solo se busca por codigo de lote de insumo.
        }
        return new TrazabilidadResponse(q, lotesInsumo, lotesProduccion);
    }

    private TrazabilidadResponse.LoteInsumoTrazado trazar(LoteInsumo l) {
        LoteInsumoResponse lote = consultaService.obtener(l.getId());
        TrazabilidadResponse.Documento documento = null;
        List<com.planta.acopio.dto.AdjuntoResponse> adjuntos = List.of();
        if (l.getDocumentoIngreso() != null) {
            var d = l.getDocumentoIngreso();
            documento = new TrazabilidadResponse.Documento(d.getId(), d.getTipo().name(), d.getNumero(), d.getFechaDocumento(),
                    d.getProveedor() == null ? null : d.getProveedor().getNombre(), d.getProveedor() == null ? null : d.getProveedor().getRuc(),
                    d.getUsuario().getNombre(), d.getCreadoEn());
            adjuntos = adjuntoService.listarDeDocumentoIngreso(d.getId());
        }
        List<TrazabilidadResponse.Movimiento> movimientos = jdbc.query(
                "SELECT m.creado_en, m.tipo_movimiento, m.cantidad, u.nombre AS usuario, m.motivo, m.referencia FROM movimiento_insumo m "
                        + "LEFT JOIN usuario u ON u.id = m.usuario_id WHERE m.lote_insumo_id = ? ORDER BY m.orden",
                (rs, n) -> new TrazabilidadResponse.Movimiento(local(rs.getTimestamp("creado_en")), rs.getString("tipo_movimiento"),
                        rs.getBigDecimal("cantidad"), rs.getString("usuario"), rs.getString("motivo"), rs.getString("referencia")), l.getId());
        List<TrazabilidadResponse.Produccion> producciones = jdbc.query(
                "SELECT lp.id, lp.fecha, p.nombre, SUM(-m.cantidad) AS cantidad FROM movimiento_insumo m "
                        + "JOIN lote_produccion lp ON lp.id = m.lote_produccion_id JOIN producto p ON p.id = lp.producto_id "
                        + "WHERE m.lote_insumo_id = ? AND m.tipo_movimiento = 'SALIDA_PRODUCCION' GROUP BY lp.id, lp.fecha, p.nombre ORDER BY lp.fecha, lp.id",
                (rs, n) -> new TrazabilidadResponse.Produccion(rs.getObject("id", UUID.class), rs.getDate("fecha").toLocalDate(), rs.getString("nombre"),
                        rs.getBigDecimal("cantidad")), l.getId());
        return new TrazabilidadResponse.LoteInsumoTrazado(lote, documento, adjuntos, movimientos, producciones);
    }

    private List<TrazabilidadResponse.LoteProduccionTrazado> trazarProduccion(UUID loteProduccionId) {
        List<Object[]> cabecera = jdbc.query("SELECT lp.id, lp.fecha, p.nombre FROM lote_produccion lp JOIN producto p ON p.id = lp.producto_id WHERE lp.id = ?",
                (rs, n) -> new Object[]{rs.getObject("id", UUID.class), rs.getDate("fecha").toLocalDate(), rs.getString("nombre")}, loteProduccionId);
        List<TrazabilidadResponse.LoteProduccionTrazado> r = new ArrayList<>();
        for (Object[] c : cabecera) {
            Map<String, List<TrazabilidadResponse.LoteConsumido>> porInsumo = new LinkedHashMap<>();
            Map<String, String> unidad = new LinkedHashMap<>();
            jdbc.query("SELECT i.nombre AS insumo, i.unidad_medida, li.id, li.codigo, li.codigo_proveedor, pc.nombre AS proveedor, li.fecha_vencimiento, -m.cantidad AS cantidad "
                            + "FROM movimiento_insumo m JOIN lote_insumo li ON li.id = m.lote_insumo_id JOIN insumo i ON i.id = m.insumo_id "
                            + "LEFT JOIN proveedor_compra pc ON pc.id = li.proveedor_id "
                            + "WHERE m.lote_produccion_id = ? AND m.tipo_movimiento = 'SALIDA_PRODUCCION' ORDER BY i.nombre, m.orden",
                    rs -> {
                        String insumo = rs.getString("insumo");
                        unidad.put(insumo, rs.getString("unidad_medida"));
                        porInsumo.computeIfAbsent(insumo, k -> new ArrayList<>()).add(new TrazabilidadResponse.LoteConsumido(rs.getObject("id", UUID.class),
                                rs.getString("codigo"), rs.getString("codigo_proveedor"), rs.getString("proveedor"),
                                rs.getDate("fecha_vencimiento") == null ? null : rs.getDate("fecha_vencimiento").toLocalDate(), rs.getBigDecimal("cantidad")));
                    }, loteProduccionId);
            List<TrazabilidadResponse.InsumoConsumido> insumos = porInsumo.entrySet().stream()
                    .map(e -> new TrazabilidadResponse.InsumoConsumido(e.getKey(), unidad.get(e.getKey()), e.getValue())).toList();
            r.add(new TrazabilidadResponse.LoteProduccionTrazado((UUID) c[0], (java.time.LocalDate) c[1], (String) c[2], insumos));
        }
        return r;
    }

    /** Las marcas de tiempo se guardan en UTC (convencion del sistema) y se muestran en hora local. */
    private static LocalDateTime local(Timestamp t) {
        return t.toLocalDateTime().atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    @SuppressWarnings("unused")
    private static BigDecimal cero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
