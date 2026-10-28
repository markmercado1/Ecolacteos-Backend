package com.planta.acopio.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.dto.AceptacionPrecioRequest;
import com.planta.acopio.dto.AjusteInventarioRequest;
import com.planta.acopio.dto.CatalogoPublicoResponse;
import com.planta.acopio.dto.ConsultaReservaRequest;
import com.planta.acopio.dto.CrearReservaRequest;
import com.planta.acopio.dto.CrearVentaCarritoRequest;
import com.planta.acopio.dto.DetalleSalidaRequest;
import com.planta.acopio.dto.DetalleVentaRequest;
import com.planta.acopio.dto.FinalizarLoteRequest;
import com.planta.acopio.dto.MotivoReservaRequest;
import com.planta.acopio.dto.PrecioQuesoRequest;
import com.planta.acopio.dto.RegularizacionRequest;
import com.planta.acopio.dto.ReservaPublicaResponse;
import com.planta.acopio.dto.ReservaResponse;
import com.planta.acopio.dto.VencimientoSalidaRequest;
import com.planta.acopio.dto.VentaResumenResponse;
import com.planta.acopio.entity.EstadoLote;
import com.planta.acopio.entity.EstadoReserva;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.LoteProduccion;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoInactivoException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.LoteProduccionRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.ReservaRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.AjusteInventarioService;
import com.planta.acopio.service.CatalogoPublicoService;
import com.planta.acopio.service.ControlVencimientoService;
import com.planta.acopio.service.InventarioService;
import com.planta.acopio.service.KardexService;
import com.planta.acopio.service.LoteProduccionService;
import com.planta.acopio.service.PrecioQuesoService;
import com.planta.acopio.service.ReglaVencimiento;
import com.planta.acopio.service.ReservaService;
import com.planta.acopio.service.VentaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 4 (reservas) contra PostgreSQL REAL. Una reserva COMPROMETE unidades (sin tocar el stock fisico, la venta ni el
 * kardex) y solo la respaldan lotes vendibles que lleguen a su fecha de retiro. Se prueba: creacion y limites, telefono,
 * fechas (plazo, dias de atencion, feriados, horario), precio publico y "Precio a confirmar", lotes que vencen antes,
 * vencidas / sin trazabilidad, concurrencia (reserva vs reserva, reserva vs venta, confirmacion vs ajuste, telefono),
 * idempotencia, transiciones, expiracion, retiro con venta real atomica, permisos y configuracion.
 *
 * Sin @Transactional: cada operacion confirma su propia transaccion, como en produccion. Cada prueba crea sus propios
 * productos (el stock es por presentacion) y usa telefonos nuevos (el limite de pendientes es por telefono). Antes de
 * cada prueba se restauran los parametros de reserva (atencion todos los dias, cierre 23:59) para no depender del dia.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ReservaIntegrationTest {

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
        registry.add("app.vencimiento.activacion-habilitada", () -> "true");
        registry.add("app.catalogo.cache-segundos", () -> "0");
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private ReservaService reservaService;
    @Autowired private ControlVencimientoService controlService;
    @Autowired private LoteProduccionService loteProduccionService;
    @Autowired private AjusteInventarioService ajusteInventarioService;
    @Autowired private InventarioService inventarioService;
    @Autowired private VentaService ventaService;
    @Autowired private PrecioQuesoService precioQuesoService;
    @Autowired private CatalogoPublicoService catalogoService;
    @Autowired private KardexService kardexService;
    @Autowired private ReglaVencimiento regla;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private LoteProduccionRepository loteRepository;
    @Autowired private ReservaRepository reservaRepository;
    @Autowired private JdbcTemplate jdbc;

    private static final AtomicLong SECUENCIA_TELEFONO = new AtomicLong(10_000_000L + (System.nanoTime() % 50_000_000L));

    private Usuario usuario;
    private LocalDate hoy;

    @BeforeEach
    void seed() {
        String s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("RESERVA_IT_" + s);
        rol = rolRepository.save(rol);
        Usuario u = new Usuario();
        u.setNombre("Reserva Test " + s);
        u.setEmail("reserva-" + s + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        usuario = usuarioRepository.save(u);
        autenticar();
        hoy = regla.hoy();
        restaurarConfiguracion();
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
        restaurarConfiguracion();
    }

    private void autenticar() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(usuario.getEmail(), null));
    }

    /** Parametros deterministas: se atiende todos los dias hasta las 23:59 (la prueba no depende del dia de la semana). */
    private void restaurarConfiguracion() {
        config("RESERVA_PLAZO_DIAS", "3");
        config("RESERVA_MAX_UNIDADES", "20");
        config("RESERVA_MAX_PENDIENTES_TELEFONO", "2");
        config("RESERVA_HORAS_PENDIENTE", "24");
        config("RESERVA_DIAS_ATENCION", "1,2,3,4,5,6,7");
        config("RESERVA_HORA_CIERRE", "23:59");
        config("RESERVA_DIAS_NO_LABORABLES", "NINGUNO");
    }

    private void config(String clave, String valor) {
        jdbc.update("UPDATE configuracion_sistema SET valor = ? WHERE clave = ?", valor, clave);
    }

    // ------------------------------------------------------------------ utilidades de datos

    private Producto nuevoProducto() {
        String s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Familia f = new Familia();
        f.setNombre("Familia Res " + s);
        Producto p = new Producto();
        p.setNombre("Yogurt Res " + s);
        p.setFamilia(familiaRepository.save(f));
        return productoRepository.save(p);
    }

    private Presentacion presentacion(Producto producto, String nombre, String unidadConteo) {
        Presentacion pr = new Presentacion();
        pr.setNombre(nombre);
        pr.setProducto(producto);
        pr.setUnidadConteo(unidadConteo);
        return presentacionRepository.save(pr);
    }

    /** Producto con control de vencimiento y UNA presentacion con precio PUBLICO. */
    private Presentacion presentacionControlada(String unidadConteo, String precioPublico) {
        Producto p = nuevoProducto();
        Presentacion pr = presentacion(p, "Botella 1 L", unidadConteo);
        controlService.activar(p.getId());
        if (precioPublico != null) precioPublico(pr, precioPublico);
        return presentacionRepository.findById(pr.getId()).orElseThrow();
    }

    private Presentacion otraPresentacion(Presentacion de, String nombre, String unidadConteo, String precioPublico) {
        Presentacion pr = presentacion(de.getProducto(), nombre, unidadConteo);
        pr.setSaldoSinTrazabilidadInicial(0); // lo que hace PresentacionService.crear en un producto ya controlado
        pr = presentacionRepository.save(pr);
        if (precioPublico != null) precioPublico(pr, precioPublico);
        return pr;
    }

    private void precioPublico(Presentacion pr, String valor) {
        precioQuesoService.crear(new PrecioQuesoRequest(pr.getId(), "PUBLICO", new BigDecimal(valor), hoy.minusDays(10)));
    }

    /** Produce `cantidad` unidades que vencen en `vence` (el lote nace antes, para admitir vencimientos pasados). */
    private void producir(Presentacion pr, int cantidad, LocalDate vence) {
        LoteProduccion lote = new LoteProduccion();
        lote.setUuidCliente(UUID.randomUUID().toString());
        lote.setFecha(vence.isBefore(hoy.minusDays(1)) ? vence : hoy.minusDays(1));
        lote.setProducto(pr.getProducto());
        lote.setLitrosUsados(new BigDecimal("100"));
        lote.setResponsable(usuario);
        lote.setEstado(EstadoLote.EN_PRODUCCION);
        lote.setIniciadoEn(LocalDateTime.now());
        UUID loteId = loteRepository.save(lote).getId();
        loteProduccionService.finalizar(loteId, new FinalizarLoteRequest(
                List.of(new DetalleSalidaRequest(pr.getId(), cantidad)), null,
                List.of(new VencimientoSalidaRequest(pr.getId(), vence))));
    }

    private static String telefonoNuevo() {
        return String.format("9%08d", SECUENCIA_TELEFONO.incrementAndGet() % 100_000_000L);
    }

    private static CrearReservaRequest.Linea linea(Presentacion pr, int cantidad) {
        return new CrearReservaRequest.Linea(pr.getId(), cantidad);
    }

    private CrearReservaRequest req(String telefono, LocalDate retiro, CrearReservaRequest.Linea... lineas) {
        return new CrearReservaRequest(UUID.randomUUID().toString(), "Cliente Prueba", telefono, null, retiro, null, List.of(lineas));
    }

    private ReservaPublicaResponse reservar(String telefono, LocalDate retiro, Presentacion pr, int cantidad) {
        return reservaService.crear(req(telefono, retiro, linea(pr, cantidad)));
    }

    private UUID idDe(ReservaPublicaResponse r) {
        return reservaRepository.findByCodigo(r.codigo()).orElseThrow().getId();
    }

    private ReservaResponse detalle(ReservaPublicaResponse r) {
        return reservaService.obtener(idDe(r));
    }

    private ReservaPublicaResponse confirmada(String telefono, LocalDate retiro, Presentacion pr, int cantidad) {
        ReservaPublicaResponse r = reservar(telefono, retiro, pr, cantidad);
        reservaService.confirmar(idDe(r));
        return r;
    }

    private void vender(Presentacion pr, int cantidad) {
        ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(UUID.randomUUID().toString(), hoy, "PUBLICO",
                List.of(new DetalleVentaRequest(pr.getId(), cantidad))));
    }

    private long libro(UUID presentacionId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_inventario WHERE presentacion_id = ?", Long.class, presentacionId);
    }

    private long movimientos(UUID presentacionId) {
        return jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario WHERE presentacion_id = ?", Long.class, presentacionId);
    }

    private long ventas() {
        return jdbc.queryForObject("SELECT count(*) FROM venta", Long.class);
    }

    private long reservasDe(String telefonoNormalizado) {
        return jdbc.queryForObject("SELECT count(*) FROM reserva WHERE telefono = ?", Long.class, telefonoNormalizado);
    }

    private long pendientesDe(String telefonoNormalizado) {
        return jdbc.queryForObject("SELECT count(*) FROM reserva WHERE telefono = ? AND estado = 'PENDIENTE'", Long.class, telefonoNormalizado);
    }

    private static String normalizado(String nacional) {
        return "+51" + nacional;
    }

    private long comprometidas(Presentacion pr) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(d.cantidad), 0) FROM reserva_detalle d JOIN reserva r ON r.id = d.reserva_id "
                + "WHERE d.presentacion_id = ? AND r.estado IN ('PENDIENTE', 'CONFIRMADA')", Long.class, pr.getId());
    }

    private CatalogoPublicoResponse.Presentacion enCatalogo(Presentacion pr) {
        return catalogoService.obtener().productos().stream().flatMap(p -> p.presentaciones().stream())
                .filter(p -> p.id().equals(pr.getId())).findFirst().orElse(null);
    }

    /** Un instante de vencimiento ya pasado para una reserva pendiente. */
    private void expirar(ReservaPublicaResponse r) {
        jdbc.update("UPDATE reserva SET expira_en = ? WHERE codigo = ?", Timestamp.valueOf(regla.ahora().minusHours(1)), r.codigo());
    }

    // ------------------------------------------------------------------ concurrencia

    private List<Object> enParalelo(List<Callable<Object>> tareas) throws Exception {
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

    private void soloRechazosDeNegocio(List<Object> resultados) {
        for (Object r : resultados) {
            if (r instanceof Exception e) {
                assertThat(e).as("solo rechazos de negocio: nunca errores de BD ni interbloqueos")
                        .isInstanceOfAny(ValidacionNegocioException.class, ConflictoNegocioException.class);
            }
        }
    }

    private long exitos(List<Object> resultados) {
        return resultados.stream().filter(r -> !(r instanceof Exception)).count();
    }

    // ================================================================== 1. creacion

    @Test
    void unaReservaValidaComprometeUnidadesSinTocarStockVentaNiKardex() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 30, hoy.plusDays(20));
        long libroAntes = libro(pr.getId());
        long movAntes = movimientos(pr.getId());
        long ventasAntes = ventas();
        String tel = telefonoNuevo();

        ReservaPublicaResponse r = reservar(tel + "", hoy.plusDays(1), pr, 2);

        assertThat(r.codigo()).matches("[A-Z0-9]{10}");
        assertThat(r.estado()).isEqualTo(EstadoReserva.PENDIENTE);
        assertThat(r.fechaRetiro()).isEqualTo(hoy.plusDays(1));
        assertThat(r.fechaMaximaRetiro()).isEqualTo(hoy.plusDays(3));
        assertThat(r.precioAConfirmar()).isFalse();
        assertThat(r.total()).isEqualByComparingTo("30.00");
        assertThat(r.lineas()).singleElement().satisfies(l -> {
            assertThat(l.cantidad()).isEqualTo(2);
            assertThat(l.precioUnitario()).isEqualByComparingTo("15.00");
            assertThat(l.subtotal()).isEqualByComparingTo("30.00");
        });
        assertThat(jdbc.queryForObject("SELECT telefono FROM reserva WHERE codigo = ?", String.class, r.codigo())).isEqualTo(normalizado(tel));
        assertThat(r.expiraEn()).isAfter(regla.ahora().plusHours(23)).isBefore(regla.ahora().plusHours(25));
        // No es una venta ni un movimiento: el stock fisico y el libro quedan EXACTAMENTE igual.
        assertThat(libro(pr.getId())).isEqualTo(libroAntes);
        assertThat(movimientos(pr.getId())).isEqualTo(movAntes);
        assertThat(ventas()).isEqualTo(ventasAntes);
        assertThat(comprometidas(pr)).isEqualTo(2);
        assertThat(detalle(r).eventos()).extracting(e -> e.tipo().name()).containsExactly("CREADA");
    }

    @Test
    void losDatosInvalidosSeRechazanSinCrearNada() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 10, hoy.plusDays(20));
        Presentacion sinControl = presentacion(nuevoProducto(), "Pieza", "unidad");
        Presentacion inactiva = otraPresentacion(pr, "Botella 2 L", "unidad", "20.00");
        inactiva.setActivo(false);
        presentacionRepository.save(inactiva);
        long antes = jdbc.queryForObject("SELECT count(*) FROM reserva", Long.class);
        String tel = telefonoNuevo();

        assertThatThrownBy(() -> reservaService.crear(new CrearReservaRequest(UUID.randomUUID().toString(), "   ", tel, null, hoy.plusDays(1), null, List.of(linea(pr, 1)))))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("nombre");
        assertThatThrownBy(() -> reservar("12345", hoy.plusDays(1), pr, 1))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("teléfono");
        assertThatThrownBy(() -> reservaService.crear(req(tel, hoy.plusDays(1), linea(pr, 1), linea(pr, 2))))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("más de una vez");
        assertThatThrownBy(() -> reservaService.crear(new CrearReservaRequest(UUID.randomUUID().toString(), "Ana", tel, null, hoy.plusDays(1), null,
                List.of(new CrearReservaRequest.Linea(UUID.randomUUID(), 1)))))
                .isInstanceOf(RecursoNoEncontradoException.class);
        assertThatThrownBy(() -> reservar(tel, hoy.plusDays(1), sinControl, 1)).isInstanceOf(RecursoInactivoException.class);
        assertThatThrownBy(() -> reservar(tel, hoy.plusDays(1), inactiva, 1)).isInstanceOf(RecursoInactivoException.class);
        assertThatThrownBy(() -> reservar(tel, hoy.plusDays(1), pr, 15))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("Solo hay 10");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM reserva", Long.class)).isEqualTo(antes);
    }

    // ================================================================== 2. limite de unidades

    @Test
    void elLimiteDe20UnidadesSumaTodasLasLineasPeroNoMezclaMedidasConUnidades() {
        Presentacion botellas = presentacionControlada("botella", "15.00");
        Presentacion piezas = otraPresentacion(botellas, "Pieza", "unidad", "10.00");
        Presentacion kilos = otraPresentacion(botellas, "Queso a granel", "kg", "40.00");
        producir(botellas, 60, hoy.plusDays(20));
        producir(piezas, 60, hoy.plusDays(20));
        producir(kilos, 60, hoy.plusDays(20));

        // 20 en total: valido. 21 en una sola linea y 12 + 12 repartidas en dos presentaciones: rechazado.
        reservaService.crear(req(telefonoNuevo(), hoy.plusDays(1), linea(botellas, 20)));
        assertThatThrownBy(() -> reservar(telefonoNuevo(), hoy.plusDays(1), botellas, 21))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("máximo 20 unidades").hasMessageContaining("pediste 21");
        assertThatThrownBy(() -> reservaService.crear(req(telefonoNuevo(), hoy.plusDays(1), linea(botellas, 12), linea(piezas, 12))))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("pediste 24");
        reservaService.crear(req(telefonoNuevo(), hoy.plusDays(1), linea(botellas, 10), linea(piezas, 10)));

        // Los kilogramos NO se suman con las unidades: 20 unidades + 20 kg es valido; 21 kg, no.
        reservaService.crear(req(telefonoNuevo(), hoy.plusDays(1), linea(botellas, 20), linea(kilos, 20)));
        assertThatThrownBy(() -> reservar(telefonoNuevo(), hoy.plusDays(1), kilos, 21))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("20 kg");
    }

    // ================================================================== 3. pendientes por telefono

    @Test
    void unTelefonoSoloPuedeTenerDosReservasPendientesAunqueCambieElFormato() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 100, hoy.plusDays(20));
        String nacional = telefonoNuevo();
        String conEspacios = nacional.substring(0, 3) + " " + nacional.substring(3, 6) + " " + nacional.substring(6);

        ReservaPublicaResponse primera = reservar(nacional, hoy.plusDays(1), pr, 1);
        reservar("+51 " + conEspacios.replace(' ', '-'), hoy.plusDays(1), pr, 1);
        assertThatThrownBy(() -> reservar("51" + nacional, hoy.plusDays(1), pr, 1))
                .isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("2 reservas pendientes");
        assertThatThrownBy(() -> reservar("+51" + conEspacios, hoy.plusDays(1), pr, 1)).isInstanceOf(ConflictoNegocioException.class);
        assertThat(pendientesDe(normalizado(nacional))).isEqualTo(2);

        reservar(telefonoNuevo(), hoy.plusDays(1), pr, 1); // otro telefono no se ve afectado

        // Al rechazar una, el telefono vuelve a poder reservar; una vencida tampoco cuenta.
        reservaService.rechazar(idDe(primera), new MotivoReservaRequest("Sin atención"));
        ReservaPublicaResponse tercera = reservar(nacional, hoy.plusDays(1), pr, 1);
        assertThat(pendientesDe(normalizado(nacional))).isEqualTo(2);
        expirar(tercera);
        reservar(nacional, hoy.plusDays(1), pr, 1);
    }

    @Test
    void ochoSolicitudesSimultaneasDeUnMismoTelefonoSoloDejanDosPendientes() throws Exception {
        for (int vuelta = 0; vuelta < 5; vuelta++) {
            Presentacion pr = presentacionControlada("unidad", "15.00");
            producir(pr, 100, hoy.plusDays(20));
            String nacional = telefonoNuevo();
            String[] formatos = {nacional, "+51" + nacional, "51" + nacional, "+51 " + nacional.substring(0, 3) + " " + nacional.substring(3)};
            List<Callable<Object>> tareas = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String formato = formatos[i % formatos.length];
                tareas.add(() -> reservar(formato, hoy.plusDays(1), pr, 1));
            }

            List<Object> r = enParalelo(tareas);

            soloRechazosDeNegocio(r);
            assertThat(exitos(r)).as("vuelta " + vuelta).isEqualTo(2);
            assertThat(pendientesDe(normalizado(nacional))).isEqualTo(2);
            assertThat(comprometidas(pr)).isEqualTo(2);
        }
    }

    // ================================================================== 4. telefono

    @Test
    void elTelefonoSeGuardaNormalizadoYLaConsultaPublicaLoAceptaEnCualquierFormato() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 30, hoy.plusDays(20));
        String nacional = telefonoNuevo();
        ReservaPublicaResponse r = reservar("+51 " + nacional.substring(0, 3) + "-" + nacional.substring(3, 6) + "-" + nacional.substring(6), hoy.plusDays(1), pr, 1);

        assertThat(jdbc.queryForObject("SELECT telefono FROM reserva WHERE codigo = ?", String.class, r.codigo())).isEqualTo(normalizado(nacional));
        assertThat(reservaService.consultar(new ConsultaReservaRequest(r.codigo(), nacional)).codigo()).isEqualTo(r.codigo());
        assertThat(reservaService.consultar(new ConsultaReservaRequest(r.codigo().toLowerCase(), "51" + nacional)).codigo()).isEqualTo(r.codigo());
        // Otro telefono, uno invalido o un codigo inexistente responden igual: 404.
        for (String telefono : List.of(telefonoNuevo(), "123", "")) {
            assertThatThrownBy(() -> reservaService.consultar(new ConsultaReservaRequest(r.codigo(), telefono)))
                    .isInstanceOf(RecursoNoEncontradoException.class);
        }
        assertThatThrownBy(() -> reservaService.consultar(new ConsultaReservaRequest("ZZZZZZZZZZ", nacional))).isInstanceOf(RecursoNoEncontradoException.class);
        for (String invalido : List.of("87654321", "887654321", "+52987654321", "9876543210", "abc")) {
            assertThatThrownBy(() -> reservar(invalido, hoy.plusDays(1), pr, 1)).as(invalido).isInstanceOf(ValidacionNegocioException.class);
        }
    }

    // ================================================================== 5. fechas

    @Test
    void elPlazoVaDesdeHoyHastaHoyMasTresInclusiveEnHoraDeLima() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 100, hoy.plusDays(30));

        ReservaPublicaResponse deHoy = reservar(telefonoNuevo(), hoy, pr, 1);
        ReservaPublicaResponse enElLimite = reservar(telefonoNuevo(), hoy.plusDays(3), pr, 1);
        assertThat(deHoy.fechaMaximaRetiro()).isEqualTo(hoy.plusDays(3));
        assertThat(enElLimite.fechaRetiro()).isEqualTo(hoy.plusDays(3));
        assertThatThrownBy(() -> reservar(telefonoNuevo(), hoy.plusDays(4), pr, 1))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("próximos 3 días");
        assertThatThrownBy(() -> reservar(telefonoNuevo(), hoy.minusDays(1), pr, 1))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("anterior a hoy");
    }

    @Test
    void losFeriadosYLosDiasSinAtencionSeLeenDeLaConfiguracionYElRetiroDelMismoDiaRespetaElHorario() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 100, hoy.plusDays(30));

        config("RESERVA_DIAS_NO_LABORABLES", hoy.plusDays(1).toString());
        assertThatThrownBy(() -> reservar(telefonoNuevo(), hoy.plusDays(1), pr, 1))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("no es un día de atención");
        reservar(telefonoNuevo(), hoy.plusDays(2), pr, 1);
        config("RESERVA_DIAS_NO_LABORABLES", "NINGUNO");
        reservar(telefonoNuevo(), hoy.plusDays(1), pr, 1);

        // Un dia de la semana sin atencion (el de hoy+2 se quita de la lista).
        int diaSemana = hoy.plusDays(2).getDayOfWeek().getValue();
        config("RESERVA_DIAS_ATENCION", java.util.stream.IntStream.rangeClosed(1, 7).filter(d -> d != diaSemana)
                .mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
        assertThatThrownBy(() -> reservar(telefonoNuevo(), hoy.plusDays(2), pr, 1))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("no es un día de atención");
        reservar(telefonoNuevo(), hoy.plusDays(3), pr, 1);
        config("RESERVA_DIAS_ATENCION", "1,2,3,4,5,6,7");

        // Horario: con cierre a las 00:00 ya no se acepta el retiro de HOY (pero si el de manana).
        config("RESERVA_HORA_CIERRE", "00:00");
        assertThatThrownBy(() -> reservar(telefonoNuevo(), hoy, pr, 1))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("cierra a las 00:00");
        reservar(telefonoNuevo(), hoy.plusDays(1), pr, 1);
    }

    // ================================================================== 6. precio publico / a confirmar

    @Test
    void unaLineaSinPrecioPublicoDejaElTotalEnNullYExigeAceptacionAntesDeConfirmar() {
        Presentacion conPrecio = presentacionControlada("unidad", "15.00");
        Presentacion sinPrecio = otraPresentacion(conPrecio, "Botella 2 L", "unidad", null);
        producir(conPrecio, 30, hoy.plusDays(20));
        producir(sinPrecio, 30, hoy.plusDays(20));

        ReservaPublicaResponse r = reservaService.crear(req(telefonoNuevo(), hoy.plusDays(1), linea(conPrecio, 2), linea(sinPrecio, 3)));

        assertThat(r.precioAConfirmar()).isTrue();
        assertThat(r.total()).as("nunca se inventa un total").isNull();
        assertThat(r.lineas()).extracting(l -> l.precioUnitario() == null).containsExactlyInAnyOrder(false, true);
        assertThat(jdbc.queryForObject("SELECT total FROM reserva WHERE codigo = ?", BigDecimal.class, r.codigo())).isNull();
        UUID id = idDe(r);
        assertThatThrownBy(() -> reservaService.confirmar(id)).isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("Precio a confirmar");
        assertThatThrownBy(() -> reservaService.registrarAceptacionDePrecio(id, new AceptacionPrecioRequest("WHATSAPP", null)))
                .isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("recotiza");

        // Se configura el precio publico, se recotiza (ya hay total) pero falta que el CLIENTE acepte el importe.
        precioPublico(sinPrecio, "20.00");
        ReservaResponse recotizada = reservaService.recotizar(id);
        assertThat(recotizada.total()).isEqualByComparingTo("90.00"); // 2*15 + 3*20
        assertThat(recotizada.precioAConfirmar()).isFalse();
        assertThat(recotizada.requiereAceptacion()).isTrue();
        assertThatThrownBy(() -> reservaService.confirmar(id)).isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("aceptación del importe");

        ReservaResponse aceptada = reservaService.registrarAceptacionDePrecio(id, new AceptacionPrecioRequest("WHATSAPP", "  Escribió que está de acuerdo  "));
        assertThat(aceptada.aceptacion().totalAceptado()).isEqualByComparingTo("90.00");
        assertThat(aceptada.aceptacion().medio()).isEqualTo("WHATSAPP");
        assertThat(aceptada.aceptacion().nota()).isEqualTo("Escribió que está de acuerdo");
        assertThat(aceptada.aceptacion().registradoPor()).isEqualTo(usuario.getNombre());
        assertThat(aceptada.aceptacion().aceptadoEn()).isNotNull();

        assertThat(reservaService.confirmar(id).estado()).isEqualTo(EstadoReserva.CONFIRMADA);
        assertThat(jdbc.queryForObject("SELECT aceptado_por FROM reserva WHERE id = ?", UUID.class, id)).isEqualTo(usuario.getId());
        assertThat(reservaService.obtener(id).eventos()).extracting(e -> e.tipo().name())
                .containsExactly("CREADA", "PRECIOS_RECOTIZADOS", "PRECIO_ACEPTADO", "CONFIRMADA");
    }

    @Test
    void siElPrecioPublicoCambiaDespuesDeLaCotizacionNoSeConfirmaHastaRecotizarYVolverAAceptar() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 30, hoy.plusDays(20));
        ReservaPublicaResponse r = reservar(telefonoNuevo(), hoy.plusDays(1), pr, 2);
        UUID id = idDe(r);
        assertThatThrownBy(() -> reservaService.registrarAceptacionDePrecio(id, new AceptacionPrecioRequest("TELEFONO", null)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("no requiere aceptación");

        precioQuesoService.crear(new PrecioQuesoRequest(pr.getId(), "PUBLICO", new BigDecimal("18.00"), hoy));

        assertThatThrownBy(() -> reservaService.confirmar(id)).isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("cambió");
        ReservaResponse recotizada = reservaService.recotizar(id);
        assertThat(recotizada.total()).isEqualByComparingTo("36.00");
        assertThat(recotizada.requiereAceptacion()).isTrue();
        assertThatThrownBy(() -> reservaService.confirmar(id)).isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("aceptación");
        reservaService.registrarAceptacionDePrecio(id, new AceptacionPrecioRequest("PRESENCIAL", null));
        assertThat(reservaService.confirmar(id).estado()).isEqualTo(EstadoReserva.CONFIRMADA);
    }

    // ================================================================== 7. lotes que vencen antes de la fecha de retiro

    @Test
    void unLoteQueVenceAntesDeLaFechaDeRetiroNoRespaldaLaReserva() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 10, hoy.plusDays(1));   // vence manana
        producir(pr, 5, hoy.plusDays(10));   // vence dentro de 10 dias

        assertThatThrownBy(() -> reservar(telefonoNuevo(), hoy.plusDays(2), pr, 8))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("Solo hay 5").hasMessageContaining(hoy.plusDays(2).toString());
        reservar(telefonoNuevo(), hoy.plusDays(2), pr, 5);                    // solo cuenta el lote largo
        reservar(telefonoNuevo(), hoy.plusDays(1), pr, 10);                   // para manana sirven los dos lotes
        assertThatThrownBy(() -> reservar(telefonoNuevo(), hoy.plusDays(1), pr, 1))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("No hay unidades disponibles");
        assertThat(comprometidas(pr)).isEqualTo(15);
        assertThat(libro(pr.getId())).as("el stock fisico no cambia").isEqualTo(15);
    }

    @Test
    void elDiaDeVencimientoDelLoteEsInclusivoParaLaFechaDeRetiro() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 4, hoy.plusDays(2));

        reservar(telefonoNuevo(), hoy.plusDays(2), pr, 4); // retira el ultimo dia valido del lote
        assertThatThrownBy(() -> reservar(telefonoNuevo(), hoy.plusDays(3), pr, 1)).isInstanceOf(ValidacionNegocioException.class);
    }

    // ================================================================== 8. vencidas y sin trazabilidad

    @Test
    void lasUnidadesVencidasYSinTrazabilidadNoRespaldanReservas() {
        Producto p = nuevoProducto();
        Presentacion pr = presentacion(p, "Botella 1 L", "unidad");
        precioPublico(pr, "15.00");
        ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), 30, "Existencias anteriores"));
        controlService.activar(p.getId());                   // las 30 quedan SIN TRAZABILIDAD
        producir(pr, 20, hoy.minusDays(1));                  // 20 YA VENCIDAS
        producir(pr, 4, hoy.plusDays(10));                   // 4 vendibles
        assertThat(libro(pr.getId())).isEqualTo(54);

        assertThatThrownBy(() -> reservar(telefonoNuevo(), hoy.plusDays(1), pr, 5))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("Solo hay 4");
        reservar(telefonoNuevo(), hoy.plusDays(1), pr, 4);

        // Regularizar las existencias anteriores con una fecha las vuelve vendibles (y reservables).
        controlService.regularizar(pr.getId(), new RegularizacionRequest(6, hoy.plusDays(5), "Etiqueta del lote anterior"));
        reservar(telefonoNuevo(), hoy.plusDays(1), pr, 6);
        assertThatThrownBy(() -> reservar(telefonoNuevo(), hoy.plusDays(1), pr, 1)).isInstanceOf(ValidacionNegocioException.class);
    }

    // ================================================================== 9. dos reservas simultaneas

    @Test
    void seisReservasSimultaneasNuncaComprometenLasMismasUnidades() throws Exception {
        for (int vuelta = 0; vuelta < 6; vuelta++) {
            Presentacion pr = presentacionControlada("unidad", "15.00");
            producir(pr, 10, hoy.plusDays(20));
            List<Callable<Object>> tareas = new ArrayList<>();
            for (int i = 0; i < 6; i++) tareas.add(() -> reservar(telefonoNuevo(), hoy.plusDays(1), pr, 4));

            List<Object> r = enParalelo(tareas);

            soloRechazosDeNegocio(r);
            assertThat(exitos(r)).as("vuelta " + vuelta + ": caben 2 de 4 en 10").isEqualTo(2);
            assertThat(comprometidas(pr)).isEqualTo(8);
            assertThat(libro(pr.getId())).isEqualTo(10);
        }
    }

    // ================================================================== 10. reserva concurrente con venta directa

    @Test
    void unaReservaYUnaVentaDirectaNuncaSeRepartenLasMismasUnidades() throws Exception {
        for (int vuelta = 0; vuelta < 12; vuelta++) {
            Presentacion pr = presentacionControlada("unidad", "15.00");
            producir(pr, 10, hoy.plusDays(20));
            String tel = telefonoNuevo();

            List<Object> r = enParalelo(List.of(
                    () -> reservar(tel, hoy.plusDays(1), pr, 6),
                    () -> { vender(pr, 6); return "venta"; }));

            soloRechazosDeNegocio(r);
            assertThat(exitos(r)).as("vuelta " + vuelta + ": 6 + 6 no caben en 10").isEqualTo(1);
            boolean reservo = r.get(0) instanceof ReservaPublicaResponse;
            assertThat(comprometidas(pr)).isEqualTo(reservo ? 6 : 0);
            assertThat(libro(pr.getId())).isEqualTo(reservo ? 10 : 4);
        }
    }

    @Test
    void unaReservaYUnaVentaQueSiCabenJuntasPasanAmbasEnCualquierOrden() throws Exception {
        for (int vuelta = 0; vuelta < 8; vuelta++) {
            Presentacion pr = presentacionControlada("unidad", "15.00");
            producir(pr, 10, hoy.plusDays(20));

            List<Object> r = enParalelo(List.of(
                    () -> reservar(telefonoNuevo(), hoy.plusDays(1), pr, 4),
                    () -> { vender(pr, 6); return "venta"; }));

            assertThat(r).as("vuelta " + vuelta).noneMatch(o -> o instanceof Exception);
            assertThat(comprometidas(pr)).isEqualTo(4);
            assertThat(libro(pr.getId())).isEqualTo(4);
        }
    }

    @Test
    void unaVentaDirectaNoPuedeConsumirLoQueUnaReservaVigenteNecesita() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 10, hoy.plusDays(20));
        confirmada(telefonoNuevo(), hoy.plusDays(1), pr, 8);
        long movAntes = movimientos(pr.getId());

        assertThatThrownBy(() -> vender(pr, 3))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("comprometido por reservas").hasMessageContaining("reservadas 8");
        assertThat(movimientos(pr.getId())).as("la venta rechazada no escribe nada").isEqualTo(movAntes);
        vender(pr, 2); // lo no reservado si se vende
        assertThat(libro(pr.getId())).isEqualTo(8);
    }

    // ================================================================== 11. confirmacion concurrente con ajustes

    @Test
    void confirmarContraUnAjusteConcurrenteNuncaDejaUnFaltanteSinRegistrar() throws Exception {
        for (int vuelta = 0; vuelta < 10; vuelta++) {
            Presentacion pr = presentacionControlada("unidad", "15.00");
            producir(pr, 10, hoy.plusDays(20));
            ReservaPublicaResponse r = reservar(telefonoNuevo(), hoy.plusDays(1), pr, 8);
            UUID id = idDe(r);

            List<Object> res = enParalelo(List.of(
                    () -> reservaService.confirmar(id),
                    () -> ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), 3, "Se rompieron botellas"))));

            soloRechazosDeNegocio(res);
            assertThat(res.get(1)).as("la perdida fisica real NUNCA se bloquea").isNotInstanceOf(Exception.class);
            assertThat(libro(pr.getId())).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM incidencia_cobertura WHERE presentacion_id = ?", Long.class, pr.getId()))
                    .as("vuelta " + vuelta + ": la reserva de 8 ya no tiene cobertura con 3: debe quedar la incidencia").isEqualTo(1);
            ReservaResponse d = reservaService.obtener(id);
            assertThat(d.sinCobertura()).isTrue();
            if (res.get(0) instanceof Exception) assertThat(d.estado()).isEqualTo(EstadoReserva.PENDIENTE);
            else assertThat(d.estado()).isEqualTo(EstadoReserva.CONFIRMADA);
        }
    }

    @Test
    void noSeConfirmaUnaReservaCuyaCoberturaSeRompioPorUnAjusteAnterior() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 10, hoy.plusDays(20));
        ReservaPublicaResponse r = reservar(telefonoNuevo(), hoy.plusDays(1), pr, 8);
        ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), 3, "Se rompieron botellas")); // la perdida SI se registra

        assertThatThrownBy(() -> reservaService.confirmar(idDe(r)))
                .isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("Ya no hay unidades suficientes");

        assertThat(reservaService.obtener(idDe(r)).estado()).isEqualTo(EstadoReserva.PENDIENTE);
        assertThat(libro(pr.getId())).isEqualTo(3);
        // La salida administrativa: rechazarla libera las unidades comprometidas.
        reservaService.rechazar(idDe(r), new MotivoReservaRequest("Sin stock por rotura"));
        assertThat(comprometidas(pr)).isZero();
    }

    @Test
    void unAjusteQueNoRompeLaCoberturaNoDejaIncidenciaYUnoQueSiLaRompeSeResuelveCancelando() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 10, hoy.plusDays(20));
        ReservaPublicaResponse r = confirmada(telefonoNuevo(), hoy.plusDays(1), pr, 6);
        UUID id = idDe(r);

        ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), 7, "Conteo: -3"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidencia_cobertura WHERE presentacion_id = ?", Long.class, pr.getId())).isZero();
        assertThat(reservaService.obtener(id).sinCobertura()).isFalse();

        ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), 2, "Conteo: -5"));
        assertThat(libro(pr.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT unidades_faltantes FROM incidencia_cobertura WHERE presentacion_id = ?", Integer.class, pr.getId())).isEqualTo(4);
        assertThat(reservaService.obtener(id).sinCobertura()).isTrue();

        reservaService.cancelar(id, new MotivoReservaRequest("Sin stock por rotura"));
        assertThat(reservaService.obtener(id).sinCobertura()).isFalse();
        assertThat(comprometidas(pr)).isZero();
    }

    // Fase 5 (panel): filtro por codigo, indicador sinCobertura en el listado y comprometido/libre en el inventario.
    @Test
    void elListadoFiltraPorCodigoYMarcaSinCoberturaYElInventarioInformaComprometidoYLibre() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 10, hoy.plusDays(20));
        ReservaPublicaResponse antigua = confirmada(telefonoNuevo(), hoy.plusDays(1), pr, 6);
        ReservaPublicaResponse nueva = reservar(telefonoNuevo(), hoy.plusDays(2), pr, 2);

        var porCodigo = reservaService.listar(null, null, null, antigua.codigo().toLowerCase(), null, PageRequest.of(0, 20)).contenido();
        assertThat(porCodigo).extracting(x -> x.codigo()).containsExactly(antigua.codigo());
        assertThat(porCodigo.get(0).sinCobertura()).isFalse();
        assertThat(reservaService.listar(null, null, null, "NOEXISTE00", null, PageRequest.of(0, 20)).contenido()).isEmpty();

        var fila = filaDeStock(pr);
        assertThat(fila.stock()).isEqualTo(10);
        assertThat(fila.stockVendible()).isEqualTo(10);
        assertThat(fila.stockComprometido()).isEqualTo(8);
        assertThat(fila.stockLibre()).as("10 vendibles - 8 reservadas").isEqualTo(2);

        ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), 2, "Conteo: -8")); // la perdida SI se registra
        var tras = reservaService.listar(null, null, null, null, null, PageRequest.of(0, 100)).contenido();
        assertThat(tras.stream().filter(x -> x.codigo().equals(antigua.codigo())).findFirst().orElseThrow().sinCobertura()).isTrue();
        assertThat(tras.stream().filter(x -> x.codigo().equals(nueva.codigo())).findFirst().orElseThrow().sinCobertura())
                .as("la mas antigua tiene prioridad: la nueva todavia cabe").isFalse();
        var despues = filaDeStock(pr);
        assertThat(despues.stockVendible()).isEqualTo(2);
        assertThat(despues.stockComprometido()).isEqualTo(8);
        assertThat(despues.stockLibre()).as("no se puede vender nada sin agravar el faltante").isZero();
    }

    private com.planta.acopio.dto.FilaStockResponse filaDeStock(Presentacion pr) {
        return inventarioService.listarStockPaginado(null, pr.getProducto().getNombre(), PageRequest.of(0, 20)).contenido().stream()
                .filter(f -> f.presentacionId().equals(pr.getId())).findFirst().orElseThrow();
    }

    // Fase 6: las reglas publicas coinciden con lo que el servidor acepta al crear la reserva.
    @Test
    void lasReglasPublicasListanSoloFechasQueElServidorAceptaAlReservar() {
        var reglas = reservaService.reglasPublicas();
        assertThat(reglas.maxUnidades()).isEqualTo(20);
        assertThat(reglas.horasPendiente()).isEqualTo(24);
        assertThat(reglas.fechasDisponibles()).isNotEmpty().isSorted();
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 10, hoy.plusDays(30));
        for (LocalDate fecha : reglas.fechasDisponibles()) {
            assertThat(reservar(telefonoNuevo(), fecha, pr, 1).fechaRetiro()).isEqualTo(fecha);
        }
        LocalDate fuera = hoy.plusDays(reglas.fechasDisponibles().isEmpty() ? 1 : 10);
        assertThatThrownBy(() -> reservar(telefonoNuevo(), fuera, pr, 1)).isInstanceOf(ValidacionNegocioException.class);
    }

    // ================================================================== 12. idempotencia

    @Test
    void repetirLaMismaSolicitudNoCreaOtraReservaNiComprometeDosVeces() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 10, hoy.plusDays(20));
        CrearReservaRequest peticion = req(telefonoNuevo(), hoy.plusDays(1), linea(pr, 4));

        ReservaPublicaResponse primera = reservaService.crear(peticion);
        ReservaPublicaResponse repetida = reservaService.crear(peticion);

        assertThat(repetida.codigo()).isEqualTo(primera.codigo());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reserva WHERE uuid_cliente = ?", Long.class, peticion.uuidCliente())).isEqualTo(1);
        assertThat(comprometidas(pr)).isEqualTo(4);

        CrearReservaRequest distinta = new CrearReservaRequest(peticion.uuidCliente(), peticion.nombreCliente(), peticion.telefono(), null,
                peticion.fechaRetiro(), null, List.of(linea(pr, 5)));
        assertThatThrownBy(() -> reservaService.crear(distinta)).isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("datos distintos");
        assertThat(comprometidas(pr)).isEqualTo(4);
    }

    @Test
    void cincoEnviosSimultaneosDeLaMismaSolicitudCreanUnaSolaReserva() throws Exception {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 10, hoy.plusDays(20));
        CrearReservaRequest peticion = req(telefonoNuevo(), hoy.plusDays(1), linea(pr, 4));

        List<Object> r = enParalelo(List.of(() -> reservaService.crear(peticion), () -> reservaService.crear(peticion),
                () -> reservaService.crear(peticion), () -> reservaService.crear(peticion), () -> reservaService.crear(peticion)));

        assertThat(r).noneMatch(o -> o instanceof Exception);
        assertThat(r.stream().map(o -> ((ReservaPublicaResponse) o).codigo()).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reserva WHERE uuid_cliente = ?", Long.class, peticion.uuidCliente())).isEqualTo(1);
        assertThat(comprometidas(pr)).isEqualTo(4);
    }

    // ================================================================== 13. transiciones

    @Test
    void soloSePermitenLasTransicionesDefinidas() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 50, hoy.plusDays(20));
        MotivoReservaRequest motivo = new MotivoReservaRequest("Motivo de prueba");

        ReservaPublicaResponse a = reservar(telefonoNuevo(), hoy.plusDays(1), pr, 1);
        UUID idA = idDe(a);
        assertThat(reservaService.confirmar(idA).estado()).isEqualTo(EstadoReserva.CONFIRMADA);
        assertThatThrownBy(() -> reservaService.confirmar(idA)).isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("CONFIRMADA");
        assertThatThrownBy(() -> reservaService.rechazar(idA, motivo)).isInstanceOf(ConflictoNegocioException.class);
        assertThatThrownBy(() -> reservaService.recotizar(idA)).isInstanceOf(ConflictoNegocioException.class);
        assertThat(reservaService.cancelar(idA, motivo).estado()).isEqualTo(EstadoReserva.CANCELADA);
        for (Runnable intento : List.<Runnable>of(() -> reservaService.confirmar(idA), () -> reservaService.rechazar(idA, motivo),
                () -> reservaService.cancelar(idA, motivo))) {
            assertThatThrownBy(intento::run).isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("CANCELADA");
        }

        ReservaPublicaResponse b = reservar(telefonoNuevo(), hoy.plusDays(1), pr, 1);
        UUID idB = idDe(b);
        assertThat(reservaService.rechazar(idB, motivo).estado()).isEqualTo(EstadoReserva.RECHAZADA);
        assertThatThrownBy(() -> reservaService.confirmar(idB)).isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("RECHAZADA");
        assertThatThrownBy(() -> reservaService.cancelar(idB, motivo)).isInstanceOf(ConflictoNegocioException.class);

        ReservaPublicaResponse c = reservar(telefonoNuevo(), hoy.plusDays(1), pr, 1);
        UUID idC = idDe(c);
        assertThatThrownBy(() -> ventaService.registrarRetiroDeReserva(idC, UUID.randomUUID().toString(), hoy))
                .as("no se retira una reserva pendiente").isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("PENDIENTE");
        assertThat(reservaService.cancelar(idC, motivo).estado()).isEqualTo(EstadoReserva.CANCELADA);

        // Historial: quien, cuando y por que.
        assertThat(reservaService.obtener(idA).eventos()).extracting(e -> e.tipo().name() + ":" + e.usuario() + ":" + e.motivo())
                .containsExactly("CREADA:null:null", "CONFIRMADA:" + usuario.getNombre() + ":null", "CANCELADA:" + usuario.getNombre() + ":Motivo de prueba");
        assertThat(reservaService.obtener(idB).eventos()).extracting(e -> e.tipo().name())
                .containsExactly("CREADA", "RECHAZADA");
        assertThat(comprometidas(pr)).as("rechazadas y canceladas no comprometen").isZero();
    }

    // ================================================================== 14. expiracion y liberacion

    @Test
    void unaReservaVencidaDejaDeComprometerStockAunqueNadieLaHayaConsultado() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 10, hoy.plusDays(20));
        ReservaPublicaResponse r = reservar(telefonoNuevo(), hoy.plusDays(1), pr, 8);
        assertThat(enCatalogo(pr).disponibilidad().name()).as("10 - 8 = 2 libres").isEqualTo("POCAS_UNIDADES");
        assertThatThrownBy(() -> reservar(telefonoNuevo(), hoy.plusDays(1), pr, 3)).isInstanceOf(ValidacionNegocioException.class);

        expirar(r); // pasan las 24 h: SIN consultar nada y sin ningun proceso programado

        reservar(telefonoNuevo(), hoy.plusDays(1), pr, 10); // las 10 unidades vuelven a estar libres
        assertThat(reservaService.obtener(idDe(r)).estado()).isEqualTo(EstadoReserva.VENCIDA);
        assertThat(reservaService.consultar(new ConsultaReservaRequest(r.codigo(),
                jdbc.queryForObject("SELECT telefono FROM reserva WHERE codigo = ?", String.class, r.codigo()))).estado()).isEqualTo(EstadoReserva.VENCIDA);
        assertThat(reservaService.listar("VENCIDA", null, null, null, null, PageRequest.of(0, 100)).contenido()).extracting(x -> x.codigo()).contains(r.codigo());
        assertThat(reservaService.listar("PENDIENTE", null, null, null, null, PageRequest.of(0, 100)).contenido()).extracting(x -> x.codigo()).doesNotContain(r.codigo());
        assertThatThrownBy(() -> reservaService.confirmar(idDe(r))).isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("VENCIDA");
        assertThatThrownBy(() -> reservaService.rechazar(idDe(r), new MotivoReservaRequest("x"))).isInstanceOf(ConflictoNegocioException.class);
        assertThat(libro(pr.getId())).as("vencer no mueve el stock").isEqualTo(10);
    }

    @Test
    void unaReservaConfirmadaSeVenceAlTerminarSuFechaDeRetiroYLiberaLasUnidades() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 10, hoy.plusDays(20));
        ReservaPublicaResponse r = confirmada(telefonoNuevo(), hoy.plusDays(1), pr, 8);
        assertThatThrownBy(() -> vender(pr, 5)).isInstanceOf(ValidacionNegocioException.class);

        jdbc.update("UPDATE reserva SET fecha_retiro = ? WHERE codigo = ?", java.sql.Date.valueOf(hoy.minusDays(1)), r.codigo()); // paso su dia

        assertThat(reservaService.obtener(idDe(r)).estado()).isEqualTo(EstadoReserva.VENCIDA);
        vender(pr, 10); // ya no compromete nada
        assertThatThrownBy(() -> reservaService.cancelar(idDe(r), new MotivoReservaRequest("x"))).isInstanceOf(ConflictoNegocioException.class);
        assertThatThrownBy(() -> ventaService.registrarRetiroDeReserva(idDe(r), UUID.randomUUID().toString(), hoy)).isInstanceOf(ConflictoNegocioException.class);
    }

    // ================================================================== 15. retiro con venta real

    @Test
    void elRetiroEsUnaVentaRealPorElFlujoNormalConFefoYKardexYMarcaLaReservaEnLaMismaTransaccion() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 6, hoy.plusDays(3));
        producir(pr, 10, hoy.plusDays(10));
        ReservaPublicaResponse a = confirmada(telefonoNuevo(), hoy.plusDays(1), pr, 8);
        ReservaPublicaResponse otra = reservar(telefonoNuevo(), hoy.plusDays(2), pr, 5);
        UUID id = idDe(a);
        long movAntes = movimientos(pr.getId());
        long ventasAntes = ventas();
        String uuidVenta = UUID.randomUUID().toString();

        VentaResumenResponse venta = ventaService.registrarRetiroDeReserva(id, uuidVenta, hoy);

        assertThat(venta.tipoCliente()).isEqualTo("PUBLICO");
        assertThat(venta.total()).isEqualByComparingTo("120.00");
        ReservaResponse d = reservaService.obtener(id);
        assertThat(d.estado()).isEqualTo(EstadoReserva.RETIRADA);
        assertThat(d.ventaId()).isEqualTo(venta.id());
        assertThat(reservaService.obtener(id).eventos()).extracting(e -> e.tipo().name()).containsExactly("CREADA", "CONFIRMADA", "RETIRADA");
        // Un solo movimiento de venta (-8), FEFO: 6 del lote que vence antes y 2 del siguiente.
        assertThat(movimientos(pr.getId())).isEqualTo(movAntes + 1);
        assertThat(jdbc.queryForObject("SELECT cantidad FROM movimiento_inventario WHERE presentacion_id = ? AND tipo_movimiento = 'SALIDA_VENTA'", Integer.class, pr.getId())).isEqualTo(-8);
        assertThat(jdbc.queryForList("SELECT a.cantidad FROM movimiento_inventario_lote a JOIN movimiento_inventario m ON m.id = a.movimiento_id "
                + "WHERE m.presentacion_id = ? AND m.tipo_movimiento = 'SALIDA_VENTA' ORDER BY a.cantidad", Integer.class, pr.getId())).containsExactly(-6, -2);
        assertThat(libro(pr.getId())).isEqualTo(8);
        assertThat(kardexService.presentacion(pr.getId(), null, null, PageRequest.of(0, 50)).saldoFinal()).isEqualByComparingTo(BigDecimal.valueOf(8));
        assertThat(ventas()).isEqualTo(ventasAntes + 1);
        // La otra reserva sigue comprometida y cubierta; la retirada ya no compromete.
        assertThat(reservaService.obtener(idDe(otra)).sinCobertura()).isFalse();
        assertThat(comprometidas(pr)).isEqualTo(5);
        // Repetir el retiro con el mismo uuid devuelve la misma venta: no hay una segunda.
        assertThat(ventaService.registrarRetiroDeReserva(id, uuidVenta, hoy).id()).isEqualTo(venta.id());
        assertThat(ventas()).isEqualTo(ventasAntes + 1);
    }

    @Test
    void siLaVentaDelRetiroFallaLaReservaSigueConfirmadaYNoQuedaNadaEscrito() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 20, hoy.plusDays(20));
        ReservaPublicaResponse r = confirmada(telefonoNuevo(), hoy.plusDays(1), pr, 4);
        UUID id = idDe(r);
        long movAntes = movimientos(pr.getId());
        long ventasAntes = ventas();

        // El precio publico cambia despues de cotizar: la venta saldria a otro importe y NO se entrega.
        precioQuesoService.crear(new PrecioQuesoRequest(pr.getId(), "PUBLICO", new BigDecimal("17.00"), hoy));
        assertThatThrownBy(() -> ventaService.registrarRetiroDeReserva(id, UUID.randomUUID().toString(), hoy))
                .isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("no coincide con el cotizado");

        ReservaResponse d = reservaService.obtener(id);
        assertThat(d.estado()).as("atomico: la reserva NO paso a RETIRADA").isEqualTo(EstadoReserva.CONFIRMADA);
        assertThat(d.ventaId()).isNull();
        assertThat(ventas()).isEqualTo(ventasAntes);
        assertThat(movimientos(pr.getId())).isEqualTo(movAntes);
        assertThat(libro(pr.getId())).isEqualTo(20);
        assertThat(comprometidas(pr)).as("sigue comprometiendo sus unidades").isEqualTo(4);
        assertThat(reservaService.obtener(id).eventos()).extracting(e -> e.tipo().name()).containsExactly("CREADA", "CONFIRMADA");
    }

    @Test
    void elRetiroPorCarritoExigeCanalPublicoLineasDeLaReservaYCantidadesNoMayoresYPermiteRetirarMenos() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        Presentacion ajena = otraPresentacion(pr, "Botella 2 L", "unidad", "20.00");
        producir(pr, 30, hoy.plusDays(20));
        producir(ajena, 30, hoy.plusDays(20));
        ReservaPublicaResponse r = confirmada(telefonoNuevo(), hoy.plusDays(1), pr, 6);
        UUID id = idDe(r);

        assertThatThrownBy(() -> ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(UUID.randomUUID().toString(), hoy,
                "MAYORISTA", List.of(new DetalleVentaRequest(pr.getId(), 6)), id)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("canal PUBLICO");
        assertThatThrownBy(() -> ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(UUID.randomUUID().toString(), hoy,
                "PUBLICO", List.of(new DetalleVentaRequest(ajena.getId(), 1)), id)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("no forma parte de la reserva");
        assertThatThrownBy(() -> ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(UUID.randomUUID().toString(), hoy,
                "PUBLICO", List.of(new DetalleVentaRequest(pr.getId(), 7)), id)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("la reserva es de 6");
        assertThat(reservaService.obtener(id).estado()).isEqualTo(EstadoReserva.CONFIRMADA);
        assertThat(libro(pr.getId())).isEqualTo(30);

        // Retiro parcial: el cliente se lleva 4 de las 6; la reserva queda RETIRADA y libera el resto.
        ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(UUID.randomUUID().toString(), hoy,
                "PUBLICO", List.of(new DetalleVentaRequest(pr.getId(), 4)), id));
        assertThat(reservaService.obtener(id).estado()).isEqualTo(EstadoReserva.RETIRADA);
        assertThat(libro(pr.getId())).isEqualTo(26);
        assertThat(comprometidas(pr)).isZero();
        assertThatThrownBy(() -> ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(UUID.randomUUID().toString(), hoy,
                "PUBLICO", List.of(new DetalleVentaRequest(pr.getId(), 2)), id)))
                .as("una reserva ya retirada no se retira otra vez").isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("RETIRADA");
    }

    @Test
    void crearConfirmarCancelarYVencerNuncaMuevenElKardex() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 20, hoy.plusDays(20));
        long mov = movimientos(pr.getId());
        long saldo = libro(pr.getId());

        ReservaPublicaResponse a = reservar(telefonoNuevo(), hoy.plusDays(1), pr, 3);
        reservaService.confirmar(idDe(a));
        reservaService.cancelar(idDe(a), new MotivoReservaRequest("Cancelada"));
        ReservaPublicaResponse b = reservar(telefonoNuevo(), hoy.plusDays(1), pr, 3);
        expirar(b);
        ReservaPublicaResponse c = reservar(telefonoNuevo(), hoy.plusDays(1), pr, 3);
        reservaService.rechazar(idDe(c), new MotivoReservaRequest("Rechazada"));

        assertThat(movimientos(pr.getId())).isEqualTo(mov);
        assertThat(libro(pr.getId())).isEqualTo(saldo);
        assertThat(kardexService.presentacion(pr.getId(), null, null, PageRequest.of(0, 50)).saldoFinal()).isEqualByComparingTo(BigDecimal.valueOf(saldo));
    }

    // ================================================================== 16. permisos HTTP

    private <T> T anonimo(Callable<T> peticion) throws Exception {
        SecurityContextHolder.clearContext();
        TestSecurityContextHolder.clearContext(); // un user(...) previo dejaria autenticada a MockMvc
        try {
            return peticion.call();
        } finally {
            autenticar();
        }
    }

    @Test
    void elPublicoSoloCreaYConsultaSuReservaYElPersonalGestiona() throws Exception {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 30, hoy.plusDays(20));
        String tel = telefonoNuevo();
        String cuerpo = json.writeValueAsString(req(tel, hoy.plusDays(1), linea(pr, 2)));

        // Publico, SIN token.
        String respuesta = anonimo(() -> mvc.perform(post("/api/catalogo/reservas").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String codigo = json.readTree(respuesta).get("codigo").asText();
        anonimo(() -> mvc.perform(post("/api/catalogo/reservas/consulta").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ConsultaReservaRequest(codigo, tel)))).andExpect(status().isOk()));
        anonimo(() -> mvc.perform(post("/api/catalogo/reservas/consulta").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ConsultaReservaRequest(codigo, telefonoNuevo())))).andExpect(status().isNotFound()));
        UUID id = reservaRepository.findByCodigo(codigo).orElseThrow().getId();

        // El publico no ve ni cambia nada del personal.
        anonimo(() -> mvc.perform(get("/api/reservas")).andExpect(status().isUnauthorized()));
        anonimo(() -> mvc.perform(get("/api/reservas/" + id)).andExpect(status().isUnauthorized()));
        anonimo(() -> mvc.perform(put("/api/reservas/" + id + "/confirmar")).andExpect(status().isUnauthorized()));
        anonimo(() -> mvc.perform(put("/api/catalogo/reservas/" + id + "/confirmar")).andExpect(status().isUnauthorized()));

        // Otros roles: 403. VENTAS y ADMIN: gestionan.
        for (String rol : List.of("ACOPIADOR", "CALIDAD", "RECEPCION", "PRODUCCION")) {
            mvc.perform(get("/api/reservas").with(user(usuario.getEmail()).roles(rol))).andExpect(status().isForbidden());
            mvc.perform(put("/api/reservas/" + id + "/confirmar").with(user(usuario.getEmail()).roles(rol))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/reservas?estado=PENDIENTE&telefono=" + tel).with(user(usuario.getEmail()).roles("VENTAS"))).andExpect(status().isOk());
        mvc.perform(get("/api/reservas/" + id).with(user(usuario.getEmail()).roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(put("/api/reservas/" + id + "/confirmar").with(user(usuario.getEmail()).roles("VENTAS"))).andExpect(status().isOk());
        mvc.perform(put("/api/reservas/" + id + "/cancelar").contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"x\"}")
                .with(user(usuario.getEmail()).roles("ADMIN"))).andExpect(status().isOk());
        assertThat(reservaService.obtener(id).estado()).isEqualTo(EstadoReserva.CANCELADA);
    }

    @Test
    void soloAdminModificaLaConfiguracionDeReservasYQuedaAuditada() throws Exception {
        String url = "/api/admin/configuracion/RESERVA_PLAZO_DIAS";
        String cuerpo = "{\"valor\":\"5\"}";

        mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(cuerpo).with(user(usuario.getEmail()).roles("VENTAS"))).andExpect(status().isForbidden());
        anonimo(() -> mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andExpect(status().isUnauthorized()));
        assertThat(jdbc.queryForObject("SELECT valor FROM configuracion_sistema WHERE clave = 'RESERVA_PLAZO_DIAS'", String.class)).isEqualTo("3");

        mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(cuerpo).with(user(usuario.getEmail()).roles("ADMIN"))).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT valor FROM configuracion_sistema WHERE clave = 'RESERVA_PLAZO_DIAS'", String.class)).isEqualTo("5");
        assertThat(jdbc.queryForObject("SELECT actualizado_por FROM configuracion_sistema WHERE clave = 'RESERVA_PLAZO_DIAS'", UUID.class)).isEqualTo(usuario.getId());
    }

    // ================================================================== 17. configuracion persistente

    @Test
    void elPlazoLosLimitesYLosDiasDeAtencionSonConfigurablesYLasReservasExistentesConservanSuFechaMaxima() throws Exception {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 100, hoy.plusDays(30));
        ReservaPublicaResponse previa = reservar(telefonoNuevo(), hoy.plusDays(1), pr, 1);

        for (String[] cambio : new String[][]{{"RESERVA_PLAZO_DIAS", "5"}}) {
            mvc.perform(put("/api/admin/configuracion/" + cambio[0]).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"valor\":\"" + cambio[1] + "\"}").with(user(usuario.getEmail()).roles("ADMIN"))).andExpect(status().isOk());
        }
        autenticar();
        ReservaPublicaResponse nueva = reservar(telefonoNuevo(), hoy.plusDays(5), pr, 1);
        assertThat(nueva.fechaMaximaRetiro()).isEqualTo(hoy.plusDays(5));
        assertThat(reservaService.consultar(new ConsultaReservaRequest(previa.codigo(),
                jdbc.queryForObject("SELECT telefono FROM reserva WHERE codigo = ?", String.class, previa.codigo()))).fechaMaximaRetiro())
                .as("las existentes conservan la fecha maxima calculada al crearse").isEqualTo(hoy.plusDays(3));
        assertThatThrownBy(() -> reservar(telefonoNuevo(), hoy.plusDays(6), pr, 1)).isInstanceOf(ValidacionNegocioException.class);

        config("RESERVA_MAX_UNIDADES", "5");
        assertThatThrownBy(() -> reservar(telefonoNuevo(), hoy.plusDays(1), pr, 6)).hasMessageContaining("máximo 5 unidades");
        reservar(telefonoNuevo(), hoy.plusDays(1), pr, 5);

        config("RESERVA_MAX_PENDIENTES_TELEFONO", "1");
        String tel = telefonoNuevo();
        reservar(tel, hoy.plusDays(1), pr, 1);
        assertThatThrownBy(() -> reservar(tel, hoy.plusDays(1), pr, 1)).isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("1 reservas pendientes");

        config("RESERVA_HORAS_PENDIENTE", "1");
        ReservaPublicaResponse corta = reservar(telefonoNuevo(), hoy.plusDays(1), pr, 1);
        assertThat(corta.expiraEn()).isBefore(regla.ahora().plusMinutes(70)).isAfter(regla.ahora().plusMinutes(50));
    }

    @Test
    void unValorInvalidoDeConfiguracionSeRechazaYNoSeGuarda() throws Exception {
        for (String[] par : new String[][]{{"RESERVA_PLAZO_DIAS", "abc"}, {"RESERVA_PLAZO_DIAS", "99"}, {"RESERVA_DIAS_ATENCION", "8"},
                {"RESERVA_HORA_CIERRE", "25:00"}, {"RESERVA_DIAS_NO_LABORABLES", "navidad"}, {"RESERVA_MAX_UNIDADES", "0"}}) {
            String antes = jdbc.queryForObject("SELECT valor FROM configuracion_sistema WHERE clave = ?", String.class, par[0]);
            mvc.perform(put("/api/admin/configuracion/" + par[0]).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"valor\":\"" + par[1] + "\"}").with(user(usuario.getEmail()).roles("ADMIN"))).andExpect(status().isBadRequest());
            assertThat(jdbc.queryForObject("SELECT valor FROM configuracion_sistema WHERE clave = ?", String.class, par[0])).as(par[0] + "=" + par[1]).isEqualTo(antes);
        }
    }

    // ================================================================== 18. catalogo y funcionalidades previas

    @Test
    void laDisponibilidadPublicaDescuentaLasReservasVigentesYMuestraPrecioAConfirmar() {
        Presentacion conPrecio = presentacionControlada("unidad", "15.00");
        Presentacion sinPrecio = otraPresentacion(conPrecio, "Botella 2 L", "unidad", null);
        producir(conPrecio, 15, hoy.plusDays(20));
        producir(sinPrecio, 15, hoy.plusDays(20));
        assertThat(enCatalogo(conPrecio).disponibilidad().name()).isEqualTo("DISPONIBLE");
        assertThat(enCatalogo(sinPrecio).precio()).as("sin precio publico: 'Precio a confirmar', nunca un precio inventado").isNull();
        assertThat(enCatalogo(sinPrecio).precioAConfirmar()).isTrue();

        ReservaPublicaResponse r = reservar(telefonoNuevo(), hoy.plusDays(1), conPrecio, 12); // quedan 3
        assertThat(enCatalogo(conPrecio).disponibilidad().name()).isEqualTo("POCAS_UNIDADES");
        reservaService.cancelar(idDe(r), new MotivoReservaRequest("Libera"));
        assertThat(enCatalogo(conPrecio).disponibilidad().name()).isEqualTo("DISPONIBLE");
        reservar(telefonoNuevo(), hoy.plusDays(1), sinPrecio, 15); // la presentacion sin precio tambien se reserva
        assertThat(enCatalogo(sinPrecio).disponibilidad().name()).isEqualTo("AGOTADO");
    }

    @Test
    void unProductoSinReservasSeVendeExactamenteComoAntesYLoNoReservadoSiSePuedeVender() {
        Presentacion pr = presentacionControlada("unidad", "15.00");
        producir(pr, 10, hoy.plusDays(20));

        vender(pr, 10); // sin reservas, la venta sale como siempre (FEFO, kardex)

        assertThat(libro(pr.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidencia_cobertura WHERE presentacion_id = ?", Long.class, pr.getId())).isZero();
    }
}
