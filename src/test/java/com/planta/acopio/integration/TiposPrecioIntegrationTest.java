package com.planta.acopio.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.dto.AjusteInventarioRequest;
import com.planta.acopio.dto.CrearVentaCarritoRequest;
import com.planta.acopio.dto.DetalleVentaRequest;
import com.planta.acopio.dto.PrecioQuesoRequest;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.AjusteInventarioService;
import com.planta.acopio.service.PrecioQuesoService;
import com.planta.acopio.service.VentaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tipos de precio administrables (V37) contra PostgreSQL real: los tres tipos existen activos; un ADMIN desactiva uno con motivo y el servidor
 * rechaza precios y ventas NUEVOS de ese tipo, mientras lo registrado antes sigue consultable; al reactivarlo vuelve a aceptarse. PUBLICO no se
 * puede desactivar, repetir el estado es 409, el motivo es obligatorio y solo ADMIN cambia el estado.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TiposPrecioIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private VentaService ventaService;
    @Autowired private PrecioQuesoService precioQuesoService;
    @Autowired private AjusteInventarioService ajusteInventarioService;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final LocalDate hoy = LocalDate.now(ZoneId.of("America/Lima"));

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void desactivarUnTipoBloqueaLoNuevoYConservaLoHistoricoYReactivarLoDevuelve() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        Presentacion p = presentacionConStock(20);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null));
        precioQuesoService.crear(new PrecioQuesoRequest(p.getId(), "PROVEEDOR", new BigDecimal("18.00"), hoy.minusDays(10)));
        SecurityContextHolder.clearContext();

        assertThat(codigos(listar(admin, false))).containsExactly("MAYORISTA", "PROVEEDOR", "PUBLICO");
        assertThat(codigos(listar(admin, true))).containsExactly("MAYORISTA", "PROVEEDOR", "PUBLICO");

        JsonNode desactivado = cambiar(admin, "PROVEEDOR", false, "Ya no vendemos a proveedores", 200);
        assertThat(desactivado.get("activo").asBoolean()).isFalse();
        assertThat(desactivado.get("ultimoCambio").get("motivo").asText()).isEqualTo("Ya no vendemos a proveedores");
        assertThat(desactivado.get("ultimoCambio").get("usuarioNombre").asText()).isNotBlank();

        // los selectores solo ven los activos; la lista completa conserva el desactivado con su estado
        assertThat(codigos(listar(admin, true))).containsExactly("MAYORISTA", "PUBLICO");
        assertThat(codigos(listar(admin, false))).containsExactly("MAYORISTA", "PROVEEDOR", "PUBLICO");

        // lo nuevo se rechaza en el servidor
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null));
        assertThatThrownBy(() -> precioQuesoService.crear(new PrecioQuesoRequest(p.getId(), "PROVEEDOR", new BigDecimal("19.00"), hoy)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("desactivado");
        assertThatThrownBy(() -> ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(UUID.randomUUID().toString(), hoy,
                "PROVEEDOR", List.of(new DetalleVentaRequest(p.getId(), 1)))))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("desactivado");
        // lo historico sigue ahi, con su tipo
        assertThat(precioQuesoService.listarPorPresentacion(p.getId())).anyMatch(x -> x.modalidad().equals("PROVEEDOR") && x.precio().compareTo(new BigDecimal("18.00")) == 0);
        // los otros tipos siguen vendiendo
        ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(UUID.randomUUID().toString(), hoy, "PUBLICO",
                List.of(new DetalleVentaRequest(p.getId(), 1))));
        SecurityContextHolder.clearContext();

        // reactivar: vuelve a aceptarse, y no se cambio ningun precio ni venta existente
        cambiar(admin, "PROVEEDOR", true, "Se retoma la venta a proveedores", 200);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null));
        precioQuesoService.crear(new PrecioQuesoRequest(p.getId(), "PROVEEDOR", new BigDecimal("19.00"), hoy));
        SecurityContextHolder.clearContext();
        assertThat(jdbc.queryForObject("select count(*) from cambio_tipo_precio where codigo = 'PROVEEDOR'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from precio_presentacion where presentacion_id = ? and modalidad = 'PROVEEDOR'", Integer.class, p.getId())).isEqualTo(2);
    }

    @Test
    void protegeAPublicoExigeMotivoRechazaRepetirYSoloAdminCambia() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        String ventas = tokenDeRol("VENTAS");

        cambiar(admin, "PUBLICO", false, "Intento", 400);                  // el catalogo publico y las reservas dependen de PUBLICO
        cambiar(admin, "MAYORISTA", true, "Ya estaba activo", 409);        // repetir el estado
        cambiar(admin, "MAYORISTA", false, "   ", 400);                    // motivo obligatorio
        cambiar(admin, "INEXISTENTE", false, "No existe", 404);
        cambiar(ventas, "MAYORISTA", false, "No es admin", 403);
        mvc.perform(get("/api/tipos-precio?soloActivos=true").header("Authorization", ventas)).andExpect(status().isOk());   // VENTAS si consulta
        assertThat(codigos(listar(admin, false))).containsExactly("MAYORISTA", "PROVEEDOR", "PUBLICO");
        assertThat(jdbc.queryForObject("select count(*) from tipo_precio where activo", Integer.class)).isEqualTo(3);
    }

    private List<String> codigos(JsonNode lista) {
        return lista.findValuesAsText("codigo");
    }

    private JsonNode listar(String token, boolean soloActivos) throws Exception {
        return json.readTree(mvc.perform(get("/api/tipos-precio?soloActivos=" + soloActivos).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode cambiar(String token, String codigo, boolean activo, String motivo, int esperado) throws Exception {
        MvcResult r = mvc.perform(put("/api/tipos-precio/" + codigo + "/estado").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"activo\":" + activo + ",\"motivo\":\"" + motivo + "\"}")).andExpect(status().is(esperado)).andReturn();
        return esperado == 200 ? json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)) : null;
    }

    private Presentacion presentacionConStock(int stock) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null));
        String s = String.valueOf(System.nanoTime());
        Familia f = new Familia();
        f.setNombre("Familia tipos " + s);
        Producto prod = new Producto();
        prod.setNombre("Producto tipos " + s);
        prod.setFamilia(familiaRepository.save(f));
        prod = productoRepository.save(prod);
        Presentacion pr = new Presentacion();
        pr.setNombre("Unidad");
        pr.setProducto(prod);
        pr.setUnidadConteo("unidad");
        pr = presentacionRepository.save(pr);
        precioQuesoService.crear(new PrecioQuesoRequest(pr.getId(), "PUBLICO", new BigDecimal("20.00"), LocalDate.of(2000, 1, 1)));
        ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), stock, "Stock inicial de la prueba"));
        SecurityContextHolder.clearContext();
        return pr;
    }

    private String token(String email, String password) throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + json.readTree(login.getResponse().getContentAsString()).get("token").asText();
    }

    private String tokenDeRol(String rolNombre) throws Exception {
        Rol rol = rolRepository.findByNombre(rolNombre).orElseThrow();
        Usuario u = new Usuario();
        u.setNombre("Usuario " + rolNombre);
        u.setEmail(rolNombre.toLowerCase() + UUID.randomUUID().toString().substring(0, 6) + "@planta.com");
        u.setPasswordHash(passwordEncoder.encode("Clave1234"));
        u.setRol(rol);
        u.setActivo(true);
        usuarioRepository.save(u);
        return token(u.getEmail(), "Clave1234");
    }
}
