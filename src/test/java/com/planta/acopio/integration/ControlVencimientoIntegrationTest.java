package com.planta.acopio.integration;

import com.planta.acopio.dto.AjusteInventarioRequest;
import com.planta.acopio.dto.CorregirVencimientoRequest;
import com.planta.acopio.dto.CrearLoteRequest;
import com.planta.acopio.dto.CrearVentaCarritoRequest;
import com.planta.acopio.dto.DetalleSalidaRequest;
import com.planta.acopio.dto.DetalleVentaRequest;
import com.planta.acopio.dto.FinalizarLoteRequest;
import com.planta.acopio.dto.PrecioQuesoRequest;
import com.planta.acopio.dto.VencimientoSalidaRequest;
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
import com.planta.acopio.service.LoteProduccionService;
import com.planta.acopio.service.PrecioQuesoService;
import com.planta.acopio.service.VentaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
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
 * Fase 1 (control de vencimiento) contra PostgreSQL real. Invariante central, por presentacion controlada:
 *     stock del libro = saldo_sin_trazabilidad_inicial + SUM(movimiento_inventario_lote.cantidad)
 * Cubre: activar/desactivar, entrada trazada de produccion, guardas de falla cerrada (venta, ajuste, registro de
 * un paso), correccion de fechas, y las carreras de activacion/desactivacion contra escritores concurrentes del
 * libro y el orden de bloqueos. Sin @Transactional: cada operacion confirma su propia transaccion, como en
 * produccion (el borrado/commit real es parte de lo que se prueba).
 */
