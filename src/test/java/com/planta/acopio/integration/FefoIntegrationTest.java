package com.planta.acopio.integration;

import com.planta.acopio.dto.AjusteInventarioRequest;
import com.planta.acopio.dto.CrearVentaCarritoRequest;
import com.planta.acopio.dto.DetalleSalidaRequest;
import com.planta.acopio.dto.DetalleVentaRequest;
import com.planta.acopio.dto.ExistenciasPresentacionResponse;
import com.planta.acopio.dto.FinalizarLoteRequest;
import com.planta.acopio.dto.PrecioQuesoRequest;
import com.planta.acopio.dto.RegularizacionRequest;
import com.planta.acopio.dto.VencimientoSalidaRequest;
import com.planta.acopio.dto.VentaRequest;
import com.planta.acopio.entity.EstadoLote;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.LoteProduccion;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.LoteProduccionDetalleRepository;
import com.planta.acopio.repository.LoteProduccionRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.AjusteInventarioService;
import com.planta.acopio.service.ControlVencimientoService;
import com.planta.acopio.service.InventarioService;
import com.planta.acopio.service.KardexService;
import com.planta.acopio.service.LoteProduccionService;
import com.planta.acopio.service.PrecioQuesoService;
import com.planta.acopio.service.ReglaVencimiento;
import com.planta.acopio.service.VentaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fase 2 (FEFO, bloqueo de vencidos, ajustes con asignacion, regularizacion) contra PostgreSQL REAL.
 * Invariantes por presentacion controlada, que se verifican en cada escenario:
 *   libro = saldo_inicial + SUM(reparto)
 *   libro = sin trazabilidad + SUM(saldo de cada lote)  (ningun cajon queda negativo)
 *   vendible = lotes vigentes; vencido y sin trazabilidad NO se venden.
 * "Hoy" se toma de ReglaVencimiento (America/Lima), igual que el servicio. Sin @Transactional: cada operacion
 * confirma su propia transaccion, como en produccion.
 */
