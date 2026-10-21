package com.planta.acopio.integration;

import com.jayway.jsonpath.JsonPath;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.repository.FamiliaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fotos de producto y presentacion contra un PostgreSQL real (Flyway aplica V20) y el sistema de archivos real:
 * alta con nombre duplicado (409 por el chequeo previo, sin 500), limites de columna (400), subida, lectura
 * PUBLICA sin token, reemplazo y baja borran el archivo viejo, una subida invalida no deja archivos huerfanos
 * y las fotos de acopio (/api/archivos) siguen protegidas. No es @Transactional: el borrado del archivo viejo
 * ocurre en afterCommit, asi que cada peticion debe confirmar de verdad.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class FotosCatalogoIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("acopio_lechero_test")
            .withUsername("acopio")
            .withPassword("acopio");

    static final Path DIRECTORIO_FOTOS = crearDirectorioTemporal();

    private static Path crearDirectorioTemporal() {
        try {
            return Files.createTempDirectory("fotos-catalogo-it");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.fotos.directorio", DIRECTORIO_FOTOS::toString);
    }

    @Autowired private MockMvc mvc;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private JdbcTemplate jdbc;

    private static byte[] png(int ancho, int alto) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static long archivosEnDisco() throws IOException {
        try (Stream<Path> s = Files.list(DIRECTORIO_FOTOS)) {
            return s.count();
        }
    }

    private static boolean existe(String fotoUrl) {
        return Files.exists(DIRECTORIO_FOTOS.resolve(fotoUrl.substring(fotoUrl.lastIndexOf('/') + 1)));
    }

    private Familia nuevaFamilia() {
        Familia f = new Familia();
        f.setNombre("Familia " + UUID.randomUUID().toString().substring(0, 8));
        return familiaRepository.save(f);
    }

    private String crearProducto(Familia familia, String nombre) throws Exception {
        MvcResult r = mvc.perform(post("/api/admin/productos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"" + nombre + "\",\"familiaId\":\"" + familia.getId() + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    private String subirFotoProducto(String productoId, byte[] bytes) throws Exception {
        MvcResult r = mvc.perform(multipart(HttpMethod.PUT, "/api/admin/productos/" + productoId + "/foto")
                        .file(new MockMultipartFile("file", "x.png", "image/png", bytes)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.fotoUrl");
    }

    @Test
    void laMigracionV20AgregaLasColumnasDeFotoSinTocarDatosExistentes() {
        Integer columnas = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE column_name = 'foto_archivo' "
                        + "AND table_name IN ('producto', 'presentacion')", Integer.class);
        assertThat(columnas).isEqualTo(2);
        // Opcionales: ninguna fila existente (ni por migrar) queda obligada a tener foto.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE column_name = 'foto_archivo' "
                + "AND table_name IN ('producto', 'presentacion') AND is_nullable = 'YES'", Integer.class)).isEqualTo(2);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void flujoCompletoDeFotoDeProductoYPresentacion() throws Exception {
        Familia familia = nuevaFamilia();
        String nombre = "Yogurt " + UUID.randomUUID().toString().substring(0, 8);
        String productoId = crearProducto(familia, nombre);

        // Nombre duplicado (aunque cambie la capitalizacion): 409 del chequeo previo, nunca 500.
        mvc.perform(post("/api/admin/productos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"" + nombre.toUpperCase() + "\",\"familiaId\":\"" + familia.getId() + "\"}"))
                .andExpect(status().isConflict());

        // Limites de columna: 400 en lugar del error de Postgres.
        mvc.perform(post("/api/admin/productos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"" + "a".repeat(61) + "\",\"familiaId\":\"" + familia.getId() + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/productos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Otro " + UUID.randomUUID() + "\",\"familiaId\":\"" + familia.getId()
                                + "\",\"rendimientoEsperadoPct\":100000}"))
                .andExpect(status().isBadRequest());

        // Subir la foto del producto y verla SIN token.
        byte[] original = png(30, 20);
        String fotoUrl = subirFotoProducto(productoId, original);
        assertThat(fotoUrl).startsWith("/api/catalogo/fotos/").endsWith(".png");
        assertThat(existe(fotoUrl)).isTrue();
        // (sin @WithMockUser efectivo: la peticion anonima va por .with(anonymous()))
        mvc.perform(get(fotoUrl).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(original));

        // Reemplazar: la foto vieja se borra del disco y deja de servirse.
        String nuevaUrl = subirFotoProducto(productoId, png(40, 25));
        assertThat(nuevaUrl).isNotEqualTo(fotoUrl);
        assertThat(existe(fotoUrl)).isFalse();
        assertThat(existe(nuevaUrl)).isTrue();
        mvc.perform(get(fotoUrl)).andExpect(status().isNotFound());

        // La lista de productos expone la URL.
        mvc.perform(get("/api/admin/productos")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + productoId + "')].fotoUrl").value(nuevaUrl));

        // Foto de presentacion, independiente de la del producto.
        MvcResult pres = mvc.perform(post("/api/admin/productos/" + productoId + "/presentaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Botella 1 L\",\"contenidoCantidad\":1,\"contenidoUnidad\":\"L\",\"unidadConteo\":\"botella\"}"))
                .andExpect(status().isCreated()).andReturn();
        String presentacionId = JsonPath.read(pres.getResponse().getContentAsString(), "$.id");
        MvcResult conFoto = mvc.perform(multipart(HttpMethod.PUT,
                        "/api/admin/productos/" + productoId + "/presentaciones/" + presentacionId + "/foto")
                        .file(new MockMultipartFile("file", "p.png", "image/png", png(10, 10))))
                .andExpect(status().isOk()).andReturn();
        String fotoPresentacion = JsonPath.read(conFoto.getResponse().getContentAsString(), "$.fotoUrl");
        assertThat(existe(fotoPresentacion)).isTrue();

        // Quitar la foto del producto borra su archivo y no toca la de la presentacion.
        mvc.perform(delete("/api/admin/productos/" + productoId + "/foto")).andExpect(status().isOk())
                .andExpect(jsonPath("$.fotoUrl").doesNotExist());
        assertThat(existe(nuevaUrl)).isFalse();
        assertThat(existe(fotoPresentacion)).isTrue();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unaSubidaInvalidaDevuelve400YNoDejaArchivosHuerfanos() throws Exception {
        String productoId = crearProducto(nuevaFamilia(), "Mantequilla " + UUID.randomUUID().toString().substring(0, 8));
        long antes = archivosEnDisco();

        mvc.perform(multipart(HttpMethod.PUT, "/api/admin/productos/" + productoId + "/foto")
                        .file(new MockMultipartFile("file", "malo.png", "image/png", "no soy una imagen".getBytes())))
                .andExpect(status().isBadRequest());

        assertThat(archivosEnDisco()).isEqualTo(antes);
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    void soloAdminPuedeSubirFotos() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, "/api/admin/productos/" + UUID.randomUUID() + "/foto")
                        .file(new MockMultipartFile("file", "x.png", "image/png", new byte[]{1})))
                .andExpect(status().isForbidden());
    }

    @Test
    void lasFotosDeAcopioSiguenProtegidasAunqueLasDelCatalogoSeanPublicas() throws Exception {
        mvc.perform(get("/api/archivos/cualquiera.jpg")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/catalogo/fotos/no-existe.png")).andExpect(status().isNotFound());
    }
}
