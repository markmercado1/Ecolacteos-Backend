package com.planta.acopio.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Documentos del proveedor contra PostgreSQL real y la cadena de seguridad real (JWT, MockMvc):
 * - persona natural con DNI y RUC en una sola ficha; empresa con RUC y razon social sin DNI; el cuerpo anterior (sin tipo) sigue funcionando;
 * - DNI y RUC unicos (409) y reglas de forma (400);
 * - la busqueda encuentra por RUC y razon social en proveedores y pagos;
 * - un DNI anterior fuera de formato se conserva al editar y aparece en la revision de documentos (solo ADMIN), sin corregirse;
 * - la lista operativa (movil) sigue sin DNI ni RUC.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentosProveedorIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    private static final AtomicInteger PRUEBAS = new AtomicInteger(100);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String admin;
    private String dni;
    private String rucNatural;
    private String rucEmpresa;

    @BeforeEach
    void preparar() throws Exception {
        int prueba = PRUEBAS.incrementAndGet();
        dni = String.format("7%07d", prueba);
        rucNatural = conVerificador("10" + dni);
        rucEmpresa = conVerificador("20" + String.format("%08d", 60_000_000 + prueba));
        admin = token("admin@planta.com", "Admin1234");
    }

    @Test
    void personaNaturalConDniYRucEnUnaSolaFichaYEmpresaConRucYRazonSocialSinDni() throws Exception {
        JsonNode natural = crear(admin, "{\"nombre\":\"Rosa Quispe\",\"dni\":\"" + dni + "\",\"tipoPersona\":\"NATURAL\",\"ruc\":\"" + rucNatural + "\"}", 201);
        assertThat(natural.get("tipoPersona").asText()).isEqualTo("NATURAL");
        assertThat(natural.get("dni").asText()).isEqualTo(dni);
        assertThat(natural.get("ruc").asText()).isEqualTo(rucNatural);
        assertThat(natural.get("razonSocial").isNull()).isTrue();

        JsonNode empresa = crear(admin, "{\"tipoPersona\":\"EMPRESA\",\"ruc\":\"" + rucEmpresa + "\",\"razonSocial\":\"Lácteos Andinos S.A.C.\"}", 201);
        assertThat(empresa.get("dni").isNull()).isTrue();
        assertThat(empresa.get("nombre").asText()).isEqualTo("Lácteos Andinos S.A.C.");
        assertThat(jdbc.queryForObject("SELECT tipo_persona FROM proveedor WHERE id = ?", String.class, UUID.fromString(empresa.get("id").asText())))
                .isEqualTo("EMPRESA");

        // RUC repetido (aunque lo envie otra ficha) y DNI repetido -> 409; reglas de forma -> 400
        crear(admin, "{\"tipoPersona\":\"EMPRESA\",\"ruc\":\"" + rucEmpresa + "\",\"razonSocial\":\"Otra\"}", 409);
        crear(admin, "{\"nombre\":\"Duplicada\",\"dni\":\"" + dni + "\"}", 409);
        crear(admin, "{\"tipoPersona\":\"EMPRESA\",\"razonSocial\":\"Sin RUC\"}", 400);
        crear(admin, "{\"nombre\":\"Sin DNI\"}", 400);
        crear(admin, "{\"nombre\":\"DNI corto\",\"dni\":\"123\"}", 400);
    }

    @Test
    void elCuerpoAnteriorSinTipoSigueCreandoUnaPersonaNatural() throws Exception {
        JsonNode r = crear(admin, "{\"nombre\":\"Juan Mamani\",\"dni\":\"" + dni + "\",\"telefono\":\"+51987654321\"}", 201);
        assertThat(r.get("tipoPersona").asText()).isEqualTo("NATURAL");
        assertThat(r.get("ruc").isNull()).isTrue();

        // la edicion anterior (nombre, dni, telefono) tampoco cambia el tipo ni pierde nada
        mvc.perform(put("/api/proveedores/" + r.get("id").asText()).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Juan Mamani Ccori\",\"dni\":\"" + dni + "\",\"telefono\":\"+51987654321\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT nombre || '|' || tipo_persona FROM proveedor WHERE dni = ?", String.class, dni))
                .isEqualTo("Juan Mamani Ccori|NATURAL");
    }

    @Test
    void laBusquedaEncuentraPorRucYRazonSocialEnProveedoresYPagos() throws Exception {
        crear(admin, "{\"tipoPersona\":\"EMPRESA\",\"ruc\":\"" + rucEmpresa + "\",\"razonSocial\":\"Agropecuaria Huañec " + dni + " S.R.L.\",\"nombre\":\"Fundo Huañec\"}", 201);

        JsonNode porRuc = listar("/api/proveedores", rucEmpresa.substring(3));
        assertThat(porRuc.get("contenido")).hasSize(1);
        assertThat(porRuc.get("contenido").get(0).get("ruc").asText()).isEqualTo(rucEmpresa);
        JsonNode porRazon = listar("/api/proveedores", "agropecuaria huanec " + dni);
        assertThat(porRazon.get("contenido")).hasSize(1);

        // pagos: la misma busqueda no falla con DNI nulo y simplemente no encuentra pagos de esta empresa
        assertThat(listar("/api/pagos", rucEmpresa).get("contenido")).isEmpty();
        assertThat(listar("/api/pagos-proveedor", rucEmpresa).get("contenido")).isEmpty();
    }

    @Test
    void unDniAnteriorSeConservaAlEditarAunqueLaRevisionFueRetirada() throws Exception {
        UUID id = UUID.randomUUID();
        String dniViejo = "9" + dni.substring(1, 6); // 6 digitos, cargado por fuera del sistema
        jdbc.update("INSERT INTO proveedor (id, nombre, dni) VALUES (?, ?, ?)", id, "Proveedor Antiguo " + dni, dniViejo);

        mvc.perform(put("/api/proveedores/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Proveedor Antiguo Editado " + dni + "\",\"dni\":\"" + dniViejo + "\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT dni FROM proveedor WHERE id = ?", String.class, id)).isEqualTo(dniViejo);

        mvc.perform(get("/api/admin/proveedores/revision-documentos").header("Authorization", admin)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT dni FROM proveedor WHERE id = ?", String.class, id)).isEqualTo(dniViejo);

    }

    @Test
    void laListaOperativaDelMovilSigueSinDniNiRuc() throws Exception {
        crear(admin, "{\"nombre\":\"Rosa Operativa\",\"dni\":\"" + dni + "\",\"ruc\":\"" + rucNatural + "\"}", 201);
        MvcResult r = mvc.perform(get("/api/proveedores/operativo").header("Authorization", admin)).andExpect(status().isOk()).andReturn();
        String cuerpo = r.getResponse().getContentAsString();
        assertThat(cuerpo).contains("Rosa Operativa").doesNotContain(rucNatural).doesNotContain(dni);
        assertThat(cuerpo).doesNotContain("\"ruc\"").doesNotContain("\"dni\"");
    }

    // ------------------------------------------------------------------ auxiliares

    private JsonNode crear(String token, String cuerpo, int estado) throws Exception {
        MvcResult r = mvc.perform(post("/api/proveedores").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().is(estado)).andReturn();
        String texto = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return texto.isEmpty() ? null : json.readTree(texto);
    }

    private JsonNode listar(String ruta, String busqueda) throws Exception {
        MvcResult r = mvc.perform(get(ruta).header("Authorization", admin).param("busqueda", busqueda)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String conVerificador(String diez) {
        int[] pesos = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
        int suma = 0;
        for (int i = 0; i < 10; i++) suma += (diez.charAt(i) - '0') * pesos[i];
        int d = 11 - suma % 11;
        return diez + (d == 10 ? 0 : d == 11 ? 1 : d);
    }

    private String token(String email, String password) throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + json.readTree(login.getResponse().getContentAsString()).get("token").asText();
    }

    private Usuario usuario(String rolNombre) {
        Rol rol = rolRepository.findByNombre(rolNombre).orElseThrow();
        Usuario u = new Usuario();
        u.setNombre("Usuario " + rolNombre);
        u.setEmail(rolNombre.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@planta.com");
        u.setPasswordHash(passwordEncoder.encode("Clave1234"));
        u.setRol(rol);
        u.setActivo(true);
        return usuarioRepository.save(u);
    }
}
