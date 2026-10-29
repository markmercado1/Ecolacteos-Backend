package com.planta.acopio.integration;

import com.planta.acopio.dto.AjusteInsumoRequest;
import com.planta.acopio.dto.DetalleSalidaRequest;
import com.planta.acopio.dto.DuplicadosInsumoResponse;
import com.planta.acopio.dto.FinalizarLoteRequest;
import com.planta.acopio.dto.FusionInsumoResponse;
import com.planta.acopio.dto.IngresoInsumoRequest;
import com.planta.acopio.dto.InsumoRequest;
import com.planta.acopio.dto.KardexResponse;
import com.planta.acopio.dto.MovimientoKardexResponse;
import com.planta.acopio.dto.PlanificarLoteRequest;
import com.planta.acopio.dto.RecepcionPlantaRequest;
import com.planta.acopio.dto.RecetaInsumoRequest;
import com.planta.acopio.dto.RecetaRequest;
import com.planta.acopio.dto.RecetaResponse;
import com.planta.acopio.dto.VistaPreviaFusionResponse;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.ConciliacionService;
import com.planta.acopio.service.FusionInsumoService;
import com.planta.acopio.service.InsumoService;
import com.planta.acopio.service.InventarioInsumoService;
import com.planta.acopio.service.KardexService;
import com.planta.acopio.service.LoteProduccionService;
import com.planta.acopio.service.RecetaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fusion de insumos duplicados (V41) contra PostgreSQL real. Reproduce el estado de hoy: dos insumos que solo difieren en
 * mayusculas/tildes (el indice unico del nombre se retira para poder crear el duplicado, como en una base que ya lo tenia).
 */