@Testcontainers
@SpringBootTest
class FefoIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("acopio_lechero_test")
            .withUsername("acopio")
            .withPassword("acopio");

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.vencimiento.activacion-habilitada", () -> "true");
    }

    @Autowired private ControlVencimientoService controlService;
    @Autowired private LoteProduccionService loteProduccionService;
    @Autowired private AjusteInventarioService ajusteInventarioService;
    @Autowired private VentaService ventaService;
    @Autowired private PrecioQuesoService precioQuesoService;
    @Autowired private InventarioService inventarioService;
    @Autowired private KardexService kardexService;
    @Autowired private ReglaVencimiento regla;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private LoteProduccionRepository loteRepository;
    @Autowired private LoteProduccionDetalleRepository detalleRepository;
    @Autowired private JdbcTemplate jdbc;

    private Usuario usuario;
    private LocalDate hoy;

    @BeforeEach
    void seed() {
        String s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("FEFO_IT_" + s);
        rol = rolRepository.save(rol);
        Usuario u = new Usuario();
        u.setNombre("Fefo Test " + s);
        u.setEmail("fefo-" + s + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        usuario = usuarioRepository.save(u);
        autenticar();
        hoy = regla.hoy();
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private void autenticar() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(usuario.getEmail(), null));
    }

    // ------------------------------------------------------------------ utilidades

    private Producto nuevoProducto() {
        String s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Familia f = new Familia();
        f.setNombre("Familia Fefo " + s);
        Producto p = new Producto();
        p.setNombre("Yogurt Fefo " + s);
        p.setFamilia(familiaRepository.save(f));
        return productoRepository.save(p);
    }

    /** Presentacion con precio PUBLICO y stock inicial (por conteo, antes de activar el control). */
    private Presentacion nuevaPresentacion(Producto producto, String nombre, int stockInicial) {
        Presentacion pr = new Presentacion();
        pr.setNombre(nombre);
        pr.setProducto(producto);
        pr.setUnidadConteo("botella");
        pr = presentacionRepository.save(pr);
        precioQuesoService.crear(new PrecioQuesoRequest(pr.getId(), "PUBLICO", new BigDecimal("8"), hoy.minusDays(60)));
        if (stockInicial > 0) {
            ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), stockInicial, "Stock inicial de prueba"));
        }
        return pr;
    }

    /** Lote EN_PRODUCCION con la fecha de produccion indicada (permite lotes "viejos" cuyo vencimiento ya paso). */
    private UUID nuevoLote(Producto producto, LocalDate fechaLote) {
        LoteProduccion lote = new LoteProduccion();
        lote.setUuidCliente(UUID.randomUUID().toString());
        lote.setFecha(fechaLote);
        lote.setProducto(producto);
        lote.setLitrosUsados(new BigDecimal("100"));
        lote.setResponsable(usuario);
        lote.setEstado(EstadoLote.EN_PRODUCCION);
        lote.setIniciadoEn(LocalDateTime.now());
        return loteRepository.save(lote).getId();
    }

    /** Finaliza un lote nuevo de UNA presentacion: `cantidad` unidades que vencen en `vence`. Devuelve el id del detalle. */
    private UUID producir(Producto producto, Presentacion pr, int cantidad, LocalDate vence, LocalDate fechaLote) {
        UUID loteId = nuevoLote(producto, fechaLote);
        loteProduccionService.finalizar(loteId, new FinalizarLoteRequest(
                List.of(new DetalleSalidaRequest(pr.getId(), cantidad)), null,
                List.of(new VencimientoSalidaRequest(pr.getId(), vence))));
        return detalleRepository.findByLoteId(loteId).get(0).getId();
    }

    private UUID producir(Producto producto, Presentacion pr, int cantidad, LocalDate vence) {
        return producir(producto, pr, cantidad, vence, hoy.minusDays(1).isAfter(vence) ? vence : hoy.minusDays(1));
    }

    private void vender(Presentacion pr, int cantidad) {
        ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(UUID.randomUUID().toString(),
                hoy, "PUBLICO", List.of(new DetalleVentaRequest(pr.getId(), cantidad))));
    }

    private ExistenciasPresentacionResponse existencias(Presentacion pr) {
        return inventarioService.obtenerExistencias(pr.getId());
    }

    private long libro(UUID presentacionId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_inventario WHERE presentacion_id = ?", Long.class, presentacionId);
    }

    private long movimientos(UUID presentacionId) {
        return jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario WHERE presentacion_id = ?", Long.class, presentacionId);
    }

    private long asignado(UUID presentacionId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(a.cantidad), 0) FROM movimiento_inventario_lote a "
                + "JOIN movimiento_inventario m ON m.id = a.movimiento_id WHERE m.presentacion_id = ?", Long.class, presentacionId);
    }

    private Integer inicial(UUID presentacionId) {
        return jdbc.queryForObject("SELECT saldo_sin_trazabilidad_inicial FROM presentacion WHERE id = ?", Integer.class, presentacionId);
    }

    /** Saldo de cada lote de produccion y de cada regularizacion: ninguno puede ser negativo. */
    private Map<UUID, Long> saldosPorLote(UUID presentacionId) {
        Map<UUID, Long> saldos = new HashMap<>();
        jdbc.query("SELECT COALESCE(a.lote_detalle_id, a.regularizacion_id) AS k, SUM(a.cantidad) AS s FROM movimiento_inventario_lote a "
                + "JOIN movimiento_inventario m ON m.id = a.movimiento_id WHERE m.presentacion_id = ? "
                + "AND (a.lote_detalle_id IS NOT NULL OR a.regularizacion_id IS NOT NULL) GROUP BY 1", rs -> {
            saldos.put((UUID) rs.getObject("k"), rs.getLong("s"));
        }, presentacionId);
        // El saldo de una regularizacion es su cantidad inicial (no pasa por el libro) mas lo que se le asigno despues.
        jdbc.query("SELECT id, cantidad FROM regularizacion_trazabilidad WHERE presentacion_id = ?",
                rs -> { saldos.merge((UUID) rs.getObject("id"), rs.getLong("cantidad"), Long::sum); }, presentacionId);
        return saldos;
    }

    /** Invariantes de la fase para una presentacion controlada. */
    private void assertInvariantes(Presentacion p) {
        assertThat(libro(p.getId())).as("libro = inicial + reparto de " + p.getNombre())
                .isEqualTo(inicial(p.getId()) + asignado(p.getId()));
        // calcularExistencias falla cerrado si el reparto no cuadra con el libro o si algun cajon queda negativo.
        ExistenciasPresentacionResponse e = existencias(p);
        assertThat(e.stockFisico()).isEqualTo((int) libro(p.getId()));
        assertThat(e.stockFisico()).isEqualTo(e.stockVendible() + e.stockVencido() + e.stockSinTrazabilidad());
        assertThat(e.stockSinTrazabilidad()).isGreaterThanOrEqualTo(0);
        assertThat(saldosPorLote(p.getId()).values()).as("ningun lote con saldo negativo").allMatch(v -> v >= 0);
        // La regularizacion nunca toca el libro: sin-traza = inicial + reparto sin lote - regularizado, y >= 0.
    }

    // ------------------------------------------------------------------ FEFO

    @Test
    void laVentaSaleDelLoteQueVenceAntesYNuncaDeLoSinTrazabilidad() {
        Producto producto = nuevoProducto();
        Presentacion pr = nuevaPresentacion(producto, "Botella 1 L", 20); // 20 existencias anteriores
        controlService.activar(producto.getId());
        UUID l1 = producir(producto, pr, 30, hoy.plusDays(5));
        UUID l2 = producir(producto, pr, 30, hoy.plusDays(30));
        assertThat(existencias(pr).stockVendible()).isEqualTo(60);
        assertThat(existencias(pr).stockSinTrazabilidad()).isEqualTo(20);

        vender(pr, 40);

        Map<UUID, Long> saldos = saldosPorLote(pr.getId());
        assertThat(saldos.get(l1)).as("el lote que vence antes se agota primero").isEqualTo(0L);
        assertThat(saldos.get(l2)).isEqualTo(20L);
        var e = existencias(pr);
        assertThat(e.stockFisico()).isEqualTo(40);
        assertThat(e.stockVendible()).isEqualTo(20);
        assertThat(e.stockSinTrazabilidad()).isEqualTo(20);
        assertThat(e.lotes()).hasSize(1).first().satisfies(l -> {
            assertThat(l.id()).isEqualTo(l2);
            assertThat(l.saldo()).isEqualTo(20);
            assertThat(l.diasParaVencer()).isEqualTo(30);
        });
        assertInvariantes(pr);

        // Se pueden vender las 20 vigentes restantes, pero ni una mas: las 20 "sin trazabilidad" no se venden.
        vender(pr, 20);
        assertThatThrownBy(() -> vender(pr, 1)).isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("vendibles 0").hasMessageContaining("20 sin trazabilidad");
        assertThat(existencias(pr).stockFisico()).isEqualTo(20);
        assertInvariantes(pr);
    }

    @Test
    void unaVentaDeVariasUnidadesSePartePorLotesEnUnSoloMovimientoConUnaAsignacionPorLote() {
        Producto producto = nuevoProducto();
        Presentacion pr = nuevaPresentacion(producto, "Botella 1 L", 0);
        controlService.activar(producto.getId());
        UUID l1 = producir(producto, pr, 10, hoy.plusDays(3));
        UUID l2 = producir(producto, pr, 10, hoy.plusDays(9));
        UUID l3 = producir(producto, pr, 10, hoy.plusDays(15));
        long movimientosAntes = movimientos(pr.getId());

        vender(pr, 25);

        assertThat(movimientos(pr.getId())).as("un movimiento de venta por linea, igual que antes").isEqualTo(movimientosAntes + 1);
        List<Map<String, Object>> reparto = jdbc.queryForList("SELECT a.lote_detalle_id AS lote, a.cantidad AS cantidad FROM movimiento_inventario_lote a "
                + "JOIN movimiento_inventario m ON m.id = a.movimiento_id WHERE m.presentacion_id = ? AND m.tipo_movimiento = 'SALIDA_VENTA' "
                + "ORDER BY a.cantidad", pr.getId());
        assertThat(reparto).extracting(r -> r.get("lote"), r -> r.get("cantidad"))
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(l1, -10), org.assertj.core.groups.Tuple.tuple(l2, -10), org.assertj.core.groups.Tuple.tuple(l3, -5));
        assertThat(jdbc.queryForObject("SELECT cantidad FROM movimiento_inventario WHERE presentacion_id = ? AND tipo_movimiento = 'SALIDA_VENTA'",
                Integer.class, pr.getId())).isEqualTo(-25);
        assertInvariantes(pr);
    }

    @Test
    void laVentaMovilDeUnaLineaTambienSaleEnFefo() {
        Producto producto = nuevoProducto();
        Presentacion pr = nuevaPresentacion(producto, "Botella 1 L", 0);
        controlService.activar(producto.getId());
        UUID l1 = producir(producto, pr, 6, hoy.plusDays(4));
        producir(producto, pr, 6, hoy.plusDays(20));

        ventaService.registrarOIgnorarSiDuplicado(new VentaRequest(UUID.randomUUID().toString(), hoy.minusDays(3), "PUBLICO",
                pr.getId(), 4, new BigDecimal("8")));

        assertThat(saldosPorLote(pr.getId()).get(l1)).isEqualTo(2L);
        assertInvariantes(pr);
    }

    // ------------------------------------------------------------------ vencidos

    @Test
    void unLoteVencidoNoSeVendePeroUnoQueVenceHoySiYElDiaDeVencimientoEsInclusivo() {
        Producto producto = nuevoProducto();
        Presentacion pr = nuevaPresentacion(producto, "Botella 1 L", 0);
        controlService.activar(producto.getId());
        UUID vencido = producir(producto, pr, 10, hoy.minusDays(1), hoy.minusDays(30));
        UUID venceHoy = producir(producto, pr, 10, hoy, hoy.minusDays(10));
        UUID vigente = producir(producto, pr, 10, hoy.plusDays(9), hoy.minusDays(1));

        var e = existencias(pr);
        assertThat(e.stockFisico()).isEqualTo(30);
        assertThat(e.stockVencido()).isEqualTo(10);
        assertThat(e.stockVendible()).isEqualTo(20);
        assertThat(e.lotes()).extracting(l -> l.id(), l -> l.vencido(), l -> l.diasParaVencer())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(vencido, true, -1L),
                        org.assertj.core.groups.Tuple.tuple(venceHoy, false, 0L),
                        org.assertj.core.groups.Tuple.tuple(vigente, false, 9L));

        vender(pr, 20); // consume el que vence HOY y el vigente; el vencido se queda
        assertThatThrownBy(() -> vender(pr, 1)).isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("vendibles 0").hasMessageContaining("10 vencidas");
        assertThat(saldosPorLote(pr.getId()).get(vencido)).isEqualTo(10L);
        assertThat(saldosPorLote(pr.getId()).get(venceHoy)).isEqualTo(0L);
        assertInvariantes(pr);
    }

    @Test
    void laFechaDeLaVentaQueMandaElClienteNoCambiaLaEvaluacionDelVencimiento() {
        Producto producto = nuevoProducto();
        Presentacion pr = nuevaPresentacion(producto, "Botella 1 L", 0);
        controlService.activar(producto.getId());
        producir(producto, pr, 10, hoy.minusDays(1), hoy.minusDays(30)); // vencido

        // Una venta "capturada" con fecha de hace 10 dias (cuando el lote aun no vencia) igual se rechaza hoy.
        assertThatThrownBy(() -> ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(
                UUID.randomUUID().toString(), hoy.minusDays(10), "PUBLICO", List.of(new DetalleVentaRequest(pr.getId(), 1)))))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("vendibles 0");
        assertInvariantes(pr);
    }

    @Test
    void unCarritoConUnaLineaControladaSinStockVendibleNoDejaNingunaSalidaNiVentaParcial() {
        Producto controlado = nuevoProducto();
        Presentacion a = nuevaPresentacion(controlado, "Botella 1 L", 0);
        controlService.activar(controlado.getId());
        producir(controlado, a, 5, hoy.plusDays(10));
        Presentacion b = nuevaPresentacion(nuevoProducto(), "Pieza", 10); // sin control
        long ventasAntes = jdbc.queryForObject("SELECT count(*) FROM venta", Long.class);

        assertThatThrownBy(() -> ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(
                UUID.randomUUID().toString(), hoy, "PUBLICO",
                List.of(new DetalleVentaRequest(a.getId(), 6), new DetalleVentaRequest(b.getId(), 2)))))
                .isInstanceOf(ValidacionNegocioException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM venta", Long.class)).isEqualTo(ventasAntes);
        assertThat(libro(b.getId())).as("la linea sin control tampoco se descuenta").isEqualTo(10);
        assertThat(libro(a.getId())).isEqualTo(5);

        // El mismo carrito dentro del limite si pasa, y mezcla controlado con no controlado.
        ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(UUID.randomUUID().toString(), hoy, "PUBLICO",
                List.of(new DetalleVentaRequest(a.getId(), 5), new DetalleVentaRequest(b.getId(), 2))));
        assertThat(libro(a.getId())).isZero();
        assertThat(libro(b.getId())).isEqualTo(8);
        assertInvariantes(a);
    }

    // ------------------------------------------------------------------ ajustes

    @Test
    void unAjusteNegativoDescuentaPrimeroLoVencidoLuegoLoSinTrazabilidadYAlFinalFefo() {
        Producto producto = nuevoProducto();
        Presentacion pr = nuevaPresentacion(producto, "Botella 1 L", 20);
        controlService.activar(producto.getId());
        UUID vencido = producir(producto, pr, 5, hoy.minusDays(2), hoy.minusDays(30));
        UUID l1 = producir(producto, pr, 10, hoy.plusDays(5));
        UUID l2 = producir(producto, pr, 10, hoy.plusDays(25));
        assertThat(existencias(pr).stockFisico()).isEqualTo(45);

        ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), 35, "Conteo 1: -10")); // 5 vencidas + 5 sin trazabilidad
        var e1 = existencias(pr);
        assertThat(saldosPorLote(pr.getId()).get(vencido)).isEqualTo(0L);
        assertThat(e1.stockVencido()).isZero();
        assertThat(e1.stockSinTrazabilidad()).isEqualTo(15);
        assertThat(e1.stockVendible()).isEqualTo(20);
        assertInvariantes(pr);

        ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), 12, "Conteo 2: -23")); // 15 sin trazabilidad + 8 del lote que vence antes
        var e2 = existencias(pr);
        assertThat(e2.stockSinTrazabilidad()).isZero();
        assertThat(saldosPorLote(pr.getId()).get(l1)).isEqualTo(2L);
        assertThat(saldosPorLote(pr.getId()).get(l2)).isEqualTo(10L);
        assertThat(e2.stockVendible()).isEqualTo(12);
        assertInvariantes(pr);
    }

    @Test
    void unAjustePositivoEntraComoSinTrazabilidadYNoAumentaLoVendible() {
        Producto producto = nuevoProducto();
        Presentacion pr = nuevaPresentacion(producto, "Botella 1 L", 4);
        controlService.activar(producto.getId());
        producir(producto, pr, 10, hoy.plusDays(5));

        ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), 19, "Se hallaron 5 botellas")); // 14 -> 19

        var e = existencias(pr);
        assertThat(e.stockFisico()).isEqualTo(19);
        assertThat(e.stockSinTrazabilidad()).isEqualTo(9);
        assertThat(e.stockVendible()).as("lo hallado no tiene fecha: no se puede vender hasta regularizarlo").isEqualTo(10);
        assertInvariantes(pr);
    }

    @Test
    void ningunAjusteNiVentaTocaElKardexMasQueConSuMovimientoDeSiempre() {
        Producto producto = nuevoProducto();
        Presentacion pr = nuevaPresentacion(producto, "Botella 1 L", 10);
        controlService.activar(producto.getId());
        producir(producto, pr, 20, hoy.plusDays(5));
        vender(pr, 6);
        ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), 20, "Conteo"));

        var kardex = kardexService.presentacion(pr.getId(), null, null, PageRequest.of(0, 100));

        // El kardex solo lee movimiento_inventario: cada operacion es UN movimiento; el reparto por lote no aparece.
        assertThat(kardex.movimientos().contenido()).extracting(m -> m.tipoMovimiento())
                .containsExactly("AJUSTE", "ENTRADA_PRODUCCION", "SALIDA_VENTA", "AJUSTE");
        assertThat(kardex.saldoFinal()).isEqualByComparingTo(BigDecimal.valueOf(libro(pr.getId())));
        assertInvariantes(pr);
    }

    // ------------------------------------------------------------------ regularizacion

    @Test
    void regularizarReclasificaSinTocarElLibroYLasUnidadesPasanAVenderseEnFefo() {
        Producto producto = nuevoProducto();
        Presentacion pr = nuevaPresentacion(producto, "Botella 1 L", 40);
        controlService.activar(producto.getId());
        UUID l1 = producir(producto, pr, 10, hoy.plusDays(20));
        long libroAntes = libro(pr.getId());
        long movimientosAntes = movimientos(pr.getId());
        String huella = jdbc.queryForObject("SELECT md5(COALESCE(string_agg(id::text || tipo_movimiento || cantidad || creado_en::text, ';' "
                + "ORDER BY creado_en, id), '')) FROM movimiento_inventario WHERE presentacion_id = ?", String.class, pr.getId());

        var r = controlService.regularizar(pr.getId(), new RegularizacionRequest(15, hoy.plusDays(6), "Etiqueta: vence en 6 días"));

        assertThat(r.sinTrazabilidadRestante()).isEqualTo(25);
        assertThat(r.vencido()).isFalse();
        assertThat(libro(pr.getId())).isEqualTo(libroAntes);
        assertThat(movimientos(pr.getId())).as("la regularizacion no agrega movimientos al libro").isEqualTo(movimientosAntes);
        assertThat(jdbc.queryForObject("SELECT md5(COALESCE(string_agg(id::text || tipo_movimiento || cantidad || creado_en::text, ';' "
                + "ORDER BY creado_en, id), '')) FROM movimiento_inventario WHERE presentacion_id = ?", String.class, pr.getId()))
                .as("el libro queda identico, byte a byte").isEqualTo(huella);
        var e = existencias(pr);
        assertThat(e.stockSinTrazabilidad()).isEqualTo(25);
        assertThat(e.stockVendible()).isEqualTo(25); // 10 del lote + 15 regularizadas
        assertThat(e.lotes()).extracting(l -> l.origen(), l -> l.saldo()).containsExactly(
                org.assertj.core.groups.Tuple.tuple("REGULARIZACION", 15), org.assertj.core.groups.Tuple.tuple("LOTE", 10));
        assertInvariantes(pr);

        // Venta de 20: primero las 15 regularizadas (vencen en 6 dias), luego 5 del lote (vence en 20).
        vender(pr, 20);
        assertThat(saldosPorLote(pr.getId()).get(r.id())).isEqualTo(0L);
        assertThat(saldosPorLote(pr.getId()).get(l1)).isEqualTo(5L);
        assertThat(existencias(pr).stockSinTrazabilidad()).isEqualTo(25);
        assertInvariantes(pr);
    }

    @Test
    void regularizarNoPuedeSuperarLoSinTrazabilidadNiAplicarseAUnProductoSinControl() {
        Producto producto = nuevoProducto();
        Presentacion pr = nuevaPresentacion(producto, "Botella 1 L", 10);
        controlService.activar(producto.getId());
        controlService.regularizar(pr.getId(), new RegularizacionRequest(7, hoy.plusDays(3), "primera"));

        assertThatThrownBy(() -> controlService.regularizar(pr.getId(), new RegularizacionRequest(4, hoy.plusDays(3), "segunda")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("Solo hay 3");
        controlService.regularizar(pr.getId(), new RegularizacionRequest(3, hoy.plusDays(3), "resto"));
        assertThat(existencias(pr).stockSinTrazabilidad()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM regularizacion_trazabilidad WHERE presentacion_id = ?", Long.class, pr.getId())).isEqualTo(2);

        Presentacion sinControl = nuevaPresentacion(nuevoProducto(), "Pieza", 5);
        assertThatThrownBy(() -> controlService.regularizar(sinControl.getId(), new RegularizacionRequest(1, hoy, "x")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("no controla vencimiento");
        assertInvariantes(pr);
    }

    @Test
    void unaRegularizacionConFechaPasadaQuedaVencidaYSeDaDeBajaConUnAjuste() {
        Producto producto = nuevoProducto();
        Presentacion pr = nuevaPresentacion(producto, "Botella 1 L", 12);
        controlService.activar(producto.getId());

        var r = controlService.regularizar(pr.getId(), new RegularizacionRequest(12, hoy.minusDays(4), "Etiqueta ya vencida"));

        assertThat(r.vencido()).isTrue();
        var e = existencias(pr);
        assertThat(e.stockVencido()).isEqualTo(12);
        assertThat(e.stockVendible()).isZero();
        assertThatThrownBy(() -> vender(pr, 1)).isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("12 vencidas");

        ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), 0, "Baja por vencimiento"));
        assertThat(libro(pr.getId())).isZero();
        assertThat(existencias(pr).stockVencido()).isZero();
        assertInvariantes(pr);
    }

    // ------------------------------------------------------------------ esquema V22

    @Test
    void elEsquemaImpideUnRepartoConDosOrigenesYUnaRegularizacionSinMotivoOCantidadInvalida() {
        Producto producto = nuevoProducto();
        Presentacion pr = nuevaPresentacion(producto, "Botella 1 L", 10);
        controlService.activar(producto.getId());
        UUID detalleId = producir(producto, pr, 5, hoy.plusDays(5));
        var reg = controlService.regularizar(pr.getId(), new RegularizacionRequest(2, hoy.plusDays(5), "ok"));
        UUID movimientoId = jdbc.queryForObject("SELECT id FROM movimiento_inventario WHERE presentacion_id = ? AND tipo_movimiento = 'ENTRADA_PRODUCCION'",
                UUID.class, pr.getId());

        assertThatThrownBy(() -> jdbc.update("INSERT INTO movimiento_inventario_lote (movimiento_id, lote_detalle_id, regularizacion_id, cantidad) VALUES (?,?,?,?)",
                movimientoId, detalleId, reg.id(), 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO regularizacion_trazabilidad (presentacion_id, cantidad, fecha_vencimiento, motivo, usuario_id) VALUES (?,?,?,?,?)",
                pr.getId(), 1, hoy, "   ", usuario.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO regularizacion_trazabilidad (presentacion_id, cantidad, fecha_vencimiento, motivo, usuario_id) VALUES (?,?,?,?,?)",
                pr.getId(), 0, hoy, "x", usuario.getId())).isInstanceOf(DataIntegrityViolationException.class);
        // Un movimiento no puede repartirse dos veces contra "sin trazabilidad" (ni lote ni regularizacion).
        jdbc.update("INSERT INTO movimiento_inventario_lote (movimiento_id, cantidad) VALUES (?,?)", movimientoId, 1);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO movimiento_inventario_lote (movimiento_id, cantidad) VALUES (?,?)", movimientoId, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------ concurrencia sobre producto controlado

    private List<Object> correrEnParalelo(List<Callable<Object>> tareas) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tareas.size());
        try {
            CyclicBarrier salida = new CyclicBarrier(tareas.size());
            List<Future<Object>> futuros = new ArrayList<>();
            for (Callable<Object> t : tareas) {
                futuros.add(pool.submit(() -> {
                    autenticar();
                    salida.await(10, TimeUnit.SECONDS);
                    try {
                        return t.call();
                    } catch (Exception e) {
                        return e;
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            List<Object> resultados = new ArrayList<>();
            for (Future<Object> f : futuros) resultados.add(f.get(60, TimeUnit.SECONDS)); // un interbloqueo fallaria aqui
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertSoloRechazosDeNegocio(List<Object> resultados) {
        for (Object r : resultados) {
            if (r instanceof Exception e) {
                assertThat(e).as("solo rechazos de negocio, nunca errores de BD ni interbloqueos")
                        .isInstanceOfAny(ValidacionNegocioException.class, ConflictoNegocioException.class);
            }
        }
    }

    private long contarExitos(List<Object> resultados) {
        return resultados.stream().filter(r -> !(r instanceof Exception)).count();
    }

    @Test
    void ochoVentasDe3ConVendible10SoloTresTienenExitoYLoVencidoYSinTrazabilidadNuncaSeToca() throws Exception {
        Producto producto = nuevoProducto();
        Presentacion pr = nuevaPresentacion(producto, "Botella 1 L", 7); // 7 sin trazabilidad
        controlService.activar(producto.getId());
        UUID vencido = producir(producto, pr, 5, hoy.minusDays(1), hoy.minusDays(30));
        UUID a = producir(producto, pr, 6, hoy.plusDays(5));
        UUID b = producir(producto, pr, 4, hoy.plusDays(20));

        List<Callable<Object>> tareas = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tareas.add(() -> {
                vender(pr, 3);
                return "ok";
            });
        }
        List<Object> r = correrEnParalelo(tareas);

        assertSoloRechazosDeNegocio(r);
        assertThat(contarExitos(r)).isEqualTo(3);
        Map<UUID, Long> saldos = saldosPorLote(pr.getId());
        assertThat(saldos.get(vencido)).as("lo vencido no se toca").isEqualTo(5L);
        assertThat(saldos.get(a) + saldos.get(b)).as("de los 10 vigentes salieron 9").isEqualTo(1L);
        var e = existencias(pr);
        assertThat(e.stockSinTrazabilidad()).isEqualTo(7);
        assertThat(e.stockFisico()).isEqualTo(7 + 5 + 10 - 9);
        assertInvariantes(pr);
    }

    @Test
    void ventasAjustesYRegularizacionesSimultaneosNuncaDejanUnCajonNegativoNiElRepartoDescuadrado() throws Exception {
        for (int i = 0; i < 15; i++) {
            Producto producto = nuevoProducto();
            Presentacion pr = nuevaPresentacion(producto, "Botella 1 L", 12);
            controlService.activar(producto.getId());
            producir(producto, pr, 10, hoy.plusDays(5));
            producir(producto, pr, 8, hoy.plusDays(2), hoy.minusDays(3));
            final int ronda = i;

            List<Object> r = correrEnParalelo(List.of(
                    () -> { vender(pr, 9); return "ok"; },
                    () -> { vender(pr, 12); return "ok"; },
                    () -> ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), 15 + ronda % 3, "Conteo concurrente")),
                    () -> controlService.regularizar(pr.getId(), new RegularizacionRequest(7, hoy.plusDays(8), "Regularizacion concurrente"))));

            assertSoloRechazosDeNegocio(r);
            assertInvariantes(pr); // si el reparto no cuadra o un cajon queda negativo, esto falla
        }
    }

    @Test
    void ventasDeDosPresentacionesControladasEnOrdenOpuestoNoSeInterbloquean() throws Exception {
        for (int i = 0; i < 10; i++) {
            Producto producto = nuevoProducto();
            Presentacion x = nuevaPresentacion(producto, "Botella 1 L", 0);
            Presentacion y = nuevaPresentacion(producto, "Botella 500 ml", 0);
            controlService.activar(producto.getId());
            producir(producto, x, 20, hoy.plusDays(10));
            producir(producto, y, 20, hoy.plusDays(10));

            List<Callable<Object>> tareas = new ArrayList<>();
            for (int k = 0; k < 6; k++) {
                List<DetalleVentaRequest> lineas = (k % 2 == 0)
                        ? List.of(new DetalleVentaRequest(x.getId(), 1), new DetalleVentaRequest(y.getId(), 1))
                        : List.of(new DetalleVentaRequest(y.getId(), 1), new DetalleVentaRequest(x.getId(), 1));
                tareas.add(() -> ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(
                        UUID.randomUUID().toString(), hoy, "PUBLICO", lineas)));
            }
            List<Object> r = correrEnParalelo(tareas);

            assertThat(r).as("ninguna debe fallar ni interbloquearse").noneMatch(o -> o instanceof Exception);
            assertThat(existencias(x).stockVendible()).isEqualTo(14);
            assertThat(existencias(y).stockVendible()).isEqualTo(14);
            assertInvariantes(x);
            assertInvariantes(y);
        }
    }
}
