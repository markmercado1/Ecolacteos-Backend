package com.planta.acopio.integration;

import com.planta.acopio.entity.*;
import com.planta.acopio.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// CORE_DESIGN.md §16, prioridad 1 (idempotencia) + validacion de esquema real:
// levanta un PostgreSQL 15 real en Testcontainers, aplica schema.sql tal cual
// se desplegaria en produccion, y verifica que la restriccion UNIQUE de
// uuid_cliente (mejora #1, la contradiccion documento/SQL mas importante
// detectada en Fase 1) realmente existe a nivel de base de datos -- no solo
// en la anotacion @Column de la entidad JPA.
@Testcontainers
@SpringBootTest
class SchemaIntegrationTest {

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

    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private ZonaRepository zonaRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private ProveedorRepository proveedorRepository;
    @Autowired private RegistroAcopioRepository registroAcopioRepository;
    @Autowired private ConfiguracionSistemaRepository configuracionSistemaRepository;

    @Test
    void elEsquemaCargaCorrectamenteYLosCatalogosSemillaExisten() {
        // Si schema.sql tiene un error de sintaxis o de orden de dependencias
        // entre tablas, el contexto de Spring ni siquiera arranca -- el solo
        // hecho de llegar a este assert ya valida la integridad estructural.
        assertThat(configuracionSistemaRepository.findById("UMBRAL_CONCILIACION_PCT")).isPresent();
        assertThat(configuracionSistemaRepository.findById("UMBRAL_ANOMALIA_Z_SCORE")).isPresent();
        assertThat(configuracionSistemaRepository.findById("CAPACIDAD_PROCESAMIENTO_DIARIA_LITROS")).isPresent();
    }

    @Test
    void laBaseDeDatosRechazaUuidClienteDuplicadoEnRegistroAcopio() {
        // rol.nombre VARCHAR(30): prefijo corto + sufijo de 9 digitos para que el nombre generado quepa.
        Rol rolAcopiador = rolRepository.save(rolCon("ACOP_TEST_" + (System.nanoTime() % 1_000_000_000L)));
        Usuario usuario = usuarioRepository.save(usuarioCon(rolAcopiador));
        Zona zona = zonaRepository.save(zonaCon());
        Unidad unidad = unidadRepository.save(unidadCon(usuario));
        Proveedor proveedor = proveedorRepository.save(proveedorCon(zona));

        String uuidClienteCompartido = "11111111-1111-1111-1111-111111111111";

        RegistroAcopio primero = registroAcopioRepository.save(
                registroCon(uuidClienteCompartido, proveedor, usuario, unidad, BigDecimal.valueOf(40)));
        assertThat(primero.getId()).isNotNull();

        RegistroAcopio segundoConMismoUuid =
                registroCon(uuidClienteCompartido, proveedor, usuario, unidad, BigDecimal.valueOf(99));

        // La UNIQUE constraint sobre uuid_cliente (schema.sql) debe rechazar el
        // INSERT a nivel de base de datos, independientemente de la logica de
        // idempotencia en RegistroAcopioService (defensa en profundidad).
        assertThatThrownBy(() -> registroAcopioRepository.saveAndFlush(segundoConMismoUuid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Rol rolCon(String nombre) {
        Rol r = new Rol();
        r.setNombre(nombre);
        return r;
    }

    private Usuario usuarioCon(Rol rol) {
        Usuario u = new Usuario();
        u.setNombre("Usuario Test");
        u.setEmail("test-" + System.nanoTime() + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        return u;
    }

    private Zona zonaCon() {
        Zona z = new Zona();
        z.setNombre("Zona Test");
        return z;
    }

    private Unidad unidadCon(Usuario responsable) {
        Unidad u = new Unidad();
        u.setPlaca("TEST-" + (System.nanoTime() % 1_000_000_000L)); // placa VARCHAR(20): nanoTime completo no cabe
        u.setResponsable(responsable); // NOT NULL (mejora #2) -- si esto fallara, el schema no la aplico
        return u;
    }

    private Proveedor proveedorCon(Zona zona) {
        Proveedor p = new Proveedor();
        p.setNombre("Proveedor Test");
        p.setDni("DNI-" + (System.nanoTime() % 1_000_000_000L)); // dni VARCHAR(15): nanoTime completo no cabe (bug preexistente, no relacionado a este refactor)
        p.setZonaActual(zona);
        return p;
    }

    private RegistroAcopio registroCon(String uuidCliente, Proveedor proveedor, Usuario acopiador, Unidad unidad, BigDecimal litros) {
        RegistroAcopio r = new RegistroAcopio();
        r.setUuidCliente(uuidCliente);
        r.setProveedor(proveedor);
        r.setAcopiador(acopiador);
        r.setUnidad(unidad);
        r.setFechaHora(LocalDateTime.now());
        r.setLitros(litros);
        return r;
    }
}
