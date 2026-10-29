package com.planta.acopio.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.dto.InsumoRequest;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.InsumoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proveedores de compra (V42) por HTTP con la cadena real de seguridad y PostgreSQL real: roles (PRODUCCION consulta y crea
 * rapido; solo ADMIN edita y da de baja), RUC obligatorio, de 11 digitos con verificador valido y unico, categorias
 * flexibles muchos-a-muchos, categoria por defecto e insumos habituales.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProveedorCompraIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    // RUC con digito verificador valido.
    private static final String RUC_1 = "20100070970";
    private static final String RUC_2 = "20600000013";
    private static final String RUC_3 = "20500000024";
    private static final String RUC_4 = "20700000037";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private InsumoService insumoService;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;

    private final RequestPostProcessor admin = user("admin@planta.com").roles("ADMIN");
    private final RequestPostProcessor produccion = user("prod@planta.com").roles("PRODUCCION");
    private final RequestPostProcessor ventas = user("ventas@planta.com").roles("VENTAS");

    @BeforeEach
    void autenticarParaElServicioDeInsumos() {
        // InsumoService.crear escribe el saldo inicial con el usuario autenticado; aqui basta con que exista uno.
        String s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("PROV_COMPRA_" + s);
        rol = rolRepository.save(rol);
        Usuario u = new Usuario();
        u.setNombre("Prov Compra " + s);
        u.setEmail("prov-" + s + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        usuarioRepository.save(u);
        SecurityContextHolder.getContext().setAuthentication(new UsuarioPrincipal(u.getEmail()));
    }

    private static final class UsuarioPrincipal extends UsernamePasswordAuthenticationToken {
        UsuarioPrincipal(String email) { super(email, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))); }
    }

    private JsonNode ok(org.springframework.test.web.servlet.ResultActions r, int estado) throws Exception {
        r.andExpect(status().is(estado));
        String cuerpo = r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return cuerpo.isBlank() ? json.createObjectNode() : json.readTree(cuerpo);
    }

    private JsonNode crear(RequestPostProcessor quien, String cuerpo, int estado) throws Exception {
        return ok(mvc.perform(post("/api/proveedores-compra").with(quien).contentType(MediaType.APPLICATION_JSON).content(cuerpo)), estado);
    }

    @Test
    void produccionHaceAltaRapidaConNombreYRucYQuedaEnOtrosInsumos() throws Exception {
        JsonNode p = crear(produccion, "{\"nombre\":\"  Distribuidora   Andina SAC \",\"ruc\":\"" + RUC_1 + "\"}", 201);

        assertThat(p.get("nombre").asText()).isEqualTo("Distribuidora Andina SAC");
        assertThat(p.get("activo").asBoolean()).isTrue();
        assertThat(p.get("categorias")).hasSize(1);
        assertThat(p.get("categorias").get(0).get("codigo").asText()).isEqualTo("OTROS_INSUMOS");
    }

    @Test
    void elRucDebeTener11DigitosUnVerificadorValidoYSerUnico() throws Exception {
        assertThat(crear(admin, "{\"nombre\":\"A\",\"ruc\":\"1234567\"}", 400).get("mensaje").asText()).contains("11 dígitos");
        assertThat(crear(admin, "{\"nombre\":\"A\",\"ruc\":\"20100070971\"}", 400).get("mensaje").asText()).contains("dígito verificador");
        assertThat(crear(admin, "{\"nombre\":\"\",\"ruc\":\"" + RUC_2 + "\"}", 400)).isNotNull();

        crear(admin, "{\"nombre\":\"Lacteos del Sur\",\"ruc\":\"" + RUC_2 + "\"}", 201);
        // Mismo RUC, con separadores: se reconoce y se rechaza nombrando al existente.
        JsonNode repetido = crear(produccion, "{\"nombre\":\"Otro\",\"ruc\":\"2060 0000-013\"}", 409);
        assertThat(repetido.get("mensaje").asText()).contains("Lacteos del Sur");
    }

    @Test
    void unProveedorPuedeEstarEnLecheYOtrosInsumosYTieneInsumosHabituales() throws Exception {
        UUID azucar = insumoService.crear(new InsumoRequest("Azucar prov " + System.nanoTime(), "kg", BigDecimal.ZERO)).id();
        JsonNode p = crear(admin, "{\"nombre\":\"Mixto SA\",\"ruc\":\"" + RUC_3 + "\",\"telefono\":\"999111222\",\"contacto\":\"Rosa\","
                + "\"categorias\":[\"LECHE\",\"OTROS_INSUMOS\"],\"insumosHabituales\":[\"" + azucar + "\"]}", 201);

        assertThat(p.get("categorias")).extracting(c -> c.get("codigo").asText()).containsExactlyInAnyOrder("LECHE", "OTROS_INSUMOS");
        assertThat(p.get("insumosHabituales")).hasSize(1);
        assertThat(p.get("telefono").asText()).isEqualTo("999111222");

        // Cambiar de categoria con un clic (PUT con la lista nueva) y quitar los insumos habituales.
        String id = p.get("id").asText();
        JsonNode editado = ok(mvc.perform(put("/api/proveedores-compra/" + id).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"Mixto SA\",\"ruc\":\"" + RUC_3 + "\",\"categorias\":[\"LECHE\"],\"insumosHabituales\":[]}")), 200);
        assertThat(editado.get("categorias")).hasSize(1);
        assertThat(editado.get("insumosHabituales")).isEmpty();
        // Categoria inexistente o vacia al editar: rechazo.
        ok(mvc.perform(put("/api/proveedores-compra/" + id).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"Mixto SA\",\"ruc\":\"" + RUC_3 + "\",\"categorias\":[\"NOEXISTE\"]}")), 400);
        ok(mvc.perform(put("/api/proveedores-compra/" + id).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"Mixto SA\",\"ruc\":\"" + RUC_3 + "\",\"categorias\":[]}")), 400);
    }

    @Test
    void soloAdminEditaYDaDeBajaYElSelectorSoloMuestraActivosConBusquedaSinTildes() throws Exception {
        JsonNode p = crear(admin, "{\"nombre\":\"Azúcar Norteña SAC\",\"ruc\":\"" + RUC_4 + "\"}", 201);
        String id = p.get("id").asText();

        // PRODUCCION: consulta, pero no edita ni desactiva; VENTAS no entra.
        ok(mvc.perform(put("/api/proveedores-compra/" + id).with(produccion).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"X\",\"ruc\":\"" + RUC_4 + "\"}")), 403);
        ok(mvc.perform(patch("/api/proveedores-compra/" + id + "/desactivar").with(produccion)), 403);
        ok(mvc.perform(get("/api/proveedores-compra").with(ventas)), 403);
        assertThat(ok(mvc.perform(get("/api/proveedores-compra").param("busqueda", "AZUCAR norte").with(produccion)), 200))
                .extracting(n -> n.get("id").asText()).contains(id);
        assertThat(ok(mvc.perform(get("/api/proveedores-compra").param("busqueda", RUC_4.substring(2, 8)).with(produccion)), 200)).hasSize(1);

        ok(mvc.perform(patch("/api/proveedores-compra/" + id + "/desactivar").with(admin)), 200);
        assertThat(ok(mvc.perform(get("/api/proveedores-compra").param("busqueda", "norte").with(produccion)), 200)).isEmpty();
        assertThat(ok(mvc.perform(get("/api/proveedores-compra").param("busqueda", "norte").param("soloActivos", "false").with(admin)), 200)).hasSize(1);
        // Un RUC de un proveedor desactivado sigue ocupado y el mensaje lo dice.
        assertThat(crear(produccion, "{\"nombre\":\"Copia\",\"ruc\":\"" + RUC_4 + "\"}", 409).get("mensaje").asText()).contains("desactivado");

        ok(mvc.perform(patch("/api/proveedores-compra/" + id + "/reactivar").with(admin)), 200);
        assertThat(ok(mvc.perform(get("/api/proveedores-compra").param("busqueda", "norte").with(produccion)), 200)).hasSize(1);
        assertThat(ok(mvc.perform(get("/api/proveedores-compra/categorias").with(produccion)), 200))
                .extracting(n -> n.get("codigo").asText()).contains("LECHE", "OTROS_INSUMOS");
    }
}
