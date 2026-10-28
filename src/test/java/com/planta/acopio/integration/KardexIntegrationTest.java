package com.planta.acopio.integration;

import com.planta.acopio.dto.AjusteInsumoRequest;
import com.planta.acopio.dto.AjusteInventarioRequest;
import com.planta.acopio.dto.DetalleSalidaRequest;
import com.planta.acopio.dto.FinalizarLoteRequest;
import com.planta.acopio.dto.IngresoInsumoRequest;
import com.planta.acopio.dto.InsumoRequest;
import com.planta.acopio.dto.InsumoResponse;
import com.planta.acopio.dto.KardexResponse;
import com.planta.acopio.dto.MovimientoKardexResponse;
import com.planta.acopio.dto.PlanificarLoteRequest;
import com.planta.acopio.dto.PrecioQuesoRequest;
import com.planta.acopio.dto.RecepcionPlantaRequest;
import com.planta.acopio.dto.RecetaInsumoRequest;
import com.planta.acopio.dto.RecetaRequest;
import com.planta.acopio.dto.RecetaResponse;
import com.planta.acopio.dto.VentaRequest;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.AjusteInventarioService;
import com.planta.acopio.service.ConciliacionService;
import com.planta.acopio.service.InsumoService;
import com.planta.acopio.service.InventarioInsumoService;
import com.planta.acopio.service.KardexService;
import com.planta.acopio.service.LoteProduccionService;
import com.planta.acopio.service.PrecioQuesoService;
import com.planta.acopio.service.RecetaService;
import com.planta.acopio.service.VentaService;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
 * V19 contra PostgreSQL real: la migracion (saldos iniciales), las consultas nativas del kardex (funciones de
 * ventana, joins a documentos, filtros de fecha, paginacion) y la coherencia Produccion/Ventas/Kardex:
 * Recepcion -> receta -> iniciar (salidas de leche e insumos) -> finalizar (entrada de producto) -> venta
 * (salida) -> ajustes, y la invariante insumo.stock = SUM(movimiento_insumo). Sin @Transactional: cada
 * operacion confirma su propia transaccion, como en produccion.
 */
