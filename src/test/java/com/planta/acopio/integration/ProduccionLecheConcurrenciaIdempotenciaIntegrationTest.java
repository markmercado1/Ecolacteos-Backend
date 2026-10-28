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
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.LoteProduccionRepository;
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
 * Carrera real de idempotencia (mismo uuidCliente, dos conexiones JDBC distintas, dos transacciones reales)
 * pedida explícitamente por el usuario: exactamente una fila debe quedar persistida en lote_produccion, y
 * el hilo que pierde la carrera debe recibir ConflictoNegocioException de forma limpia -- no un hang, no
 * una sesión de Hibernate corrupta. Verifica en particular el punto que motivó la corrección de esta fase:
 * recuperar "el lote ganador" NO se intenta dentro de la transacción que falló (ver
 * LoteProduccionService.crear()), así que este test prueba el camino real: el perdedor recibe 409 limpio,
 * y una llamada de control posterior (fuera de esa transacción) sí encuentra el lote ganador.
 *
 * Un solo @Test en su propio contenedor/contexto (mismo motivo que
 * ProduccionLecheConcurrenciaSaldoIntegrationTest: sin @Transactional, los datos se confirman de verdad).
 */
@Testcontainers
@SpringBootTest
class ProduccionLecheConcurrenciaIdempotenciaIntegrationTest {

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
    @Autowired private InventarioLecheService inventarioLecheService;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private LoteProduccionRepository loteProduccionRepository;

    private Usuario responsable;
    private Unidad unidad;
    private Producto producto;
    private Presentacion presentacion;

    @BeforeEach
    void seedComun() {
        Rol rol = rolRepository.save(rolCon("PROD_CCI_IT_" + (System.nanoTime() % 1_000_000_000L)));
        responsable = usuarioRepository.save(usuarioCon(rol));
        unidad = unidadRepository.save(unidadCon(responsable));
        producto = productoRepository.save(productoCon());
        presentacion = presentacionRepository.save(presentacionCon(producto));
    }

    @Test
    void unaCarreraRealConElMismoUuidClientePersisteExactamenteUnLoteYElPerdedorRecibeConflictoLimpio() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(responsable.getEmail(), null));
        conciliacionService.registrarYConciliar(new RecepcionPlantaRequest(
                LocalDate.now(), null, unidad.getId(), BigDecimal.valueOf(300), BigDecimal.valueOf(300), List.of()));

        String uuidCompartido = UUID.randomUUID().toString();
        CyclicBarrier arranqueSincronizado = new CyclicBarrier(2);
        Callable<String> mismaProduccion = () -> {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(responsable.getEmail(), null));
            arranqueSincronizado.await(5, TimeUnit.SECONDS);
            try {
                var lote = loteProduccionService.crear(new CrearLoteRequest(
                        uuidCompartido, LocalDate.now(), producto.getId(), BigDecimal.valueOf(150),
                        List.of(new DetalleSalidaRequest(presentacion.getId(), 20))));
                return "OK:" + lote.id();
            } catch (ConflictoNegocioException ex) {
                return "CONFLICTO";
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<String> resultado1 = pool.submit(mismaProduccion);
        Future<String> resultado2 = pool.submit(mismaProduccion);
        String r1 = resultado1.get(10, TimeUnit.SECONDS);
        String r2 = resultado2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        // Exactamente uno gana ("OK:...") y el otro recibe el conflicto -- nunca los dos "OK", nunca los
        // dos "CONFLICTO" (eso significaria que ninguno logro persistir el lote).
        List<String> resultados = List.of(r1, r2);
        assertThat(resultados.stream().filter(r -> r.startsWith("OK:")).count()).isEqualTo(1);
        assertThat(resultados.stream().filter(r -> r.equals("CONFLICTO")).count()).isEqualTo(1);

        // Llamada de control DESPUES de la carrera, en una transaccion nueva y limpia: confirma que el
        // perdedor no dejo la sesion/conexion compartida en un estado corrupto, y que el lote ganador es
        // recuperable normalmente (idempotencia real via reintento, no recuperacion dentro de la
        // transaccion que fallo -- ver el comentario de LoteProduccionService.crear()).
        assertThat(loteProduccionRepository.findByUuidCliente(uuidCompartido)).isPresent();
        long filasConEseUuid = loteProduccionRepository.findAllByOrderByFechaDesc().stream()
                .filter(l -> l.getUuidCliente().equals(uuidCompartido)).count();
        assertThat(filasConEseUuid).isEqualTo(1); // nunca duplicado
        assertThat(inventarioLecheService.obtenerSaldoDisponibleLitros()).isEqualByComparingTo(BigDecimal.valueOf(150));
    }

    private Rol rolCon(String nombre) {
        Rol r = new Rol();
        r.setNombre(nombre);
        return r;
    }

    private Usuario usuarioCon(Rol rol) {
        Usuario u = new Usuario();
        u.setNombre("Produccion Concurrencia Idempotencia Test");
        u.setEmail("produccion-concurrencia-idem-" + System.nanoTime() + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        return u;
    }

    private Unidad unidadCon(Usuario responsable) {
        Unidad u = new Unidad();
        u.setPlaca("CCIT-" + (System.nanoTime() % 1_000_000_000L));
        u.setResponsable(responsable);
        return u;
    }

    private Producto productoCon() {
        Familia familia = new Familia();
        familia.setNombre("Queso Concurrencia Idempotencia Test " + System.nanoTime());
        Producto t = new Producto();
        t.setNombre("Queso Concurrencia Idempotencia Test " + System.nanoTime());
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
