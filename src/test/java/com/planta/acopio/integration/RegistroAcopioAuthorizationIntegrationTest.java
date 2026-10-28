package com.planta.acopio.integration;

import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.LoteProduccion;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.entity.Zona;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.LoteProduccionRepository;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.RegistroAcopioRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.repository.ZonaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 04 (docs/PROMPTS_FINALIZAR_MOVIL.md): "Comprueba con pruebas de autorización que PRODUCCION hoy
 * no puede leer GET /api/registros-acopio ni GET /api/registros-acopio/proveedor/{id}" -- esta clase
 * primero documentaba ese 403 real (contra Spring Security real, no un mock de la anotación) y ahora
 * prueba que PRODUCCION y CALIDAD sí pueden leer, un rol no autorizado (VENTAS) sigue en 403, y que
 * `disponibleParaLote` distingue un registro libre de uno ya vinculado a un lote -- sin exponer nunca
 * DNI/teléfono del proveedor (RegistroAcopioResponse no los tiene) ni abrir el POST/las correcciones.
 *
 * Mismo patrón que SchemaIntegrationTest: PostgreSQL 15 real en Testcontainers, Flyway aplica el schema
 * real. @WithMockUser puebla el SecurityContext directamente (sin pasar por JwtAuthFilter, que no toca
 * nada si no hay header Authorization) -- alcanza para @PreAuthorize porque ninguno de estos endpoints
 * de solo lectura consulta Usuario/Rol por el nombre autenticado.
 */