@Testcontainers
@SpringBootTest
class ControlVencimientoIntegrationTest {

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
        // La activacion esta DESHABILITADA por defecto en todo entorno real; solo las pruebas la habilitan.
        registry.add("app.vencimiento.activacion-habilitada", () -> "true");
    }

    @Autowired private Environment environment;
    @Autowired private ControlVencimientoService controlService;
    @Autowired private LoteProduccionService loteProduccionService;
    @Autowired private AjusteInventarioService ajusteInventarioService;
    @Autowired private VentaService ventaService;
    @Autowired private PrecioQuesoService precioQuesoService;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private LoteProduccionRepository loteRepository;
    @Autowired private LoteProduccionDetalleRepository detalleRepository;
    @Autowired private JdbcTemplate jdbc;

    private Usuario usuario;

    @BeforeEach
    void seed() {
        // Aislamiento: estas pruebas ESCRIBEN datos; nunca contra la base de desarrollo de application.yml.
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(postgres.getJdbcUrl())
                .doesNotContain(":5432/acopio_lechero");
        String s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("VENC_IT_" + s);
        rol = rolRepository.save(rol);
        Usuario u = new Usuario();
        u.setNombre("Vencimiento Test " + s);
        u.setEmail("venc-" + s + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        usuario = usuarioRepository.save(u);
        autenticar();
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
        f.setNombre("Familia Venc " + s);
        Producto p = new Producto();
        p.setNombre("Yogurt Venc " + s);
        p.setFamilia(familiaRepository.save(f));
        return productoRepository.save(p);
    }

    private Presentacion nuevaPresentacion(Producto producto, String nombre, int stockInicial) {
        Presentacion pr = new Presentacion();
        pr.setNombre(nombre);
        pr.setProducto(producto);
        pr.setUnidadConteo("botella");
        pr = presentacionRepository.save(pr);
        if (stockInicial > 0) {
            ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), stockInicial, "Stock inicial de prueba"));
        }
        return pr;
    }

    private UUID nuevoLoteEnProduccion(Producto producto) {
        LoteProduccion lote = new LoteProduccion();
        lote.setUuidCliente(UUID.randomUUID().toString());
        lote.setFecha(LocalDate.now());
        lote.setProducto(producto);
        lote.setLitrosUsados(new BigDecimal("100"));
        lote.setResponsable(usuario);
        lote.setEstado(EstadoLote.EN_PRODUCCION);
        lote.setIniciadoEn(LocalDateTime.now());
        return loteRepository.save(lote).getId();
    }

    private FinalizarLoteRequest salidas(LocalDate vence, Object... presentacionYCantidad) {
        List<DetalleSalidaRequest> detalles = new ArrayList<>();
        List<VencimientoSalidaRequest> fechas = new ArrayList<>();
        for (int i = 0; i < presentacionYCantidad.length; i += 2) {
            UUID id = ((Presentacion) presentacionYCantidad[i]).getId();
            detalles.add(new DetalleSalidaRequest(id, (Integer) presentacionYCantidad[i + 1]));
            if (vence != null) fechas.add(new VencimientoSalidaRequest(id, vence));
        }
        return new FinalizarLoteRequest(detalles, null, fechas);
    }

    private long libro(UUID presentacionId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_inventario WHERE presentacion_id = ?", Long.class, presentacionId);
    }

    private long asignado(UUID presentacionId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(a.cantidad), 0) FROM movimiento_inventario_lote a "
                + "JOIN movimiento_inventario m ON m.id = a.movimiento_id WHERE m.presentacion_id = ?", Long.class, presentacionId);
    }

    private Integer inicial(UUID presentacionId) {
        return jdbc.queryForObject("SELECT saldo_sin_trazabilidad_inicial FROM presentacion WHERE id = ?", Integer.class, presentacionId);
    }

    private boolean controla(UUID productoId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT controla_vencimiento FROM producto WHERE id = ?", Boolean.class, productoId));
    }

    private long asignacionesDelProducto(UUID productoId) {
        return jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario_lote a JOIN movimiento_inventario m ON m.id = a.movimiento_id "
                + "JOIN presentacion p ON p.id = m.presentacion_id WHERE p.producto_id = ?", Long.class, productoId);
    }

    private String huellaLibro(UUID presentacionId) {
        return jdbc.queryForObject("SELECT count(*) || ':' || COALESCE(SUM(cantidad), 0) || ':' || md5(COALESCE(string_agg(id::text || tipo_movimiento || cantidad "
                + "|| creado_en::text, ';' ORDER BY creado_en, id), '')) FROM movimiento_inventario WHERE presentacion_id = ?", String.class, presentacionId);
    }

    /** La invariante central de la fase, para una presentacion controlada. */
    private void assertInvariante(Presentacion p) {
        Integer ini = inicial(p.getId());
        assertThat(ini).as("saldo inicial de " + p.getNombre()).isNotNull();
        assertThat(libro(p.getId())).as("stock del libro de " + p.getNombre() + " = inicial + asignado")
                .isEqualTo(ini + asignado(p.getId()));
    }

    // ------------------------------------------------------------------ activacion

    @Test
    void activarCapturaElSaldoInicialDeCadaPresentacionIncluidasLasInactivasSinTocarElLibro() {
        Producto producto = nuevoProducto();
        Presentacion litro = nuevaPresentacion(producto, "Botella 1 L", 100);
        Presentacion medio = nuevaPresentacion(producto, "Botella 500 ml", 40);
        Presentacion vacia = nuevaPresentacion(producto, "Botella 2 L", 0);
        medio.setActivo(false);
        presentacionRepository.save(medio);
        String antesLitro = huellaLibro(litro.getId());
        String antesMedio = huellaLibro(medio.getId());

        var r = controlService.activar(producto.getId());

        assertThat(r.controlaVencimiento()).isTrue();
        assertThat(controla(producto.getId())).isTrue();
        assertThat(inicial(litro.getId())).isEqualTo(100);
        assertThat(inicial(medio.getId())).as("tambien las presentaciones inactivas").isEqualTo(40);
        assertThat(inicial(vacia.getId())).isEqualTo(0);
        assertThat(huellaLibro(litro.getId())).as("el libro no cambia al activar").isEqualTo(antesLitro);
        assertThat(huellaLibro(medio.getId())).isEqualTo(antesMedio);
        assertThat(asignacionesDelProducto(producto.getId())).as("activar no crea asignaciones").isZero();
        for (Presentacion p : List.of(litro, medio, vacia)) assertInvariante(p);

        // Idempotente: una segunda activacion no recaptura ni pisa el saldo.
        controlService.activar(producto.getId());
        assertThat(inicial(litro.getId())).isEqualTo(100);
    }

    // ------------------------------------------------------------------ produccion trazada

    @Test
    void unLoteControladoRegistraLaFechaYLaAsignacionYLaInvarianteSeCumple() {
        Producto producto = nuevoProducto();
        Presentacion litro = nuevaPresentacion(producto, "Botella 1 L", 100);
        Presentacion medio = nuevaPresentacion(producto, "Botella 500 ml", 0);
        controlService.activar(producto.getId());
        UUID loteId = nuevoLoteEnProduccion(producto);
        LocalDate vence = LocalDate.now().plusDays(20);

        loteProduccionService.finalizar(loteId, salidas(vence, litro, 60, medio, 25));

        assertThat(detalleRepository.findByLoteId(loteId)).hasSize(2)
                .allSatisfy(d -> assertThat(d.getFechaVencimiento()).isEqualTo(vence));
        assertThat(libro(litro.getId())).isEqualTo(160);
        assertThat(asignado(litro.getId())).isEqualTo(60);
        assertThat(libro(medio.getId())).isEqualTo(25);
        assertThat(asignado(medio.getId())).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario_lote a JOIN movimiento_inventario m ON m.id = a.movimiento_id "
                + "JOIN presentacion p ON p.id = m.presentacion_id WHERE p.producto_id = ? AND a.lote_detalle_id IS NULL", Long.class, producto.getId()))
                .as("las entradas de produccion se asignan a su lote, nunca a 'sin trazabilidad'").isZero();
        assertInvariante(litro);
        assertInvariante(medio);
    }

    @Test
    void finalizarSinFechasUnProductoControladoNoEscribeNadaYElLoteSigueEnProduccion() {
        Producto producto = nuevoProducto();
        Presentacion litro = nuevaPresentacion(producto, "Botella 1 L", 10);
        controlService.activar(producto.getId());
        UUID loteId = nuevoLoteEnProduccion(producto);
        String antes = huellaLibro(litro.getId());

        assertThatThrownBy(() -> loteProduccionService.finalizar(loteId, salidas(null, litro, 60)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("Falta la fecha de vencimiento");

        assertThat(huellaLibro(litro.getId())).isEqualTo(antes);
        assertThat(detalleRepository.findByLoteId(loteId)).isEmpty();
        assertThat(loteRepository.findById(loteId).orElseThrow().getEstado()).isEqualTo(EstadoLote.EN_PRODUCCION);
        assertInvariante(litro);
    }

    @Test
    void elRegistroDeUnPasoDeUnProductoControladoSeRechazaSinCrearNada() {
        Producto producto = nuevoProducto();
        Presentacion litro = nuevaPresentacion(producto, "Botella 1 L", 10);
        controlService.activar(producto.getId());
        long lotesAntes = loteRepository.count();

        assertThatThrownBy(() -> loteProduccionService.crear(new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(),
                producto.getId(), new BigDecimal("50"), List.of(new DetalleSalidaRequest(litro.getId(), 5)))))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("controla vencimiento");

        assertThat(loteRepository.count()).isEqualTo(lotesAntes);
        assertInvariante(litro);
    }

    // ------------------------------------------------------------------ ventas y ajustes (Fase 2 reemplazo la falla cerrada de la Fase 1)

    @Test
    void conSoloExistenciasSinTrazabilidadLaVentaSeRechazaSinEscribirNadaYElAjusteSiSeRegistra() {
        Producto producto = nuevoProducto();
        Presentacion litro = nuevaPresentacion(producto, "Botella 1 L", 50);
        precioQuesoService.crear(new PrecioQuesoRequest(litro.getId(), "PUBLICO", new BigDecimal("8.5"), LocalDate.now().minusDays(1)));
        controlService.activar(producto.getId());
        String antes = huellaLibro(litro.getId());
        long ventasAntes = jdbc.queryForObject("SELECT count(*) FROM venta", Long.class);

        // Hay 50 en inventario pero TODAS sin trazabilidad: ninguna es vendible.
        assertThatThrownBy(() -> ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(
                UUID.randomUUID().toString(), LocalDate.now(), "PUBLICO", List.of(new DetalleVentaRequest(litro.getId(), 3)))))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("vendibles 0").hasMessageContaining("50 sin trazabilidad");

        assertThat(huellaLibro(litro.getId())).as("ningun movimiento nuevo").isEqualTo(antes);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venta", Long.class)).as("la venta se revirtio completa").isEqualTo(ventasAntes);
        assertInvariante(litro);

        // Un ajuste negativo si se registra y se descuenta de lo sin trazabilidad.
        ajusteInventarioService.crear(new AjusteInventarioRequest(litro.getId(), 45, "Rotura"));
        assertThat(libro(litro.getId())).isEqualTo(45);
        assertThat(asignado(litro.getId())).isEqualTo(-5);
        assertInvariante(litro);
    }

    // ------------------------------------------------------------------ desactivacion

    @Test
    void desactivarSinMovimientosDesdeLaActivacionRevierteTodoSinTocarElLibro() {
        Producto producto = nuevoProducto();
        Presentacion litro = nuevaPresentacion(producto, "Botella 1 L", 30);
        controlService.activar(producto.getId());
        String antes = huellaLibro(litro.getId());

        var r = controlService.desactivar(producto.getId());

        assertThat(r.controlaVencimiento()).isFalse();
        assertThat(controla(producto.getId())).isFalse();
        assertThat(inicial(litro.getId())).isNull();
        assertThat(huellaLibro(litro.getId())).isEqualTo(antes);
        // Y vuelve a comportarse como un producto sin control: se puede vender/ajustar de nuevo.
        ajusteInventarioService.crear(new AjusteInventarioRequest(litro.getId(), 28, "Conteo"));
        assertThat(libro(litro.getId())).isEqualTo(28);
    }

    @Test
    void desactivarSeRechazaCuandoYaHayAsignacionesYElControlSigueActivo() {
        Producto producto = nuevoProducto();
        Presentacion litro = nuevaPresentacion(producto, "Botella 1 L", 30);
        controlService.activar(producto.getId());
        loteProduccionService.finalizar(nuevoLoteEnProduccion(producto), salidas(LocalDate.now().plusDays(10), litro, 12));

        assertThatThrownBy(() -> controlService.desactivar(producto.getId()))
                .isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("asignados a lotes");

        assertThat(controla(producto.getId())).isTrue();
        assertThat(inicial(litro.getId())).isEqualTo(30);
        assertInvariante(litro);
    }

    // ------------------------------------------------------------------ correccion de fecha

    @Test
    void corregirUnaFechaDeUnLoteSinUsoConservaLaOriginalYSeRechazaTrasUsarse() {
        Producto producto = nuevoProducto();
        Presentacion litro = nuevaPresentacion(producto, "Botella 1 L", 0);
        controlService.activar(producto.getId());
        UUID loteId = nuevoLoteEnProduccion(producto);
        LocalDate mal = LocalDate.now().plusDays(400);
        LocalDate bien = LocalDate.now().plusDays(40);
        loteProduccionService.finalizar(loteId, salidas(mal, litro, 20));
        UUID detalleId = detalleRepository.findByLoteId(loteId).get(0).getId();

        var r = controlService.corregirVencimiento(detalleId, new CorregirVencimientoRequest(bien, "Se digitó el año equivocado"));

        assertThat(r.fechaVencimiento()).isEqualTo(bien);
        assertThat(r.fechaVencimientoOriginal()).isEqualTo(mal);
        var guardado = detalleRepository.findById(detalleId).orElseThrow();
        assertThat(guardado.getVencimientoCorregidoPor()).isEqualTo(usuario.getId());
        assertThat(guardado.getVencimientoMotivoCorreccion()).isEqualTo("Se digitó el año equivocado");

        // Simula que el lote ya se uso (una salida asignada): la correccion se rechaza y nada cambia.
        UUID ajusteId = UUID.randomUUID();
        UUID movimientoId = UUID.randomUUID();
        jdbc.update("INSERT INTO ajuste_inventario (id, presentacion_id, usuario_id, cantidad_anterior, cantidad_corregida, motivo) VALUES (?,?,?,?,?,?)",
                ajusteId, litro.getId(), usuario.getId(), 20, 19, "simula uso");
        jdbc.update("INSERT INTO movimiento_inventario (id, presentacion_id, tipo_movimiento, cantidad, ajuste_inventario_id) VALUES (?,?,?,?,?)",
                movimientoId, litro.getId(), "AJUSTE", -1, ajusteId);
        jdbc.update("INSERT INTO movimiento_inventario_lote (movimiento_id, lote_detalle_id, cantidad) VALUES (?,?,?)", movimientoId, detalleId, -1);

        assertThatThrownBy(() -> controlService.corregirVencimiento(detalleId, new CorregirVencimientoRequest(LocalDate.now().plusDays(5), "otra")))
                .isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("ya fue utilizado");
        assertThat(detalleRepository.findById(detalleId).orElseThrow().getFechaVencimiento()).isEqualTo(bien);
    }

    // ------------------------------------------------------------------ concurrencia

    private List<Object> correrEnParalelo(List<Callable<Object>> tareas) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tareas.size());
        try {
            CyclicBarrier salida = new CyclicBarrier(tareas.size());
            List<Future<Object>> futuros = new ArrayList<>();
            for (Callable<Object> t : tareas) {
                futuros.add(pool.submit(() -> {
                    autenticar(); // el SecurityContext es por hilo
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

    private void assertSoloFallasEsperadas(List<Object> resultados) {
        for (Object r : resultados) {
            if (r instanceof Exception e) {
                assertThat(e).as("solo se admiten rechazos de negocio, nunca errores de BD ni interbloqueos")
                        .isInstanceOfAny(ValidacionNegocioException.class, ConflictoNegocioException.class);
            }
        }
    }

    @Test
    void activarContraUnaEntradaDeProduccionConcurrenteNuncaDejaUnMovimientoSinAsignar() throws Exception {
        for (int i = 0; i < 25; i++) {
            Producto producto = nuevoProducto();
            Presentacion litro = nuevaPresentacion(producto, "Botella 1 L", 50);
            UUID loteId = nuevoLoteEnProduccion(producto);
            // En iteraciones alternas la entrada trae fecha (valida solo si la activacion llego primero) o no (valida solo si no).
            FinalizarLoteRequest req = salidas(i % 2 == 0 ? LocalDate.now().plusDays(10) : null, litro, 30);

            List<Object> r = correrEnParalelo(List.of(
                    () -> controlService.activar(producto.getId()),
                    () -> loteProduccionService.finalizar(loteId, req)));

            assertSoloFallasEsperadas(r);
            assertThat(controla(producto.getId())).isTrue();
            assertInvariante(litro); // pase lo que pase en el entrelazado, el stock cuadra exactamente
        }
    }

    @Test
    void activarContraUnAjusteConcurrenteNuncaDejaUnMovimientoSinAsignar() throws Exception {
        for (int i = 0; i < 25; i++) {
            Producto producto = nuevoProducto();
            Presentacion litro = nuevaPresentacion(producto, "Botella 1 L", 50);

            List<Object> r = correrEnParalelo(List.of(
                    () -> controlService.activar(producto.getId()),
                    () -> ajusteInventarioService.crear(new AjusteInventarioRequest(litro.getId(), 44, "Conteo concurrente"))));

            assertSoloFallasEsperadas(r);
            assertThat(controla(producto.getId())).isTrue();
            assertInvariante(litro);
        }
    }

    @Test
    void desactivarContraUnaEntradaTrazadaConcurrenteNuncaDejaAsignacionesEnUnProductoSinControl() throws Exception {
        for (int i = 0; i < 25; i++) {
            Producto producto = nuevoProducto();
            Presentacion litro = nuevaPresentacion(producto, "Botella 1 L", 20);
            controlService.activar(producto.getId());
            UUID loteId = nuevoLoteEnProduccion(producto);
            FinalizarLoteRequest req = salidas(LocalDate.now().plusDays(10), litro, 30);

            List<Object> r = correrEnParalelo(List.of(
                    () -> controlService.desactivar(producto.getId()),
                    () -> loteProduccionService.finalizar(loteId, req)));

            assertSoloFallasEsperadas(r);
            if (controla(producto.getId())) {
                assertInvariante(litro);
            } else {
                assertThat(asignacionesDelProducto(producto.getId())).as("sin control no puede haber asignaciones").isZero();
                assertThat(inicial(litro.getId())).isNull();
            }
        }
    }

    @Test
    void produccionesConPresentacionesEnOrdenOpuestoYUnaVentaConcurrenteNoSeInterbloquean() throws Exception {
        for (int i = 0; i < 15; i++) {
            Producto producto = nuevoProducto();
            Presentacion a = nuevaPresentacion(producto, "Botella 1 L", 50);
            Presentacion b = nuevaPresentacion(producto, "Botella 500 ml", 50);
            precioQuesoService.crear(new PrecioQuesoRequest(a.getId(), "PUBLICO", new BigDecimal("8"), LocalDate.now().minusDays(1)));
            precioQuesoService.crear(new PrecioQuesoRequest(b.getId(), "PUBLICO", new BigDecimal("5"), LocalDate.now().minusDays(1)));
            UUID lote1 = nuevoLoteEnProduccion(producto);
            UUID lote2 = nuevoLoteEnProduccion(producto);
            FinalizarLoteRequest ab = new FinalizarLoteRequest(List.of(new DetalleSalidaRequest(a.getId(), 10), new DetalleSalidaRequest(b.getId(), 10)), null);
            FinalizarLoteRequest ba = new FinalizarLoteRequest(List.of(new DetalleSalidaRequest(b.getId(), 7), new DetalleSalidaRequest(a.getId(), 7)), null);

            List<Object> r = correrEnParalelo(List.of(
                    () -> loteProduccionService.finalizar(lote1, ab),
                    () -> loteProduccionService.finalizar(lote2, ba),
                    () -> ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(UUID.randomUUID().toString(),
                            LocalDate.now(), "PUBLICO", List.of(new DetalleVentaRequest(a.getId(), 5), new DetalleVentaRequest(b.getId(), 5))))));

            for (Object o : r) assertThat(o).as("ninguna operacion debe fallar ni interbloquearse").isNotInstanceOf(Exception.class);
            assertThat(libro(a.getId())).isEqualTo(50 + 10 + 7 - 5);
            assertThat(libro(b.getId())).isEqualTo(50 + 10 + 7 - 5);
        }
    }
}
