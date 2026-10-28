package com.planta.acopio.integration;

import com.planta.acopio.dto.CrearLoteRequest;
import com.planta.acopio.dto.DetalleSalidaRequest;
import com.planta.acopio.dto.FinalizarLoteRequest;
import com.planta.acopio.dto.LoteProduccionResponse;
import com.planta.acopio.dto.PlanificarLoteRequest;
import com.planta.acopio.dto.RecepcionPlantaRequest;
import com.planta.acopio.dto.RecetaInsumoRequest;
import com.planta.acopio.dto.RecetaRequest;
import com.planta.acopio.dto.RecetaResponse;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.ConciliacionService;
import com.planta.acopio.service.InventarioLecheService;
import com.planta.acopio.service.InventarioService;
import com.planta.acopio.service.LoteProduccionService;
import com.planta.acopio.service.RecetaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * V18 contra PostgreSQL 15 real (Testcontainers): Flyway aplica V18, flujo completo Recepción -> stock ->
 * receta -> producción planificada -> validación -> iniciar (consumo) -> resultado real -> inventario de
 * producto terminado, con filas reales. Sin @Transactional a propósito: cada llamada al servicio abre su
 * propia transacción real, para observar commits/rollbacks y concurrencia como en dos peticiones HTTP.
 * Cada test usa sus propios insumos/producto; la leche es un pool global, por eso se verifican DELTAS.
 */
@Testcontainers
@SpringBootTest
class RecetaProduccionIntegrationTest {

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
    @Autowired private LoteProduccionService loteProduccionService;
    @Autowired private RecetaService recetaService;
    @Autowired private InventarioLecheService inventarioLecheService;
    @Autowired private InventarioService inventarioService;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private InsumoRepository insumoRepository;
    @Autowired private JdbcTemplate jdbc;

    private Usuario responsable;
    private Unidad unidad;
    private Producto andino;
    private Presentacion pieza;
    private Insumo cuajo;
    private Insumo sal;

    @BeforeEach
    void seed() {
        String sufijo = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("PROD_RECETA_IT_" + sufijo);
        rol = rolRepository.save(rol);
        Usuario u = new Usuario();
        u.setNombre("Produccion Receta Test");
        u.setEmail("produccion-receta-" + sufijo + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        responsable = usuarioRepository.save(u);
        Unidad un = new Unidad();
        un.setPlaca("REC-" + sufijo);
        un.setResponsable(responsable);
        unidad = unidadRepository.save(un);

        Familia familia = new Familia();
        familia.setNombre("Queso Receta " + sufijo);
        Producto p = new Producto();
        p.setNombre("Queso Andino " + sufijo);
        p.setFamilia(familiaRepository.save(familia));
        andino = productoRepository.save(p);
        Presentacion pr = new Presentacion();
        pr.setNombre("Pieza");
        pr.setProducto(andino);
        pr.setUnidadConteo("pieza");
        pieza = presentacionRepository.save(pr);

        cuajo = insumoRepository.save(insumo("Cuajo " + sufijo, "g", "15"));
        sal = insumoRepository.save(insumo("Sal " + sufijo, "kg", "20"));
        autenticar();
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    private void autenticar() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(responsable.getEmail(), null));
    }

    private Insumo insumo(String nombre, String unidadMedida, String stock) {
        Insumo i = new Insumo();
        i.setNombre(nombre);
        i.setUnidadMedida(unidadMedida);
        i.setStock(new BigDecimal(stock));
        return i;
    }

    private void recibirLeche(String litros) {
        conciliacionService.registrarYConciliar(new RecepcionPlantaRequest(
                LocalDate.now(), null, unidad.getId(), new BigDecimal(litros), new BigDecimal(litros), List.of()));
    }

    private RecetaResponse recetaAndino() {
        return recetaService.crear(new RecetaRequest("Receta estándar", andino.getId(), pieza.getId(),
                new BigDecimal("100"), BigDecimal.ONE,
                List.of(new RecetaInsumoRequest(cuajo.getId(), BigDecimal.ONE),
                        new RecetaInsumoRequest(sal.getId(), BigDecimal.ONE))));
    }

    private LoteProduccionResponse planificar(RecetaResponse receta, String litros) {
        return loteProduccionService.planificar(new PlanificarLoteRequest(UUID.randomUUID().toString(),
                LocalDate.now(), receta.id(), new BigDecimal(litros)));
    }

    private BigDecimal stock(Insumo i) {
        return jdbc.queryForObject("SELECT stock FROM insumo WHERE id = ?", BigDecimal.class, i.getId());
    }

    private int stockPieza() {
        return inventarioService.obtenerStockDePresentacion(pieza.getId()).unidadesDisponibles();
    }

