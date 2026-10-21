package com.planta.acopio.integration;

import com.planta.acopio.dto.CrearLoteRequest;
import com.planta.acopio.dto.DetalleSalidaRequest;
import com.planta.acopio.dto.RecepcionPlantaRequest;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.ConciliacionService;
import com.planta.acopio.service.InventarioLecheService;
import com.planta.acopio.service.LoteProduccionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

/**
 * Concurrencia REAL pedida explícitamente por el usuario (Caso 8): hilos con conexiones JDBC y
 * transacciones genuinamente independientes contra PostgreSQL 15 real (Testcontainers) -- no el lock en
 * memoria que simula LoteProduccionServiceTest con Mockito. Esta clase ejercita de verdad el
 * `SELECT ... FOR UPDATE` de `RecepcionPlantaRepository.bloquearPorIds()` bajo Postgres.
 *
 * Sin @Transactional a propósito (ni de clase ni de método): cada llamada a loteProduccionService.crear()
 * debe abrir su PROPIA transacción real, exactamente como pasaría con dos peticiones HTTP concurrentes.
 * Un solo @Test en su propio contenedor/contexto -- evita que datos reales confirmados por un test
 * contaminen el saldo GLOBAL (sin filtro) que lee otro test de esta suite.
 */
@Testcontainers
@SpringBootTest
class ProduccionLecheConcurrenciaSaldoIntegrationTest {

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
    }

    @Autowired private ConciliacionService conciliacionService;
    @Autowired private LoteProduccionService loteProduccionService;
    @Autowired private InventarioLecheService inventarioLecheService;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private FamiliaRepository familiaRepository;

    private Usuario responsable;
    private Unidad unidad;
    private Producto producto;
    private Presentacion presentacion;

    @BeforeEach
    void seedComun() {
        Rol rol = rolRepository.save(rolCon("PROD_CCS_IT_" + (System.nanoTime() % 1_000_000_000L)));
        responsable = usuarioRepository.save(usuarioCon(rol));
        unidad = unidadRepository.save(unidadCon(responsable));
        producto = productoRepository.save(productoCon());
        presentacion = presentacionRepository.save(presentacionCon(producto));
    }

    // Caso 8 explícito contra Postgres real: saldo real de 30 L, dos producciones concurrentes de 20 L cada
    // una (40 L pedidos en total) -- exactamente una debe tener éxito, la otra debe ver el saldo YA
    // actualizado por el SELECT ... FOR UPDATE real de Postgres y ser rechazada. Nunca las dos, nunca saldo
    // negativo.
    @Test
    void dosProduccionesConcurrentesConTransaccionesJdbcRealesSoloUnaTieneExitoYElSaldoNuncaQuedaNegativo() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(responsable.getEmail(), null));
        conciliacionService.registrarYConciliar(new RecepcionPlantaRequest(
                LocalDate.now(), null, unidad.getId(), BigDecimal.valueOf(30), BigDecimal.valueOf(30), List.of()));

        CyclicBarrier arranqueSincronizado = new CyclicBarrier(2);
        Callable<Boolean> produccionDe20 = () -> {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(responsable.getEmail(), null));
            arranqueSincronizado.await(5, TimeUnit.SECONDS); // maximiza la chance de solape real
            try {
                loteProduccionService.crear(new CrearLoteRequest(
                        UUID.randomUUID().toString(), LocalDate.now(), producto.getId(), BigDecimal.valueOf(20),
                        List.of(new DetalleSalidaRequest(presentacion.getId(), 5))));
                return true;
            } catch (ValidacionNegocioException ex) {
                return false;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Boolean> resultado1 = pool.submit(produccionDe20);
        Future<Boolean> resultado2 = pool.submit(produccionDe20);
        boolean exito1 = resultado1.get(10, TimeUnit.SECONDS);
        boolean exito2 = resultado2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(List.of(exito1, exito2)).containsExactlyInAnyOrder(true, false);
        assertThat(inventarioLecheService.obtenerSaldoDisponibleLitros()).isEqualByComparingTo(BigDecimal.valueOf(10)); // 30 - 20, nunca 30 - 40 = -10
    }

    private Rol rolCon(String nombre) {
        Rol r = new Rol();
        r.setNombre(nombre);
        return r;
    }

    private Usuario usuarioCon(Rol rol) {
        Usuario u = new Usuario();
        u.setNombre("Produccion Concurrencia Saldo Test");
        u.setEmail("produccion-concurrencia-saldo-" + System.nanoTime() + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        return u;
    }

    private Unidad unidadCon(Usuario responsable) {
        Unidad u = new Unidad();
        u.setPlaca("CCST-" + (System.nanoTime() % 1_000_000_000L));
        u.setResponsable(responsable);
        return u;
    }

    private Producto productoCon() {
        Familia familia = new Familia();
        familia.setNombre("Queso Concurrencia Saldo Test " + System.nanoTime());
        Producto t = new Producto();
        t.setNombre("Queso Concurrencia Saldo Test " + System.nanoTime());
        t.setFamilia(familiaRepository.save(familia));
        t.setRendimientoEsperadoPct(BigDecimal.valueOf(12));
        return t;
    }

    private Presentacion presentacionCon(Producto producto) {
        Presentacion p = new Presentacion();
        p.setNombre("Unidad");
        p.setProducto(producto);
        p.setUnidadConteo("unidad");
        return p;
    }
}
