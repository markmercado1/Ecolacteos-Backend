package com.planta.acopio.integration;

import com.planta.acopio.dto.AcopioPorJornadaResponse;
import com.planta.acopio.entity.*;
import com.planta.acopio.repository.*;
import com.planta.acopio.service.RegistroAcopioService;
import com.planta.acopio.service.UsuarioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paginacion pendiente: verificacion real (PostgreSQL 15 via Testcontainers) de las queries nativas nuevas
 * que un test con Mockito no puede cubrir: casts de parametros opcionales, LIKE con ESCAPE '!', la
 * agrupacion por jornada con ARRAY_AGG y su countQuery, los totales globales por proveedor y el orden
 * estable entre paginas. Requiere Docker (no se pudo ejecutar en la sesion que lo creo: Docker apagado).
 *
 * Todo se siembra en una zona y una fecha propias (2031-03-15) para no depender de los datos semilla.
 */
@Testcontainers
@SpringBootTest
@Transactional
class PaginacionPendienteIntegrationTest {

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

    private static final LocalDate DIA = LocalDate.of(2031, 3, 15);

    @Autowired private RegistroAcopioService registroAcopioService;
    @Autowired private UsuarioService usuarioService;
    @Autowired private RegistroAcopioRepository registroAcopioRepository;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private ZonaRepository zonaRepository;
    @Autowired private ProveedorRepository proveedorRepository;
    @Autowired private JornadaRepository jornadaRepository;

    private Zona zona;
    private Proveedor juan;
    private Proveedor maria;
    private Unidad unidadA;
    private Unidad unidadB;
    private Usuario acopiador;
    private Jornada jornada;
    private String sufijo;

    @BeforeEach
    void sembrar() {
        sufijo = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("PAG_IT_" + sufijo);
        rol = rolRepository.save(rol);

        acopiador = usuarioRepository.save(usuario("Acopiador Paginacion " + sufijo, rol));
        unidadA = unidadRepository.save(unidad("PA" + sufijo, acopiador));
        unidadB = unidadRepository.save(unidad("PB" + sufijo, acopiador));

        zona = new Zona();
        zona.setNombre("Zona paginacion " + sufijo);
        zona = zonaRepository.save(zona);

        juan = proveedorRepository.save(proveedor("Juan_50% Quispe " + sufijo, "J" + sufijo));
        maria = proveedorRepository.save(proveedor("Maria Huaman " + sufijo, "M" + sufijo));

        jornada = new Jornada();
        jornada.setUuidCliente("jornada-pag-" + sufijo);
        jornada.setAcopiador(acopiador);
        jornada.setUnidad(unidadB);
        jornada.setEstado(EstadoJornada.EN_RECOLECCION);
        jornada.setHoraInicio(DIA.atTime(5, 0));
        jornada = jornadaRepository.save(jornada);

        // Jornada: 3 entregas (la primera a las 06:00) -- una sola fila "Por jornada" con 30 L.
        registro(juan, unidadA, DIA.atTime(6, 0), "10", jornada);
        registro(maria, unidadA, DIA.atTime(7, 0), "12", jornada);
        registro(juan, unidadA, DIA.atTime(8, 0), "8", jornada);
        // Dos entregas sueltas (sin jornada), misma hora: el desempate por id debe dar un orden estable.
        registro(maria, unidadB, DIA.atTime(9, 0), "5", null);
        registro(maria, unidadB, DIA.atTime(9, 0), "7", null);
        // Otro dia: nunca debe aparecer.
        registro(juan, unidadA, DIA.plusDays(1).atTime(6, 0), "99", null);
    }

    @Test
    void registrosPaginadosRespetanFechaZonaYTotalReal() {
        var primera = registroAcopioService.buscarPaginado(DIA, zona.getId(), null, null, PageRequest.of(0, 2));
        var tercera = registroAcopioService.buscarPaginado(DIA, zona.getId(), null, null, PageRequest.of(2, 2));

        assertThat(primera.getTotalElements()).isEqualTo(5);
        assertThat(primera.getTotalPages()).isEqualTo(3);
        assertThat(primera.getContent()).hasSize(2);
        assertThat(tercera.getContent()).hasSize(1);
        // Orden descendente por fecha_hora: la primera fila es una de las de las 09:00.
        assertThat(primera.getContent().get(0).fechaHora()).isEqualTo(DIA.atTime(9, 0));
    }