@SpringBootTest
class FusionInsumoIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    @Autowired private FusionInsumoService fusionService;
    @Autowired private InsumoService insumoService;
    @Autowired private InventarioInsumoService inventarioInsumoService;
    @Autowired private KardexService kardexService;
    @Autowired private RecetaService recetaService;
    @Autowired private LoteProduccionService loteProduccionService;
    @Autowired private ConciliacionService conciliacionService;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private JdbcTemplate jdbc;

    private String s;
    private Usuario usuario;

    @BeforeEach
    void seed() {
        s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("FUSION_IT_" + s);
        rol = rolRepository.save(rol);
        Usuario u = new Usuario();
        u.setNombre("Fusion Test " + s);
        u.setEmail("fusion-" + s + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        usuario = usuarioRepository.save(u);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(usuario.getEmail(), null));
        // Una base con duplicados historicos aun no tiene el indice unico.
        jdbc.execute("DROP INDEX IF EXISTS uq_insumo_nombre_normalizado");
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
        // Que ningun duplicado de una prueba afecte a las demas.
        jdbc.update("UPDATE insumo SET nombre = left(nombre, 40) || ' #' || left(id::text, 8) WHERE id IN ("
                + "SELECT a.id FROM insumo a JOIN insumo b ON a.nombre_normalizado = b.nombre_normalizado AND a.id > b.id "
                + "WHERE a.fusionado_en_id IS NULL AND b.fusionado_en_id IS NULL)");
    }

    /** Crea "azucar s" (destino) y "Azúcar S" (origen, renombrado a mano: el servicio ya no deja crearlo). */
    private UUID[] duplicados(String stockDestino, String stockOrigen) {
        return duplicados(stockDestino, stockOrigen, "");
    }

    private UUID[] duplicados(String stockDestino, String stockOrigen, String variante) {
        jdbc.execute("DROP INDEX IF EXISTS uq_insumo_nombre_normalizado");   // una fusion previa pudo volver a crearlo
        UUID destino = insumoService.crear(new InsumoRequest("azucar " + s + variante, "kg", new BigDecimal(stockDestino))).id();
        UUID origen = insumoService.crear(new InsumoRequest("tmp " + s + variante, "kg", new BigDecimal(stockOrigen))).id();
        jdbc.update("UPDATE insumo SET nombre = ? WHERE id = ?", "Azúcar " + s.toUpperCase() + variante.toUpperCase(), origen);
        return new UUID[]{destino, origen};
    }

    private BigDecimal stock(UUID id) {
        return jdbc.queryForObject("SELECT stock FROM insumo WHERE id = ?", BigDecimal.class, id);
    }

    private BigDecimal libro(UUID id) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_insumo WHERE insumo_id = ?", BigDecimal.class, id);
    }

    @Test
    void fusionaSumaElStockUnificaElKardexYDejaElOrigenInactivo() {
        UUID[] ids = duplicados("11", "98");
        UUID destino = ids[0], origen = ids[1];
        inventarioInsumoService.registrarIngreso(destino, new IngresoInsumoRequest(new BigDecimal("100"), "Guía A", null));
        inventarioInsumoService.registrarIngreso(origen, new IngresoInsumoRequest(new BigDecimal("50"), "Guía B", null));
        inventarioInsumoService.ajustar(origen, new AjusteInsumoRequest(new BigDecimal("140"), "Conteo"));
        // destino: 111; origen: 140 -> 251.
        assertThat(fusionService.duplicados().grupos()).anySatisfy(g -> assertThat(g.insumos()).hasSize(2));
        assertThat(fusionService.duplicados().indiceUnicoActivo()).isFalse();

        VistaPreviaFusionResponse vp = fusionService.vistaPrevia(origen, destino);
        assertThat(vp.fusionable()).isTrue();
        assertThat(vp.stockResultante()).isEqualByComparingTo("251");
        assertThat(vp.movimientos()).isEqualTo(3);

        FusionInsumoResponse r = fusionService.fusionar(origen, destino);

        assertThat(r.stockDestinoAntes()).isEqualByComparingTo("111");
        assertThat(r.stockDestinoDespues()).isEqualByComparingTo("251");
        assertThat(r.movimientos()).isEqualTo(3);
        assertThat(r.usuarioNombre()).isEqualTo(usuario.getNombre());
        assertThat(stock(destino)).isEqualByComparingTo("251");
        assertThat(libro(destino)).isEqualByComparingTo("251");   // invariante stock = SUM(movimientos)
        assertThat(libro(origen)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForMap("SELECT activo, stock, fusionado_en_id FROM insumo WHERE id = ?", origen))
                .containsEntry("activo", false).containsEntry("fusionado_en_id", destino);

        // El kardex del destino muestra los dos historiales con saldo acumulado coherente y el usuario original.
        KardexResponse k = kardexService.insumo(destino, null, null, PageRequest.of(0, 50));
        List<MovimientoKardexResponse> filas = k.movimientos().contenido();
        assertThat(filas).hasSize(5);
        assertThat(filas.get(filas.size() - 1).saldo()).isEqualByComparingTo("251");
        assertThat(k.saldoFinal()).isEqualByComparingTo("251");

        // Auditoria y desaparicion del grupo de duplicados; el indice unico queda activo.
        assertThat(fusionService.historial()).anySatisfy(h -> assertThat(h.id()).isEqualTo(r.id()));
        assertThat(fusionService.duplicados().grupos()).noneSatisfy(g -> assertThat(g.insumos()).extracting(DuplicadosInsumoResponse.Insumo::id).contains(origen));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_insumo_nombre_normalizado'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT detalle_json::text FROM fusion_insumo WHERE id = ?", String.class, r.id())).contains("movimientos");
    }

    @Test
    void unInsumoFusionadoNoAceptaMovimientosEdicionNiReactivacionYSuNombreQuedaLibre() {
        UUID[] ids = duplicados("5", "7");
        fusionService.fusionar(ids[1], ids[0]);

        assertThatThrownBy(() -> inventarioInsumoService.registrarIngreso(ids[1], new IngresoInsumoRequest(BigDecimal.ONE, null, null)))
                .isInstanceOf(ConflictoNegocioException.class);
        assertThatThrownBy(() -> inventarioInsumoService.ajustar(ids[1], new AjusteInsumoRequest(BigDecimal.TEN, "x")))
                .isInstanceOf(ConflictoNegocioException.class);
        assertThatThrownBy(() -> insumoService.reactivar(ids[1])).isInstanceOf(ConflictoNegocioException.class);
        assertThatThrownBy(() -> insumoService.actualizar(ids[1], new InsumoRequest("otro " + s, "kg"))).isInstanceOf(ConflictoNegocioException.class);
        assertThatThrownBy(() -> fusionService.fusionar(ids[1], ids[0])).isInstanceOf(ConflictoNegocioException.class);
        // Con el origen ya fusionado, "Azúcar" no choca con nada nuevo: el nombre del fusionado no cuenta.
        assertThat(insumoService.crear(new InsumoRequest("AZUCAR " + s + " fina", "kg")).nombre()).contains("fina");
        // La base impide tocar el stock de un fusionado (ck_insumo_fusionado).
        assertThatThrownBy(() -> jdbc.update("UPDATE insumo SET stock = 3 WHERE id = ?", ids[1])).hasMessageContaining("ck_insumo_fusionado");
    }

    @Test
    void rechazaUnidadesDistintasMismaFusionYMismoInsumo() {
        UUID[] ids = duplicados("5", "7");
        jdbc.update("UPDATE insumo SET unidad_medida = 'g' WHERE id = ?", ids[1]);

        assertThat(fusionService.vistaPrevia(ids[1], ids[0]).impedimentos()).anySatisfy(m -> assertThat(m).contains("unidades"));
        assertThatThrownBy(() -> fusionService.fusionar(ids[1], ids[0])).isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("unidades");
        assertThatThrownBy(() -> fusionService.fusionar(ids[0], ids[0])).isInstanceOf(ValidacionNegocioException.class);
        assertThat(stock(ids[0])).isEqualByComparingTo("5");     // nada cambio
        assertThat(stock(ids[1])).isEqualByComparingTo("7");
    }

    @Test
    void reasignaRecetasYLotesDeProduccionYRechazaCuandoAmbosEstanEnLaMismaReceta() {
        Unidad un = new Unidad();
        un.setPlaca("FUS-" + s);
        un.setResponsable(usuario);
        Unidad unidad = unidadRepository.save(un);
        Familia f = new Familia();
        f.setNombre("Familia Fusion " + s);
        Producto p = new Producto();
        p.setNombre("Yogurt Fusion " + s);
        p.setFamilia(familiaRepository.save(f));
        Producto producto = productoRepository.save(p);
        Presentacion pr = new Presentacion();
        pr.setNombre("Vaso");
        pr.setProducto(producto);
        pr.setUnidadConteo("vaso");
        Presentacion vaso = presentacionRepository.save(pr);

        UUID[] ids = duplicados("500", "300");
        UUID destino = ids[0], origen = ids[1];
        // Receta y produccion SOLO con el insumo "origen".
        conciliacionService.registrarYConciliar(new RecepcionPlantaRequest(LocalDate.now(), null, unidad.getId(),
                new BigDecimal("1500"), new BigDecimal("1500"), List.of()));
        RecetaResponse receta = recetaService.crear(new RecetaRequest("Receta " + s, producto.getId(), vaso.getId(),
                new BigDecimal("100"), BigDecimal.ONE, List.of(new RecetaInsumoRequest(origen, BigDecimal.ONE))));
        var plan = loteProduccionService.planificar(new PlanificarLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), receta.id(), new BigDecimal("1000")));
        loteProduccionService.iniciar(plan.id());
        loteProduccionService.finalizar(plan.id(), new FinalizarLoteRequest(List.of(new DetalleSalidaRequest(vaso.getId(), 9)), null));
        BigDecimal consumido = new BigDecimal("300").subtract(stock(origen));
        assertThat(consumido).isPositive();

        VistaPreviaFusionResponse vp = fusionService.vistaPrevia(origen, destino);
        assertThat(vp.fusionable()).isTrue();
        assertThat(vp.recetas()).isEqualTo(1);
        assertThat(vp.lotesProduccion()).isEqualTo(1);

        fusionService.fusionar(origen, destino);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM receta_insumo WHERE receta_id = ? AND insumo_id = ?", Integer.class, receta.id(), destino)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lote_produccion_insumo WHERE lote_id = ? AND insumo_id = ?", Integer.class, plan.id(), destino)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_insumo WHERE lote_produccion_id = ? AND insumo_id = ? AND tipo_movimiento = 'SALIDA_PRODUCCION'",
                Integer.class, plan.id(), destino)).isEqualTo(1);
        assertThat(stock(destino)).isEqualByComparingTo(new BigDecimal("800").subtract(consumido));
        assertThat(libro(destino)).isEqualByComparingTo(stock(destino));

        // Otro par de duplicados que comparten una receta: no se fusionan a ciegas.
        UUID[] otros = duplicados("1", "1", " b");
        recetaService.crear(new RecetaRequest("Receta doble " + s, producto.getId(), vaso.getId(), new BigDecimal("100"), BigDecimal.ONE,
                List.of(new RecetaInsumoRequest(otros[0], BigDecimal.ONE), new RecetaInsumoRequest(otros[1], BigDecimal.ONE))));
        assertThat(fusionService.vistaPrevia(otros[1], otros[0]).impedimentos()).anySatisfy(m -> assertThat(m).contains("misma receta"));
        assertThatThrownBy(() -> fusionService.fusionar(otros[1], otros[0])).isInstanceOf(ConflictoNegocioException.class);
        assertThat(stock(otros[1])).isEqualByComparingTo("1");
    }

    @Test
    void siFallaLaInvarianteNoSeGuardaNada() {
        UUID[] ids = duplicados("5", "7");
        // Rompe a proposito el stock del origen (sin movimiento): la fusion lo detecta y revierte todo.
        jdbc.update("UPDATE insumo SET stock = 8 WHERE id = ?", ids[1]);

        assertThatThrownBy(() -> fusionService.fusionar(ids[1], ids[0])).isInstanceOf(IllegalStateException.class);

        assertThat(stock(ids[0])).isEqualByComparingTo("5");
        assertThat(stock(ids[1])).isEqualByComparingTo("8");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_insumo WHERE insumo_id = ?", Integer.class, ids[1])).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT fusionado_en_id FROM insumo WHERE id = ?", UUID.class, ids[1])).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fusion_insumo WHERE insumo_origen_id = ?", Integer.class, ids[1])).isZero();
    }
}