// @Transactional en la clase: cada @Test corre en su propia transaccion, con
// rollback automatico al terminar -- sin esto, los registros que @BeforeEach
// inserta en cada test se acumularian en la misma tabla entre tests (el
// @SpringBootTest reusa el mismo contexto/BD para toda la clase) y las
// aserciones hasSize(2) del listado sin filtro (GET /api/registros-acopio)
// fallarian a partir del segundo test.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class RegistroAcopioAuthorizationIntegrationTest {

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

    @Autowired private MockMvc mockMvc;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private ZonaRepository zonaRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private ProveedorRepository proveedorRepository;
    @Autowired private RegistroAcopioRepository registroAcopioRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private LoteProduccionRepository loteProduccionRepository;

    private RegistroAcopio libre;
    private RegistroAcopio vinculado;
    private Proveedor proveedor;

    @BeforeEach
    void seedDatos() {
        // rol.nombre VARCHAR(30): prefijo corto + sufijo de 9 digitos para que el nombre generado quepa.
        Rol rolAcopiador = rolRepository.save(rolCon("ACOP_AT_" + (System.nanoTime() % 1_000_000_000L)));
        Usuario acopiador = usuarioRepository.save(usuarioCon(rolAcopiador));
        Zona zona = zonaRepository.save(zonaCon());
        Unidad unidad = unidadRepository.save(unidadCon(acopiador));
        proveedor = proveedorRepository.save(proveedorCon(zona));

        libre = registroAcopioRepository.save(registroCon("libre-" + System.nanoTime(), proveedor, acopiador, unidad));
        vinculado = registroAcopioRepository.save(registroCon("vinculado-" + System.nanoTime(), proveedor, acopiador, unidad));

        Rol rolProduccion = rolRepository.save(rolCon("PROD_AT_" + (System.nanoTime() % 1_000_000_000L)));
        Usuario responsable = usuarioRepository.save(usuarioProduccionCon(rolProduccion));
        Producto producto = productoRepository.save(productoCon());
        LoteProduccion lote = new LoteProduccion();
        lote.setUuidCliente("lote-" + System.nanoTime());
        lote.setFecha(LocalDate.now());
        lote.setProducto(producto);
        lote.setLitrosUsados(BigDecimal.valueOf(40));
        lote.setResponsable(responsable);
        lote.setRegistrosVinculados(Set.of(vinculado));
        loteProduccionRepository.save(lote);
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    void produccionPuedeListarBuscarYVerElDetalleDeRegistrosDeAcopio() throws Exception {
        mockMvc.perform(get("/api/registros-acopio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mockMvc.perform(get("/api/registros-acopio/proveedor/" + proveedor.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mockMvc.perform(get("/api/registros-acopio/" + libre.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(libre.getId().toString()));
    }

    @Test
    @WithMockUser(roles = "CALIDAD")
    void calidadSigueTeniendoAccesoDeLecturaTrasElCambio() throws Exception {
        mockMvc.perform(get("/api/registros-acopio"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/registros-acopio/proveedor/" + proveedor.getId()))
                .andExpect(status().isOk());
    }

    // Rol no autorizado para este recurso -- VENTAS no aparece en ninguna de
    // las tres lecturas, ni antes ni después de agregar PRODUCCION.
    @Test
    @WithMockUser(roles = "VENTAS")
    void unRolNoAutorizadoSigueRecibiendo403() throws Exception {
        mockMvc.perform(get("/api/registros-acopio")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/registros-acopio/proveedor/" + proveedor.getId())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/registros-acopio/" + libre.getId())).andExpect(status().isForbidden());
    }

    // PRODUCCION es lectura, no escritura -- ni crear registros ni anexar correcciones.
    @Test
    @WithMockUser(roles = "PRODUCCION")
    void produccionNuncaRecibePermisoDeEscrituraSobreRegistrosDeAcopio() throws Exception {
        mockMvc.perform(get("/api/registros-acopio/" + libre.getId() + "/correcciones"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    void disponibleParaLoteDistingueElRegistroLibreDelYaVinculado() throws Exception {
        mockMvc.perform(get("/api/registros-acopio/" + libre.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disponibleParaLote").value(true));

        mockMvc.perform(get("/api/registros-acopio/" + vinculado.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disponibleParaLote").value(false));

        mockMvc.perform(get("/api/registros-acopio/proveedor/" + proveedor.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + libre.getId() + "')].disponibleParaLote").value(true))
                .andExpect(jsonPath("$[?(@.id=='" + vinculado.getId() + "')].disponibleParaLote").value(false));
    }

    // RegistroAcopioResponse no tiene dni/telefono -- ampliar el permiso de
    // lectura a PRODUCCION no expone datos personales del proveedor.
    @Test
    @WithMockUser(roles = "PRODUCCION")
    void laRespuestaNuncaIncluyeDatosPersonalesDelProveedor() throws Exception {
        mockMvc.perform(get("/api/registros-acopio/" + libre.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proveedorNombre").value(proveedor.getNombre()))
                .andExpect(jsonPath("$.dni").doesNotExist())
                .andExpect(jsonPath("$.telefono").doesNotExist());
    }

    private Rol rolCon(String nombre) {
        Rol r = new Rol();
        r.setNombre(nombre);
        return r;
    }

    private Usuario usuarioCon(Rol rol) {
        Usuario u = new Usuario();
        u.setNombre("Acopiador Test");
        u.setEmail("acopiador-" + System.nanoTime() + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        return u;
    }

    private Usuario usuarioProduccionCon(Rol rol) {
        Usuario u = new Usuario();
        u.setNombre("Produccion Test");
        u.setEmail("produccion-" + System.nanoTime() + "@planta.com");
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
        u.setPlaca("AUTH-" + (System.nanoTime() % 1_000_000_000L)); // placa VARCHAR(20): nanoTime completo no cabe
        u.setResponsable(responsable);
        return u;
    }

    private Proveedor proveedorCon(Zona zona) {
        Proveedor p = new Proveedor();
        p.setNombre("Proveedor Auth Test");
        p.setDni("DNI-" + (System.nanoTime() % 1_000_000_000L)); // dni VARCHAR(15): nanoTime completo no cabe (bug preexistente, no relacionado a este refactor)
        p.setZonaActual(zona);
        return p;
    }

    private RegistroAcopio registroCon(String uuidCliente, Proveedor proveedor, Usuario acopiador, Unidad unidad) {
        RegistroAcopio r = new RegistroAcopio();
        r.setUuidCliente(uuidCliente);
        r.setProveedor(proveedor);
        r.setAcopiador(acopiador);
        r.setUnidad(unidad);
        r.setFechaHora(LocalDateTime.now());
        r.setLitros(BigDecimal.valueOf(50));
        return r;
    }

    private Producto productoCon() {
        Familia familia = new Familia();
        familia.setNombre("Queso Auth Test " + System.nanoTime());
        Producto t = new Producto();
        t.setNombre("Queso Auth Test " + System.nanoTime());
        t.setFamilia(familiaRepository.save(familia));
        t.setRendimientoEsperadoPct(BigDecimal.valueOf(10));
        return t;
    }
}
