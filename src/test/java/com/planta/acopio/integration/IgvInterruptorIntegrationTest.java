package com.planta.acopio.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.dto.AjusteInventarioRequest;
import com.planta.acopio.dto.CrearVentaCarritoRequest;
import com.planta.acopio.dto.DetalleVentaRequest;
import com.planta.acopio.dto.PrecioQuesoRequest;
import com.planta.acopio.dto.VentaResumenResponse;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Usuario;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IGV activable desde el panel (V35) contra PostgreSQL real: el calculo parte apagado (valor por defecto); un ADMIN lo enciende con motivo y las
 * ventas NUEVAS desglosan el IGV dentro del precio sin cambiar el total; lo apaga y las nuevas vuelven a no desglosar, mientras las ya registradas
 * conservan exactamente lo que se registro. Cada cambio queda con su motivo; solo ADMIN decide; y la clasificacion en lote completa lo que falta.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IgvInterruptorIntegrationTest {

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

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private final LocalDate hoy = LocalDate.now(ZoneId.of("America/Lima"));

    @Test
    void elAdministradorEnciendeYApagaElCalculoYSoloCambianLasVentasNuevas() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        Presentacion p = presentacionConStock("23.60", 20);
        mvc.perform(post("/api/admin/tributario/tasas-igv").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tasa\":18,\"vigenteDesde\":\"" + hoy.minusDays(5) + "\",\"motivo\":\"Tasa general\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/admin/tributario/presentaciones/" + p.getId() + "/afectaciones").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"afectacion\":\"GRAVADO\",\"vigenteDesde\":\"" + hoy.minusDays(5) + "\",\"motivo\":\"Gravado\"}")).andExpect(status().isCreated());

        // por defecto: apagado
        assertThat(estado(admin).get("estado").asText()).isEqualTo("PENDIENTE");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null));
        VentaResumenResponse antes = vender(p);
        assertThat(antes.total()).isEqualByComparingTo("47.20");
        assertThat(antes.detalles().get(0).igv()).isNull();
        SecurityContextHolder.clearContext();

        // el ADMIN lo enciende, con motivo
        JsonNode encendido = cambiar(admin, true, "El administrador decidió desglosar el IGV", 200);
        assertThat(encendido.get("estado").asText()).isEqualTo("ACTIVO");
        assertThat(encendido.get("ultimoCambio").get("motivo").asText()).isEqualTo("El administrador decidió desglosar el IGV");
        assertThat(encendido.get("ultimoCambio").get("usuarioNombre").asText()).isNotBlank();

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null));
        VentaResumenResponse durante = vender(p);
        assertThat(durante.total()).isEqualByComparingTo("47.20"); // el precio NO cambia: el IGV va dentro
        assertThat(durante.detalles().get(0).igv()).isEqualByComparingTo("7.20");          // 47.20 con 18 % incluido
        assertThat(durante.detalles().get(0).valorVenta()).isEqualByComparingTo("40.00");
        assertThat(durante.igv().incluyeIgv()).isTrue();
        SecurityContextHolder.clearContext();

        // lo apaga: las nuevas vuelven a no desglosar; las ya registradas conservan lo que se registro (ni se recalculan ni se borran)
        JsonNode apagado = cambiar(admin, false, "Se apaga por revisión del contador", 200);
        assertThat(apagado.get("estado").asText()).isEqualTo("PENDIENTE");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null));
        VentaResumenResponse despues = vender(p);
        SecurityContextHolder.clearContext();
        assertThat(despues.detalles().get(0).igv()).isNull();
        assertThat(igvGuardado(antes.id())).isNull();
        assertThat(igvGuardado(durante.id())).isEqualByComparingTo("7.20");
        assertThat(igvGuardado(despues.id())).isNull();

        // cada cambio quedo registrado (append-only): el ultimo manda
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cambio_calculo_igv", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT habilitado FROM cambio_calculo_igv ORDER BY creado_en DESC, id DESC LIMIT 1", Boolean.class)).isFalse();
    }

    @Test
    void soloUnAdminCambiaElInterruptorSiempreConMotivoYNoSeRepiteElMismoEstado() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        for (String rol : List.of("VENTAS", "RECEPCION", "CALIDAD", "PRODUCCION", "ACOPIADOR")) {
            mvc.perform(put("/api/admin/tributario/calculo").header("Authorization", tokenDeRol(rol)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"habilitado\":true,\"motivo\":\"No deberia poder\"}")).andExpect(status().isForbidden());
        }
        cambiar(admin, true, "   ", 400);                       // sin motivo
        mvc.perform(put("/api/admin/tributario/calculo").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"Falta el campo\"}")).andExpect(status().isBadRequest());
        cambiar(admin, false, "Ya estaba apagado", 409);        // ya estaba en ese estado
        cambiar(admin, true, "Encender", 200);
        cambiar(admin, true, "Encender otra vez", 409);
        cambiar(admin, false, "Apagar", 200);
    }

    @Test
    void laClasificacionEnLoteCompletaLoQueFaltaSinPisarLoYaDecidido() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        Presentacion libre = presentacionConStock("10.00", 1);
        Presentacion exonerada = presentacionConStock("10.00", 1);
        mvc.perform(post("/api/admin/tributario/presentaciones/" + exonerada.getId() + "/afectaciones").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"afectacion\":\"EXONERADO\",\"vigenteDesde\":\"" + hoy.minusDays(3) + "\",\"motivo\":\"Decidido antes\"}")).andExpect(status().isCreated());

        JsonNode r = json.readTree(mvc.perform(post("/api/admin/tributario/afectaciones/lote").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"afectacion\":\"GRAVADO\",\"vigenteDesde\":\"" + hoy + "\",\"motivo\":\"Todos los productos están gravados\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(r.get("aplicadas").asInt()).isGreaterThanOrEqualTo(1);

        assertThat(afectacionVigente(libre.getId())).isEqualTo("GRAVADO");
        assertThat(afectacionVigente(exonerada.getId())).isEqualTo("EXONERADO"); // no se pisa una decision ya tomada
        // repetirlo no duplica nada: todo lo que quedaba ya esta clasificado
        JsonNode otra = json.readTree(mvc.perform(post("/api/admin/tributario/afectaciones/lote").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"afectacion\":\"GRAVADO\",\"vigenteDesde\":\"" + hoy + "\",\"motivo\":\"Otra vez\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(otra.get("aplicadas").asInt()).isZero();

        // validaciones y permisos
        mvc.perform(post("/api/admin/tributario/afectaciones/lote").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"afectacion\":\"SIN_DEFINIR\",\"vigenteDesde\":\"" + hoy + "\",\"motivo\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/tributario/afectaciones/lote").header("Authorization", tokenDeRol("VENTAS")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"afectacion\":\"GRAVADO\",\"vigenteDesde\":\"" + hoy + "\",\"motivo\":\"x\"}")).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ ayudas

    private String afectacionVigente(UUID presentacionId) {
        return jdbc.queryForObject("SELECT afectacion FROM afectacion_igv_presentacion WHERE presentacion_id = ? AND vigente_desde <= ? ORDER BY vigente_desde DESC LIMIT 1",
                String.class, presentacionId, java.sql.Date.valueOf(hoy));
    }

    private BigDecimal igvGuardado(UUID ventaId) {
        return jdbc.queryForObject("SELECT igv FROM venta_detalle WHERE venta_id = ? LIMIT 1", BigDecimal.class, ventaId);
    }

    private VentaResumenResponse vender(Presentacion p) {
        return ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(UUID.randomUUID().toString(), hoy, "PUBLICO",
                List.of(new DetalleVentaRequest(p.getId(), 2))));
    }

    private JsonNode estado(String token) throws Exception {
        return json.readTree(mvc.perform(get("/api/admin/tributario/estado").header("Authorization", token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode cambiar(String token, boolean habilitado, String motivo, int esperado) throws Exception {
        MvcResult r = mvc.perform(put("/api/admin/tributario/calculo").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"habilitado\":" + habilitado + ",\"motivo\":\"" + motivo + "\"}")).andExpect(status().is(esperado)).andReturn();
        return esperado == 200 ? json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)) : null;
    }

    private Presentacion presentacionConStock(String precio, int stock) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null));
        String s = String.valueOf(System.nanoTime());
        Familia f = new Familia();
        f.setNombre("Familia IGV sw " + s);
        Producto prod = new Producto();
        prod.setNombre("Producto IGV sw " + s);
        prod.setFamilia(familiaRepository.save(f));
        prod = productoRepository.save(prod);
        Presentacion pr = new Presentacion();
        pr.setNombre("Unidad");
        pr.setProducto(prod);
        pr.setUnidadConteo("unidad");
        pr = presentacionRepository.save(pr);
        precioQuesoService.crear(new PrecioQuesoRequest(pr.getId(), "PUBLICO", new BigDecimal(precio), LocalDate.of(2000, 1, 1)));
        if (stock > 0) ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), stock, "Stock inicial de la prueba"));
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