@Testcontainers
@SpringBootTest
class KardexIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = PostgresDePrueba.contenedor()
            .withDatabaseName("acopio_lechero_test")
            .withUsername("acopio")
            .withPassword("acopio");

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private ConciliacionService conciliacionService;
    @Autowired private RecetaService recetaService;
    @Autowired private LoteProduccionService loteProduccionService;
    @Autowired private VentaService ventaService;
    @Autowired private PrecioQuesoService precioQuesoService;
    @Autowired private AjusteInventarioService ajusteInventarioService;
    @Autowired private InsumoService insumoService;
    @Autowired private InventarioInsumoService inventarioInsumoService;
    @Autowired private KardexService kardexService;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private JdbcTemplate jdbc;

    private Usuario usuario;
    private Unidad unidad;
    private Presentacion pieza;
    private Producto andino;
    private InsumoResponse cuajo;
    private InsumoResponse sal;
    private final PageRequest todo = PageRequest.of(0, 200);

    @BeforeEach
    void seed() {
        String s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("KARDEX_IT_" + s);
        rol = rolRepository.save(rol);
        Usuario u = new Usuario();
        u.setNombre("Kardex Test " + s);
        u.setEmail("kardex-" + s + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        usuario = usuarioRepository.save(u);
        autenticar();
        Unidad un = new Unidad();
        un.setPlaca("KDX-" + s);
        un.setResponsable(usuario);
        unidad = unidadRepository.save(un);
        Familia f = new Familia();
        f.setNombre("Queso Kardex " + s);
        Producto p = new Producto();
        p.setNombre("Queso Andino Kardex " + s);
        p.setFamilia(familiaRepository.save(f));
        andino = productoRepository.save(p);
        Presentacion pr = new Presentacion();
        pr.setNombre("Pieza");
        pr.setProducto(andino);
        pr.setUnidadConteo("pieza");
        pieza = presentacionRepository.save(pr);
        cuajo = insumoService.crear(new InsumoRequest("Cuajo K " + s, "g", new BigDecimal("15")));
        sal = insumoService.crear(new InsumoRequest("Sal K " + s, "kg", new BigDecimal("20")));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private void autenticar() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(usuario.getEmail(), null));
    }

    private BigDecimal stock(UUID insumoId) {
        return jdbc.queryForObject("SELECT stock FROM insumo WHERE id = ?", BigDecimal.class, insumoId);
    }

    private BigDecimal sumaMovimientos(UUID insumoId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_insumo WHERE insumo_id = ?",
                BigDecimal.class, insumoId);
    }

    private UUID producirYVender() {
        conciliacionService.registrarYConciliar(new RecepcionPlantaRequest(LocalDate.now(), null, unidad.getId(),
                new BigDecimal("1500"), new BigDecimal("1500"), List.of()));
        RecetaResponse receta = recetaService.crear(new RecetaRequest("Estándar", andino.getId(), pieza.getId(),
                new BigDecimal("100"), BigDecimal.ONE, List.of(new RecetaInsumoRequest(cuajo.id(), BigDecimal.ONE),
                new RecetaInsumoRequest(sal.id(), BigDecimal.ONE))));
        var plan = loteProduccionService.planificar(new PlanificarLoteRequest(UUID.randomUUID().toString(),
                LocalDate.now(), receta.id(), new BigDecimal("1000")));
        loteProduccionService.iniciar(plan.id());
        loteProduccionService.finalizar(plan.id(),
                new FinalizarLoteRequest(List.of(new DetalleSalidaRequest(pieza.getId(), 9)), null));
        precioQuesoService.crear(new PrecioQuesoRequest(pieza.getId(), "PUBLICO", new BigDecimal("25"), LocalDate.now().minusDays(1)));
        ventaService.registrarOIgnorarSiDuplicado(new VentaRequest(UUID.randomUUID().toString(), LocalDate.now(),
                "PUBLICO", pieza.getId(), 2, new BigDecimal("25")));
        return plan.id();
    }

    @Test
    void v19CreaElSaldoInicialDeCadaInsumoExistenteYCuadraConSuStock() {
        // Los insumos sembrados por V6/V17 (Cuajo, Sal, Cloruro de calcio...) tienen su SALDO_INICIAL.
        Integer descuadrados = jdbc.queryForObject(
                "SELECT count(*) FROM insumo i WHERE i.stock <> "
                        + "(SELECT COALESCE(SUM(m.cantidad), 0) FROM movimiento_insumo m WHERE m.insumo_id = i.id)",
                Integer.class);
        assertThat(descuadrados).isZero();
        Integer saldosIniciales = jdbc.queryForObject(
                "SELECT count(*) FROM movimiento_insumo WHERE tipo_movimiento = 'SALDO_INICIAL' AND motivo LIKE '%V19%'",
                Integer.class);
        assertThat(saldosIniciales).isGreaterThan(0);
    }

    @Test
    void produccionYVentaQuedanEnLosTresKardexConSaldosYDocumentosCorrectos() {
        UUID loteId = producirYVender();

        // Insumo: saldo inicial 15 -> salida 10 por el lote.
        KardexResponse kCuajo = kardexService.insumo(cuajo.id(), null, null, todo);
        List<MovimientoKardexResponse> mc = kCuajo.movimientos().contenido();
        assertThat(mc).extracting(MovimientoKardexResponse::tipoMovimiento).containsExactly("SALDO_INICIAL", "SALIDA_PRODUCCION");
        assertThat(mc.get(1).salida()).isEqualByComparingTo("10");
        assertThat(mc.get(1).saldo()).isEqualByComparingTo("5");
        assertThat(mc.get(1).documentoTipo()).isEqualTo("LOTE");
        assertThat(mc.get(1).documentoId()).isEqualTo(loteId);
        assertThat(mc.get(1).referencia()).contains("Queso Andino Kardex");
        assertThat(mc.get(1).usuarioNombre()).isEqualTo(usuario.getNombre());
        assertThat(kCuajo.saldoFinal()).isEqualByComparingTo(stock(cuajo.id()));

        // Producto terminado: +9 al finalizar, -2 por la venta.
        KardexResponse kPieza = kardexService.presentacion(pieza.getId(), null, null, todo);
        List<MovimientoKardexResponse> mp = kPieza.movimientos().contenido();
        assertThat(mp).extracting(MovimientoKardexResponse::tipoMovimiento).containsExactly("ENTRADA_PRODUCCION", "SALIDA_VENTA");
        assertThat(mp.get(0).entrada()).isEqualByComparingTo("9");
        assertThat(mp.get(0).documentoId()).isEqualTo(loteId);
        assertThat(mp.get(1).salida()).isEqualByComparingTo("2");
        assertThat(mp.get(1).documentoTipo()).isEqualTo("VENTA");
        assertThat(mp.get(1).referencia()).isEqualTo("Venta · PUBLICO");
        assertThat(mp.get(1).saldo()).isEqualByComparingTo("7");
        assertThat(kPieza.saldoFinal()).isEqualByComparingTo("7");

        // Leche: el pool es global; se verifica el movimiento del lote y que el saldo final cuadre con el ledger.
        KardexResponse kLeche = kardexService.leche(null, null, PageRequest.of(0, 200));
        assertThat(kLeche.movimientos().contenido()).anySatisfy(m -> {
            assertThat(m.documentoId()).isEqualTo(loteId);
            assertThat(m.salida()).isEqualByComparingTo("1000");
        });
        BigDecimal ledger = jdbc.queryForObject("SELECT COALESCE(SUM(cantidad),0) FROM movimiento_inventario_leche", BigDecimal.class);
        assertThat(kLeche.saldoFinal()).isEqualByComparingTo(ledger);
    }

    @Test
    void ingresosYAjustesQuedanTrazadosYElStockSiempreEsLaSumaDelKardex() {
        inventarioInsumoService.registrarIngreso(sal.id(), new IngresoInsumoRequest(new BigDecimal("5.5"), "Guía 77", null));
        inventarioInsumoService.ajustar(sal.id(), new AjusteInsumoRequest(new BigDecimal("24"), "Conteo mensual"));

        assertThat(stock(sal.id())).isEqualByComparingTo("24");
        assertThat(sumaMovimientos(sal.id())).isEqualByComparingTo("24");
        List<MovimientoKardexResponse> m = kardexService.insumo(sal.id(), null, null, todo).movimientos().contenido();
        assertThat(m).extracting(MovimientoKardexResponse::tipoMovimiento).containsExactly("SALDO_INICIAL", "INGRESO", "AJUSTE");
        assertThat(m.get(1).referencia()).isEqualTo("Guía 77");
        assertThat(m.get(2).salida()).isEqualByComparingTo("1.5");
        assertThat(m.get(2).motivo()).isEqualTo("Conteo mensual");
        assertThat(m.get(2).usuarioNombre()).isEqualTo(usuario.getNombre());
        assertThat(m.get(2).saldo()).isEqualByComparingTo("24");

        // Un ajuste de producto terminado tambien aparece en su kardex con motivo y conteo.
        producirYVender();
        ajusteInventarioService.crear(new AjusteInventarioRequest(pieza.getId(), 6, "Rotura"));
        MovimientoKardexResponse ajuste = kardexService.presentacion(pieza.getId(), null, null, todo)
                .movimientos().contenido().get(2);
        assertThat(ajuste.tipoMovimiento()).isEqualTo("AJUSTE");
        assertThat(ajuste.referencia()).isEqualTo("Conteo físico: 7 → 6");
        assertThat(ajuste.motivo()).isEqualTo("Rotura");
        assertThat(ajuste.saldo()).isEqualByComparingTo("6");
    }

    @Test
    void elSaldoDeCadaFilaEsElRealAunqueSePagineOSeFiltrePorFecha() {
        for (int i = 0; i < 5; i++) {
            inventarioInsumoService.registrarIngreso(cuajo.id(), new IngresoInsumoRequest(BigDecimal.ONE, null, null));
        }
        // Página 2 de tamaño 2: filas 3 y 4 (saldo inicial 15, +1 x5 -> 17 y 18 en esas posiciones).
        List<MovimientoKardexResponse> p = kardexService.insumo(cuajo.id(), null, null, PageRequest.of(1, 2))
                .movimientos().contenido();
        assertThat(p).extracting(MovimientoKardexResponse::saldo)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("17"), new BigDecimal("18"));

        // Rango futuro: sin filas, saldo inicial = todo lo anterior.
        KardexResponse futuro = kardexService.insumo(cuajo.id(), LocalDate.now().plusDays(1), null, todo);
        assertThat(futuro.movimientos().contenido()).isEmpty();
        assertThat(futuro.saldoInicial()).isEqualByComparingTo("20");
        assertThat(futuro.saldoFinal()).isEqualByComparingTo("20");
        // Rango de hoy: todo dentro, saldo inicial 0.
        KardexResponse hoy = kardexService.insumo(cuajo.id(), LocalDate.now(), LocalDate.now(), todo);
        assertThat(hoy.saldoInicial()).isEqualByComparingTo("0");
        assertThat(hoy.totalEntradas()).isEqualByComparingTo("20");
        assertThat(hoy.movimientos().totalElementos()).isEqualTo(6);
    }

    @Test
    void unInicioRechazadoPorFaltaDeInsumoNoDejaMovimientosEnNingunKardex() {
        conciliacionService.registrarYConciliar(new RecepcionPlantaRequest(LocalDate.now(), null, unidad.getId(),
                new BigDecimal("2000"), new BigDecimal("2000"), List.of()));
        RecetaResponse receta = recetaService.crear(new RecetaRequest("Grande", andino.getId(), pieza.getId(),
                new BigDecimal("100"), BigDecimal.ONE, List.of(new RecetaInsumoRequest(cuajo.id(), new BigDecimal("2")))));
        var plan = loteProduccionService.planificar(new PlanificarLoteRequest(UUID.randomUUID().toString(),
                LocalDate.now(), receta.id(), new BigDecimal("1000"))); // pide 20 g, hay 15

        assertThatThrownBy(() -> loteProduccionService.iniciar(plan.id())).isInstanceOf(ValidacionNegocioException.class);

        Integer movimientos = jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM movimiento_insumo WHERE lote_produccion_id = ?) "
                        + "+ (SELECT count(*) FROM movimiento_inventario_leche WHERE lote_produccion_id = ?)",
                Integer.class, plan.id(), plan.id());
        assertThat(movimientos).isZero();
        assertThat(stock(cuajo.id())).isEqualByComparingTo(sumaMovimientos(cuajo.id()));
    }

    // Ingresos y producciones simultaneas sobre el mismo insumo: ninguna escritura se pierde y el stock sigue
    // siendo exactamente la suma del kardex.
    @Test
    void ingresosConcurrentesNoPierdenMovimientosNiDescuadranElStock() throws Exception {
        CyclicBarrier barrera = new CyclicBarrier(4);
        Callable<Void> ingreso = () -> {
            autenticar();
            barrera.await(5, TimeUnit.SECONDS);
            try {
                inventarioInsumoService.registrarIngreso(cuajo.id(), new IngresoInsumoRequest(new BigDecimal("2.5"), null, null));
            } finally {
                SecurityContextHolder.clearContext();
            }
            return null;
        };
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Void>> futuros = pool.invokeAll(List.of(ingreso, ingreso, ingreso, ingreso), 30, TimeUnit.SECONDS);
        for (Future<Void> f : futuros) f.get();
        pool.shutdown();

        assertThat(stock(cuajo.id())).isEqualByComparingTo("25");
        assertThat(sumaMovimientos(cuajo.id())).isEqualByComparingTo("25");
    }
}
