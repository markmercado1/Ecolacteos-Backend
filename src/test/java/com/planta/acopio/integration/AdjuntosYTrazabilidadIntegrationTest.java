package com.planta.acopio.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.dto.DocumentoIngresoResponse;
import com.planta.acopio.dto.IngresoDocumentoRequest;
import com.planta.acopio.dto.InsumoRequest;
import com.planta.acopio.dto.ProveedorCompraRequest;
import com.planta.acopio.dto.SalidaInventarioRequest;
import com.planta.acopio.dto.TrazabilidadResponse;
import com.planta.acopio.entity.MotivoSalida;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.TipoDocumentoIngreso;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.IngresoInsumoService;
import com.planta.acopio.service.InsumoService;
import com.planta.acopio.service.ProveedorCompraService;
import com.planta.acopio.service.SalidaInventarioService;
import com.planta.acopio.service.TrazabilidadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adjuntos del recibo (V46) y busqueda de trazabilidad por codigo de lote contra PostgreSQL real y la cadena de seguridad real: el tipo se
 * valida por el CONTENIDO, 10 MB maximo, el mismo archivo no se adjunta dos veces, el almacenamiento esta en una carpeta configurable
 * fuera del codigo, y la trazabilidad une lote -> proveedor -> documento -> recibo -> movimientos -> usos.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdjuntosYTrazabilidadIntegrationTest {

    private static Path carpeta;

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry registry) throws IOException {
        PostgresDePrueba.registrar(registry);
        carpeta = Files.createTempDirectory("adjuntos-it");
        registry.add("app.adjuntos.directorio", () -> carpeta.toString());
    }

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 2, 3, 4, 5};
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private InsumoService insumoService;
    @Autowired private IngresoInsumoService ingresoService;
    @Autowired private ProveedorCompraService proveedorService;
    @Autowired private SalidaInventarioService salidaService;
    @Autowired private TrazabilidadService trazabilidad;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private JdbcTemplate jdbc;

    private String s;
    private String email;
    private UUID azucar;
    private DocumentoIngresoResponse doc;

    @BeforeEach
    void seed() {
        s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("ADJ_IT_" + s);
        rol = rolRepository.save(rol);
        Usuario u = new Usuario();
        u.setNombre("Adjuntos Test " + s);
        u.setEmail("adj-" + s + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        usuarioRepository.save(u);
        email = u.getEmail();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(email, null));
        azucar = insumoService.crear(new InsumoRequest("Azucar adjuntos " + s, "kg", new BigDecimal("5"))).id();
        UUID proveedor = proveedorService.crear(new ProveedorCompraRequest("Distribuidora Adjuntos " + s, rucLibre())).id();
        doc = ingresoService.registrar(new IngresoDocumentoRequest(null, proveedor, TipoDocumentoIngreso.FACTURA, "F777-" + s, LocalDate.now(), null,
                List.of(new IngresoDocumentoRequest.Linea(azucar, new BigDecimal("40"), "LOTE-T-" + s, "PROV-T-" + s, LocalDate.now().plusDays(45)))));
    }

    private String rucLibre() {
        for (String r : new String[]{"20100070970", "20600000013", "20500000024", "20700000037", "20800000042"}) {
            if (jdbc.queryForObject("SELECT count(*) FROM proveedor_compra WHERE ruc = ?", Integer.class, r) == 0) return r;
        }
        return jdbc.queryForObject("SELECT ruc FROM proveedor_compra LIMIT 1", String.class);
    }

    private String subir(String rol, String nombre, String tipo, byte[] bytes, int estado) throws Exception {
        var r = mvc.perform(multipart("/api/inventario/ingresos/" + doc.id() + "/adjuntos").file(new MockMultipartFile("file", nombre, tipo, bytes))
                .with(user(email).roles(rol))).andExpect(status().is(estado)).andReturn();
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void adjuntaUnaFotoYUnPdfYLosGuardaEnLaCarpetaConfigurableFueraDeLaBD() throws Exception {
        JsonNode foto = json.readTree(subir("PRODUCCION", "recibo-factura.jpg", "image/jpeg", JPEG, 201));
        JsonNode pdf = json.readTree(subir("ADMIN", "factura.pdf", "application/pdf", PDF, 201));

        assertThat(foto.get("contentType").asText()).isEqualTo("image/jpeg");
        assertThat(foto.get("nombreOriginal").asText()).isEqualTo("recibo-factura.jpg");
        assertThat(pdf.get("contentType").asText()).isEqualTo("application/pdf");
        // En disco, bajo la carpeta configurada, con nombre generado por el servidor; en la BD solo la referencia (y el texto OCR, nulo).
        String ruta = jdbc.queryForObject("SELECT ruta_almacenamiento FROM adjunto WHERE id = ?", String.class, UUID.fromString(foto.get("id").asText()));
        assertThat(ruta).doesNotContain("recibo-factura").endsWith(".jpg");
        assertThat(Files.readAllBytes(carpeta.resolve(ruta))).isEqualTo(JPEG);
        assertThat(jdbc.queryForObject("SELECT texto_extraido FROM adjunto WHERE id = ?", String.class, UUID.fromString(foto.get("id").asText()))).isNull();

        // Se descargan con su tipo, sin que el navegador adivine el contenido.
        mvc.perform(get("/api/adjuntos/" + foto.get("id").asText() + "/contenido").with(user(email).roles("CALIDAD")))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/jpeg")).andExpect(header().string("X-Content-Type-Options", "nosniff"));
        assertThat(mvc.perform(get("/api/inventario/ingresos/" + doc.id() + "/adjuntos").with(user(email).roles("PRODUCCION"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).contains("recibo-factura.jpg", "factura.pdf");
    }

    @Test
    void validaElTipoPorElContenidoElTamanoYLosDuplicados() throws Exception {
        // Un ejecutable con extension y tipo de imagen se rechaza: manda el contenido, no lo que declare el cliente.
        subir("PRODUCCION", "virus.jpg", "image/jpeg", "MZ\u0090\u0000 programa".getBytes(StandardCharsets.ISO_8859_1), 400);
        subir("PRODUCCION", "vacio.png", "image/png", new byte[0], 400);
        // Mas de 10 MB.
        byte[] grande = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy(PDF, 0, grande, 0, PDF.length);
        subir("PRODUCCION", "grande.pdf", "application/pdf", grande, 400);
        // El mismo archivo no se adjunta dos veces al mismo documento.
        subir("PRODUCCION", "a.pdf", "application/pdf", PDF, 201);
        subir("PRODUCCION", "otra-copia.pdf", "application/pdf", PDF, 409);
        // Documento inexistente.
        mvc.perform(multipart("/api/inventario/ingresos/" + UUID.randomUUID() + "/adjuntos").file(new MockMultipartFile("file", "x.pdf", "application/pdf", PDF))
                .with(user(email).roles("ADMIN"))).andExpect(status().isNotFound());
    }

    @Test
    void losRolesSeRespetan() throws Exception {
        String id = json.readTree(subir("PRODUCCION", "r.jpg", "image/jpeg", JPEG, 201)).get("id").asText();
        subir("CALIDAD", "c.jpg", "image/jpeg", JPEG, 403);        // CALIDAD consulta, no adjunta
        subir("VENTAS", "v.jpg", "image/jpeg", JPEG, 403);
        mvc.perform(get("/api/adjuntos/" + id + "/contenido").with(user(email).roles("VENTAS"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/inventario/trazabilidad/lotes").param("q", "LOTE").with(user(email).roles("VENTAS"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/inventario/trazabilidad/lotes").param("q", "LOTE").with(user(email).roles("CALIDAD"))).andExpect(status().isOk());
    }

    @Test
    void laTrazabilidadUneElLoteConSuProveedorDocumentoReciboMovimientosYUsos() throws Exception {
        subir("PRODUCCION", "recibo.jpg", "image/jpeg", JPEG, 201);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(email, null));   // MockMvc limpia el contexto
        UUID lote = jdbc.queryForObject("SELECT id FROM lote_insumo WHERE insumo_id = ? AND codigo = ?", UUID.class, azucar, "LOTE-T-" + s);
        salidaService.registrar(azucar, true, new SalidaInventarioRequest(UUID.randomUUID(), new BigDecimal("3"), MotivoSalida.DERRAME, "se cayó", "INSUMO", lote));

        // Por el codigo del proveedor y en minusculas, parcial.
        TrazabilidadResponse r = trazabilidad.buscar("prov-t-" + s);
        assertThat(r.lotesInsumo()).hasSize(1);
        var t = r.lotesInsumo().get(0);
        assertThat(t.lote().codigo()).isEqualTo("LOTE-T-" + s);
        assertThat(t.lote().proveedorNombre()).startsWith("Distribuidora Adjuntos");
        assertThat(t.lote().estado()).isEqualTo("VIGENTE");
        assertThat(t.documento().tipo()).isEqualTo("FACTURA");
        assertThat(t.documento().numero()).isEqualTo("F777-" + s);
        assertThat(t.adjuntos()).extracting(a -> a.nombreOriginal()).containsExactly("recibo.jpg");
        assertThat(t.movimientos()).extracting(TrazabilidadResponse.Movimiento::tipo).containsExactly("INGRESO", "SALIDA_DERRAME");
        assertThat(t.movimientos().get(1).cantidad()).isEqualByComparingTo("-3");
        assertThat(t.movimientos().get(1).motivo()).isEqualTo("se cayó");

        assertThatThrownBy(() -> trazabilidad.buscar("x")).isInstanceOf(ValidacionNegocioException.class);
        assertThat(trazabilidad.buscar("no-existe-" + s).lotesInsumo()).isEmpty();
        // Los comodines del usuario no se interpretan.
        assertThat(trazabilidad.buscar("%%").lotesInsumo()).isEmpty();
    }
}