    @Test
    void recorrerTodasLasPaginasDevuelveCadaRegistroUnaSolaVez() {
        List<UUID> vistos = new ArrayList<>();
        for (int pagina = 0; pagina < 5; pagina++) {
            registroAcopioService.buscarPaginado(DIA, zona.getId(), null, null, PageRequest.of(pagina, 1))
                    .getContent().forEach(r -> vistos.add(r.id()));
        }
        assertThat(vistos).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    void busquedaPorNombreYPlacaSeResuelveEnLaBdYEscapaComodines() {
        // "_50%" literal: con comodines sin escapar tambien coincidiria con otros nombres.
        var porNombre = registroAcopioService.buscarPaginado(DIA, zona.getId(), null, "juan_50%", PageRequest.of(0, 20));
        assertThat(porNombre.getTotalElements()).isEqualTo(2);
        assertThat(porNombre.getContent()).allMatch(r -> r.proveedorId().equals(juan.getId()));

        var porPlaca = registroAcopioService.buscarPaginado(DIA, zona.getId(), null, "pb" + sufijo, PageRequest.of(0, 20));
        assertThat(porPlaca.getTotalElements()).isEqualTo(2);

        var sinCoincidencias = registroAcopioService.buscarPaginado(DIA, zona.getId(), null, "juan_51", PageRequest.of(0, 20));
        assertThat(sinCoincidencias.getTotalElements()).isZero();
    }

    @Test
    void agrupacionPorJornadaNoParteLaJornadaYCuentaGruposNoRegistros() {
        var pagina = registroAcopioService.agruparPorJornada(DIA, zona.getId(), null, null, PageRequest.of(0, 20));

        // 1 jornada + 2 sueltos = 3 grupos (no 5 registros).
        assertThat(pagina.getTotalElements()).isEqualTo(3);
        AcopioPorJornadaResponse grupoJornada = pagina.getContent().stream()
                .filter(AcopioPorJornadaResponse::esJornada).findFirst().orElseThrow();
        assertThat(grupoJornada.clave()).isEqualTo(jornada.getId());
        assertThat(grupoJornada.cantidad()).isEqualTo(3);
        assertThat(grupoJornada.totalLitros()).isEqualByComparingTo("30");
        assertThat(grupoJornada.fecha()).isEqualTo(DIA.atTime(6, 0));
        // Placa y acopiador salen de la jornada (unidadB), no de la unidad de los registros (unidadA).
        assertThat(grupoJornada.placa()).isEqualTo(unidadB.getPlaca());
        assertThat(grupoJornada.acopiadorNombre()).isEqualTo(acopiador.getNombre());

        // La jornada (06:00) es la mas antigua: queda ultima en orden descendente.
        assertThat(pagina.getContent().get(2).esJornada()).isTrue();

        var paginaDeUno = registroAcopioService.agruparPorJornada(DIA, zona.getId(), null, null, PageRequest.of(1, 1));
        assertThat(paginaDeUno.getTotalPages()).isEqualTo(3);
        assertThat(paginaDeUno.getContent()).hasSize(1);
    }

    @Test
    void totalesPorProveedorSonGlobalesDelFiltro() {
        var totales = registroAcopioService.totalesPorProveedor(DIA, zona.getId(), null);

        assertThat(totales).hasSize(2);
        assertThat(totales.get(0).proveedorId()).isEqualTo(maria.getId()); // 12 + 5 + 7 = 24 L
        assertThat(totales.get(0).totalLitros()).isEqualByComparingTo("24");
        assertThat(totales.get(0).cantidad()).isEqualTo(3);
        assertThat(totales.get(1).totalLitros()).isEqualByComparingTo("18"); // Juan: 10 + 8
    }

    @Test
    void historialPorProveedorPaginadoIncluyeTodosLosDias() {
        var pagina = registroAcopioService.historialPorProveedor(juan.getId(), PageRequest.of(0, 2));

        assertThat(pagina.getTotalElements()).isEqualTo(3);
        assertThat(pagina.getContent().get(0).fechaHora()).isEqualTo(DIA.plusDays(1).atTime(6, 0));
    }

    @Test
    void usuariosPaginadosFiltranPorRolEstadoYBusquedaEnLaBd() {
        Rol rol = rolRepository.findByNombre("PAG_IT_" + sufijo).orElseThrow();
        Usuario inactivo = usuario("Beatriz Paginacion " + sufijo, rol);
        inactivo.setActivo(false);
        usuarioRepository.save(inactivo);

        var todos = usuarioService.buscar(rol.getNombre(), null, null, PageRequest.of(0, 20));
        assertThat(todos.getTotalElements()).isEqualTo(2);

        var activos = usuarioService.buscar(rol.getNombre(), true, null, PageRequest.of(0, 20));
        assertThat(activos.getTotalElements()).isEqualTo(1);

        var porBusqueda = usuarioService.buscar(null, null, "beatriz paginacion " + sufijo, PageRequest.of(0, 20));
        assertThat(porBusqueda.getContent()).extracting(u -> u.nombre()).containsExactly(inactivo.getNombre());
    }

    private void registro(Proveedor proveedor, Unidad unidad, LocalDateTime fechaHora, String litros, Jornada jornada) {
        RegistroAcopio r = new RegistroAcopio();
        r.setUuidCliente(UUID.randomUUID().toString());
        r.setProveedor(proveedor);
        r.setAcopiador(acopiador);
        r.setUnidad(unidad);
        r.setFechaHora(fechaHora);
        r.setLitros(new BigDecimal(litros));
        r.setJornada(jornada);
        // Fase E: la zona de una entrega es la que tenia el proveedor cuando entrego (se guarda al registrarla); los reportes por zona la usan.
        r.setZona(proveedor.getZonaActual());
        registroAcopioRepository.save(r);
    }

    private Usuario usuario(String nombre, Rol rol) {
        Usuario u = new Usuario();
        u.setNombre(nombre);
        u.setEmail("pag-" + UUID.randomUUID() + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        return u;
    }

    private Unidad unidad(String placa, Usuario responsable) {
        Unidad u = new Unidad();
        u.setPlaca(placa);
        u.setResponsable(responsable);
        return u;
    }

    private Proveedor proveedor(String nombre, String dni) {
        Proveedor p = new Proveedor();
        p.setNombre(nombre);
        p.setDni(dni);
        p.setZonaActual(zona);
        return p;
    }
}