    private int movimientosLecheDe(UUID loteId) {
        return jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario_leche WHERE lote_produccion_id = ?",
                Integer.class, loteId);
    }

    private String estadoEnBd(UUID loteId) {
        return jdbc.queryForObject("SELECT estado FROM lote_produccion WHERE id = ?", String.class, loteId);
    }

    // Flujo completo del requerimiento: 1500 L, 15 g cuajo, 20 kg sal; 1000 L -> 10 esperadas, 9 obtenidas.
    @Test
    void flujoCompletoRecepcionRecetaPlanificadaIniciadaYFinalizadaContraPostgres() {
        recibirLeche("1500");
        BigDecimal lecheInicial = inventarioLecheService.obtenerSaldoDisponibleLitros();
        RecetaResponse receta = recetaAndino();

        var calculo = recetaService.calcular(receta.id(), new BigDecimal("1000"));
        assertThat(calculo.factor()).isEqualByComparingTo("10");
        assertThat(calculo.cantidadEsperada()).isEqualByComparingTo("10");
        assertThat(calculo.todoDisponible()).isTrue();

        LoteProduccionResponse plan = planificar(receta, "1000");
        assertThat(plan.estado()).isEqualTo("PLANIFICADA");
        assertThat(estadoEnBd(plan.id())).isEqualTo("PLANIFICADA");
        // Planificar no toca ningún stock ni crea producto terminado.
        assertThat(inventarioLecheService.obtenerSaldoDisponibleLitros()).isEqualByComparingTo(lecheInicial);
        assertThat(stock(cuajo)).isEqualByComparingTo("15");
        assertThat(stockPieza()).isZero();

        LoteProduccionResponse iniciado = loteProduccionService.iniciar(plan.id());
        assertThat(iniciado.estado()).isEqualTo("EN_PRODUCCION");
        assertThat(inventarioLecheService.obtenerSaldoDisponibleLitros())
                .isEqualByComparingTo(lecheInicial.subtract(new BigDecimal("1000")));
        assertThat(stock(cuajo)).isEqualByComparingTo("5");
        assertThat(stock(sal)).isEqualByComparingTo("10");
        assertThat(movimientosLecheDe(plan.id())).isEqualTo(1);
        assertThat(stockPieza()).isZero(); // todavía nada producido

        LoteProduccionResponse fin = loteProduccionService.finalizar(plan.id(),
                new FinalizarLoteRequest(List.of(new DetalleSalidaRequest(pieza.getId(), 9)), null));
        assertThat(fin.estado()).isEqualTo("FINALIZADA");
        assertThat(fin.cantidadEsperada()).isEqualByComparingTo("10");
        assertThat(fin.diferencia()).isEqualByComparingTo("-1");
        assertThat(stockPieza()).isEqualTo(9);

        // Doble finalización: ni duplica inventario ni cambia el resultado.
        loteProduccionService.finalizar(plan.id(),
                new FinalizarLoteRequest(List.of(new DetalleSalidaRequest(pieza.getId(), 9)), null));
        assertThatThrownBy(() -> loteProduccionService.finalizar(plan.id(),
                new FinalizarLoteRequest(List.of(new DetalleSalidaRequest(pieza.getId(), 10)), null)))
                .isInstanceOf(ConflictoNegocioException.class);
        assertThat(stockPieza()).isEqualTo(9);
    }

    @Test
    void iniciarSinStockSuficienteNoConsumeNadaYElLoteSiguePlanificado() {
        recibirLeche("1500");
        jdbc.update("UPDATE insumo SET stock = 6 WHERE id = ?", sal.getId());
        RecetaResponse receta = recetaAndino();
        LoteProduccionResponse plan = planificar(receta, "1000");
        BigDecimal lecheAntes = inventarioLecheService.obtenerSaldoDisponibleLitros();

        assertThatThrownBy(() -> loteProduccionService.iniciar(plan.id()))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("faltan 4 kg de Sal");

        assertThat(estadoEnBd(plan.id())).isEqualTo("PLANIFICADA");
        assertThat(movimientosLecheDe(plan.id())).isZero();
        assertThat(inventarioLecheService.obtenerSaldoDisponibleLitros()).isEqualByComparingTo(lecheAntes);
        assertThat(stock(cuajo)).isEqualByComparingTo("15");
        assertThat(stock(sal)).isEqualByComparingTo("6");
    }

    @Test
    void iniciarConLecheInsuficienteNoConsumeNada() {
        RecetaResponse receta = recetaAndino();
        LoteProduccionResponse plan = planificar(receta, "9000000"); // más que cualquier saldo de la suite

        assertThatThrownBy(() -> loteProduccionService.iniciar(plan.id()))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("Leche: solicitado 9000000 L");
        assertThat(movimientosLecheDe(plan.id())).isZero();
        assertThat(stock(cuajo)).isEqualByComparingTo("15");
    }

    @Test
    void cancelarUnPlanificadoNoConsumeYNoPermiteIniciarlo() {
        recibirLeche("200");
        LoteProduccionResponse plan = planificar(recetaAndino(), "100");

        loteProduccionService.cancelar(plan.id());

        assertThat(estadoEnBd(plan.id())).isEqualTo("CANCELADA");
        assertThat(stock(cuajo)).isEqualByComparingTo("15");
        assertThatThrownBy(() -> loteProduccionService.iniciar(plan.id())).isInstanceOf(ConflictoNegocioException.class);
    }

    // Dos producciones que compiten por el mismo insumo escaso (15 g de cuajo, 10 g cada una), en
    // transacciones JDBC reales simultáneas: exactamente una inicia, el stock nunca queda negativo y la que
    // falla no deja consumo de leche (rollback real de toda su transacción).
    @Test
    void dosInicioConcurrentesSobreElMismoInsumoSoloUnoConsumeYElOtroSeRevierteCompleto() throws Exception {
        recibirLeche("5000");
        RecetaResponse receta = recetaAndino();
        LoteProduccionResponse a = planificar(receta, "1000");
        LoteProduccionResponse b = planificar(receta, "1000");

        CyclicBarrier barrera = new CyclicBarrier(2);
        Callable<Boolean> iniciarA = iniciarConcurrente(a.id(), barrera);
        Callable<Boolean> iniciarB = iniciarConcurrente(b.id(), barrera);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Boolean> fa = pool.submit(iniciarA);
        Future<Boolean> fb = pool.submit(iniciarB);
        boolean okA = fa.get(20, TimeUnit.SECONDS);
        boolean okB = fb.get(20, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(List.of(okA, okB)).containsExactlyInAnyOrder(true, false);
        assertThat(stock(cuajo)).isEqualByComparingTo("5");
        UUID perdedor = okA ? b.id() : a.id();
        assertThat(estadoEnBd(perdedor)).isEqualTo("PLANIFICADA");
        assertThat(movimientosLecheDe(perdedor)).isZero();
    }

    // El mismo lote iniciado dos veces a la vez (doble click en dos pestañas): el bloqueo del lote serializa
    // y el segundo ve EN_PRODUCCION -- la leche y los insumos se consumen UNA sola vez.
    @Test
    void elMismoLoteIniciadoDosVecesALaVezConsumeUnaSolaVez() throws Exception {
        recibirLeche("2000");
        LoteProduccionResponse plan = planificar(recetaAndino(), "500");

        CyclicBarrier barrera = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Boolean> f1 = pool.submit(iniciarConcurrente(plan.id(), barrera));
        Future<Boolean> f2 = pool.submit(iniciarConcurrente(plan.id(), barrera));
        assertThat(f1.get(20, TimeUnit.SECONDS)).isTrue();
        assertThat(f2.get(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(movimientosLecheDe(plan.id())).isEqualTo(1);
        assertThat(stock(cuajo)).isEqualByComparingTo("10");
        assertThat(stock(sal)).isEqualByComparingTo("15");
    }

    private Callable<Boolean> iniciarConcurrente(UUID loteId, CyclicBarrier barrera) {
        return () -> {
            autenticar();
            barrera.await(5, TimeUnit.SECONDS);
            try {
                loteProduccionService.iniciar(loteId);
                return true;
            } catch (ValidacionNegocioException ex) {
                return false;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    // Compatibilidad: el registro directo (app móvil / registro manual) sigue en un paso, nace FINALIZADA, y
    // ahora descuenta los insumos que registra.
    @Test
    void registroDirectoSigueFuncionandoNaceFinalizadoYDescuentaInsumos() {
        recibirLeche("300");
        LoteProduccionResponse lote = loteProduccionService.crear(new CrearLoteRequest(UUID.randomUUID().toString(),
                LocalDate.now(), andino.getId(), new BigDecimal("100"), List.of(new DetalleSalidaRequest(pieza.getId(), 1)),
                null, List.of(new com.planta.acopio.dto.InsumoUsadoRequest(cuajo.getId(), new BigDecimal("2")))));

        assertThat(lote.estado()).isEqualTo("FINALIZADA");
        assertThat(lote.recetaId()).isNull();
        assertThat(lote.cantidadEsperada()).isNull();
        assertThat(stock(cuajo)).isEqualByComparingTo("13");
        assertThat(stockPieza()).isEqualTo(1);
    }
}
