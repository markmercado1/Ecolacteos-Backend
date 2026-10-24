package com.planta.acopio.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.importacion.ExcelDePrueba;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exportar e importar por HTTP con la cadena real: login que emite un JWT, filtro de seguridad, multipart, JSON y cabeceras,
 * contra PostgreSQL real. (MockMvc: sin socket, porque el Tomcat embebido no siempre arranca en equipos de desarrollo con la red
 * local restringida; todo lo demas es el contexto de produccion.)
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExcelHttpIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    private static final AtomicInteger PRUEBAS = new AtomicInteger(8000);
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private RolRepository rolRepository;
    @Autowired private ProveedorRepository proveedorRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private int prueba;
    private String sufijo;

    @BeforeEach
    void preparar() {
        prueba = PRUEBAS.incrementAndGet();
        sufijo = String.valueOf(prueba);
    }

    private String token(String email, String password) throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(login.getResponse().getContentAsString()).get("token").asText();
    }

    private String tokenDeAdmin() throws Exception {
        return token("admin@planta.com", "Admin1234");
    }

    private String tokenDeRol(String rolNombre) throws Exception {
        Rol rol = rolRepository.findByNombre(rolNombre).orElseThrow();
        Usuario u = new Usuario();
        u.setNombre("Usuario " + rolNombre + " " + sufijo);
        u.setEmail(rolNombre.toLowerCase() + sufijo + "@planta.com");
        u.setPasswordHash(passwordEncoder.encode("Clave1234"));
        u.setRol(rol);
        u.setActivo(true);
        usuarioRepository.save(u);
        return token(u.getEmail(), "Clave1234");
    }

    private String dni(int i) {
        return String.format("0%07d", prueba * 100 + i);
    }

    private static Sheet hoja(byte[] xlsx) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(xlsx)).getSheetAt(0);
    }

    @Test
    void importarYExportarDeProveedoresPorHttpConElJwtDeUnAdmin() throws Exception {
        String admin = "Bearer " + tokenDeAdmin();

        // 1. Plantilla oficial.
        MvcResult plantilla = mvc.perform(get("/api/admin/importaciones/proveedores/plantilla").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn();
        assertThat(plantilla.getResponse().getContentType()).isEqualTo(XLSX);
        assertThat(plantilla.getResponse().getHeader("Content-Disposition")).contains("plantilla-proveedores-v2.xlsx");
        assertThat(plantilla.getResponse().getHeader("Cache-Control")).contains("no-store");
        byte[] bytesPlantilla = plantilla.getResponse().getContentAsByteArray();
        assertThat(hoja(bytesPlantilla).getSheetName()).isEqualTo("Instrucciones");

        // 2. Vista previa: sube el archivo llenado como multipart.
        byte[] llena = ExcelDePrueba.conFilas(bytesPlantilla, List.of(
                // Fase G (plantilla v2): tipo, nombre, DNI, RUC, razon social, telefono, zona
                java.util.Arrays.asList(null, "Rosa Quispe " + sufijo, dni(1), null, null, "987 654 321", ""),
                java.util.Arrays.asList(null, "Luis Mamani " + sufijo, dni(2), null, null, "", ""),
                java.util.Arrays.asList(null, "Sin DNI " + sufijo, "12", null, null, "", "")));
        MvcResult vista = mvc.perform(multipart("/api/admin/importaciones/proveedores/previsualizar")
                        .file(new MockMultipartFile("archivo", "padron.xlsx", XLSX, llena)).header("Authorization", admin))
                .andExpect(status().isOk()).andReturn();
        JsonNode v = json.readTree(vista.getResponse().getContentAsString());
        assertThat(v.get("validas").asInt()).isEqualTo(2);
        assertThat(v.get("invalidas").asInt()).isEqualTo(1);
        assertThat(v.get("puedeConfirmar").asBoolean()).isTrue();
        assertThat(v.get("filas").get(0).get("valores").get("telefono").asText()).isEqualTo("+51987654321");
        assertThat(proveedorRepository.findByDni(dni(1))).isEmpty(); // la vista previa no creo nada
        String id = v.get("id").asText();

        // 3. El reporte antes de confirmar.
        MvcResult reporte = mvc.perform(get("/api/admin/importaciones/" + id + "/reporte").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn();
        assertThat(hoja(reporte.getResponse().getContentAsByteArray()).getRow(1).getCell(2).getStringCellValue()).contains("DNI");

        // 4. Confirmar (y repetir: no duplica).
        MvcResult confirmada = mvc.perform(post("/api/admin/importaciones/" + id + "/confirmar").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn();
        JsonNode r = json.readTree(confirmada.getResponse().getContentAsString());
        assertThat(r.get("creadas").asInt()).isEqualTo(2);
        assertThat(r.get("yaConfirmada").asBoolean()).isFalse();
        JsonNode repetida = json.readTree(mvc.perform(post("/api/admin/importaciones/" + id + "/confirmar").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(repetida.get("yaConfirmada").asBoolean()).isTrue();

        // 5. Los proveedores existen por la API normal (con QR) y respetan el DNI con cero inicial.
        JsonNode lista = json.readTree(mvc.perform(get("/api/proveedores").param("busqueda", sufijo).header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(lista.get("totalElementos").asInt()).isEqualTo(2);
        assertThat(lista.get("contenido").get(0).get("codigoQr").asText()).isNotBlank();
        assertThat(lista.get("contenido").findValuesAsText("dni")).containsExactlyInAnyOrder(dni(1), dni(2));

        // 6. Y salen en el Excel exportado, con DNI y telefono como texto y la cabecera de descarga expuesta a CORS.
        MvcResult exportado = mvc.perform(get("/api/exportaciones/proveedores").param("busqueda", sufijo)
                        .header("Authorization", admin).header("Origin", "http://localhost:4200"))
                .andExpect(status().isOk()).andReturn();
        assertThat(exportado.getResponse().getContentType()).isEqualTo(XLSX);
        assertThat(exportado.getResponse().getHeader("Content-Disposition")).contains("attachment").contains("proveedores-");
        assertThat(exportado.getResponse().getHeader("Access-Control-Expose-Headers")).contains("Content-Disposition");
        Sheet filas = hoja(exportado.getResponse().getContentAsByteArray());
        assertThat(filas.getLastRowNum()).isEqualTo(2);
        List<String> dnis = List.of(filas.getRow(1).getCell(2).getStringCellValue(), filas.getRow(2).getCell(2).getStringCellValue());
        assertThat(dnis).containsExactlyInAnyOrder(dni(1), dni(2));
        // Solo Rosa tiene telefono; el "+" inicial NO se neutraliza (es un telefono, no una formula).
        assertThat(List.of(filas.getRow(1).getCell(5).getStringCellValue(), filas.getRow(2).getCell(5).getStringCellValue()))
                .containsExactlyInAnyOrder("+51987654321", "");
    }

    @Test
    void unRolQueNoEsAdminNoPuedeImportarNiExportarProveedoresPorHttp() throws Exception {
        String acopiador = "Bearer " + tokenDeRol("ACOPIADOR");

        mvc.perform(get("/api/admin/importaciones/proveedores/plantilla").header("Authorization", acopiador)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/admin/importaciones/proveedores/previsualizar")
                .file(new MockMultipartFile("archivo", "p.xlsx", XLSX, new byte[]{'P', 'K', 3, 4})).header("Authorization", acopiador))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/exportaciones/proveedores").header("Authorization", acopiador)).andExpect(status().isForbidden());
        mvc.perform(get("/api/exportaciones/pagos").header("Authorization", acopiador)).andExpect(status().isForbidden());
        // Lo que si le corresponde (mismo rol que su listado) funciona.
        mvc.perform(get("/api/exportaciones/acopios").header("Authorization", acopiador)).andExpect(status().isOk());
    }

    @Test
    void sinTokenNiSiquieraLlegaAlControlador() throws Exception {
        mvc.perform(get("/api/exportaciones/proveedores")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/importaciones/proveedores/plantilla")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/admin/importaciones/" + java.util.UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void losArchivosHostilesDevuelvenUn400ConUnMensajeClaroSinDetallesInternos() throws Exception {
        String admin = "Bearer " + tokenDeAdmin();
        byte[] valido = ExcelDePrueba.conFilas(
                mvc.perform(get("/api/admin/importaciones/proveedores/plantilla").header("Authorization", admin)).andReturn().getResponse().getContentAsByteArray(),
                List.of(java.util.Arrays.asList(null, "Rosa " + sufijo, dni(1))));

        for (MockMultipartFile hostil : List.of(
                new MockMultipartFile("archivo", "datos.xlsm", XLSX, valido),
                new MockMultipartFile("archivo", "bomba.xlsx", XLSX, ExcelDePrueba.zipBomba(30L * 1024 * 1024)),
                new MockMultipartFile("archivo", "macro.xlsx", XLSX, ExcelDePrueba.conEntradaExtra(valido, "xl/vbaProject.bin", new byte[]{1})),
                new MockMultipartFile("archivo", "texto.xlsx", XLSX, "no soy un excel".getBytes()))) {
            MvcResult resultado = mvc.perform(multipart("/api/admin/importaciones/proveedores/previsualizar").file(hostil).header("Authorization", admin))
                    .andExpect(status().isBadRequest()).andReturn();
            String mensaje = json.readTree(resultado.getResponse().getContentAsString()).get("mensaje").asText();
            assertThat(mensaje).as(hostil.getOriginalFilename()).isNotBlank().doesNotContain("Exception").doesNotContain("org.apache").doesNotContain("at com.");
        }
        assertThat(proveedorRepository.findByDni(dni(1))).isEmpty();
    }

    @Test
    void unTipoDeImportacionQueNoExisteEsUn404() throws Exception {
        mvc.perform(get("/api/admin/importaciones/ventas/plantilla").header("Authorization", "Bearer " + tokenDeAdmin()))
                .andExpect(status().isNotFound());
    }
}
