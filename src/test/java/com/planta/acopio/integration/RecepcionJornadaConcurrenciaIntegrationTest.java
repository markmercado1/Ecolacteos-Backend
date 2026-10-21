package com.planta.acopio.integration;

import com.planta.acopio.dto.RecepcionJornadaRequest;
import com.planta.acopio.entity.EstadoJornada;
import com.planta.acopio.entity.Jornada;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.repository.JornadaRepository;
import com.planta.acopio.repository.RecepcionPlantaRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.ConciliacionService;
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
import java.time.LocalDateTime;
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
 * Verifica contra Postgres real el mismo fix aplicado en esta ronda a ConciliacionService.
 * registrarRecepcionDeJornada(): el flush() que hace real el INSERT (y por lo tanto la posible violación
 * del índice único parcial idx_recepcion_planta_jornada_unica) debe quedar DENTRO del try/catch, no
 * después. Sin este fix, una carrera real entre dos dispositivos recibiendo la misma jornada dejaba escapar
 * DataIntegrityViolationException sin traducir (500) en vez de ConflictoNegocioException (409) -- el mismo
 * patrón de bug que reveló ProduccionLecheConcurrenciaIdempotenciaIntegrationTest para LoteProduccionService,
 * detectado aquí por el mismo motivo (RecepcionPlanta.id también es un UUID @GeneratedValue en cliente).
 */
@Testcontainers
@SpringBootTest
class RecepcionJornadaConcurrenciaIntegrationTest {

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
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private JornadaRepository jornadaRepository;
    @Autowired private RecepcionPlantaRepository recepcionPlantaRepository;

    private Usuario responsable;
    private Jornada jornada;

    @BeforeEach
    void seedComun() {
        Rol rol = rolRepository.save(rolCon("RECEP_CC_IT_" + (System.nanoTime() % 1_000_000_000L)));
        responsable = usuarioRepository.save(usuarioCon(rol));
        Unidad unidad = unidadRepository.save(unidadCon(responsable));

        jornada = new Jornada();
        jornada.setUuidCliente("jornada-" + System.nanoTime());
        jornada.setAcopiador(responsable);
        jornada.setUnidad(unidad);
        jornada.setEstado(EstadoJornada.REGRESANDO_A_PLANTA);
        jornada.setHoraInicio(LocalDateTime.now().minusHours(3));
        jornada = jornadaRepository.save(jornada);
    }

    @Test
    void dosDispositivosRecibiendoLaMismaJornadaALaVezSoloUnoTieneExitoYElOtroRecibeConflictoLimpio() throws Exception {
        CyclicBarrier arranqueSincronizado = new CyclicBarrier(2);
        Callable<String> recibirJornada = () -> {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(responsable.getEmail(), null));
            arranqueSincronizado.await(5, TimeUnit.SECONDS);
            try {
                var recepcion = conciliacionService.registrarRecepcionDeJornada(new RecepcionJornadaRequest(
                        UUID.randomUUID().toString(), jornada.getId(), BigDecimal.valueOf(200),
                        null, LocalDateTime.now(), null));
                return "OK:" + recepcion.id();
            } catch (ConflictoNegocioException ex) {
                return "CONFLICTO";
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<String> resultado1 = pool.submit(recibirJornada);
        Future<String> resultado2 = pool.submit(recibirJornada);
        String r1 = resultado1.get(10, TimeUnit.SECONDS);
        String r2 = resultado2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        List<String> resultados = List.of(r1, r2);
        assertThat(resultados.stream().filter(r -> r.startsWith("OK:")).count()).isEqualTo(1);
        assertThat(resultados.stream().filter(r -> r.equals("CONFLICTO")).count()).isEqualTo(1);

        long recepcionesDeEstaJornada = recepcionPlantaRepository.findAllByOrderByFechaDesc().stream()
                .filter(r -> jornada.getId().equals(r.getJornada() != null ? r.getJornada().getId() : null))
                .count();
        assertThat(recepcionesDeEstaJornada).isEqualTo(1); // nunca dos recepciones para la misma jornada
    }

    private Rol rolCon(String nombre) {
        Rol r = new Rol();
        r.setNombre(nombre);
        return r;
    }

    private Usuario usuarioCon(Rol rol) {
        Usuario u = new Usuario();
        u.setNombre("Recepcion Concurrencia Test");
        u.setEmail("recepcion-concurrencia-" + System.nanoTime() + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        return u;
    }

    private Unidad unidadCon(Usuario responsable) {
        Unidad u = new Unidad();
        u.setPlaca("RCIT-" + (System.nanoTime() % 1_000_000_000L));
        u.setResponsable(responsable);
        return u;
    }
}
