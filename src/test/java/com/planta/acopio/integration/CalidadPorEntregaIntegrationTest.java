package com.planta.acopio.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.dto.ProveedorRequest;
import com.planta.acopio.dto.RegistroAcopioDTO;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.ProveedorService;
import com.planta.acopio.service.RegistroAcopioService;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase C (calidad por entrega) contra PostgreSQL real y la cadena de seguridad real (login con JWT, MockMvc):
 * - el ejemplo de la auditoria da EXACTAMENTE S/ 590.00 (500 x 1.00 + 100 x 0.90), con el pago original intacto;
 * - cada precio de calidad enlaza con el analisis de ESA entrega, su motivo y su usuario;
 * - "la entrega manda": el ajuste de precio del pago completo solo alcanza a las entregas sin precio propio;
 * - los ajustes de litros de una entrega se valoran a SU precio (dinamico mientras estan pendientes, congelado al liquidar);
 * - solo ADMIN aplica; ADMIN, RECEPCION y CALIDAD consultan; ajustes simultaneos sobre una entrega forman una cadena sin pisarse.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CalidadPorEntregaIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    private static final AtomicInteger PRUEBAS = new AtomicInteger(7000);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private ProveedorService proveedorService;
    @Autowired private RegistroAcopioService registroAcopioService;
    @Autowired private PasswordEncoder passwordEncoder;

    private int prueba;
    private String sufijo;
    private Usuario acopiador;
    private UUID unidadId;
    private LocalDate hoy;

    @BeforeEach
    void preparar() {
        prueba = PRUEBAS.incrementAndGet();
        sufijo = String.valueOf(prueba);
        hoy = LocalDate.now();
        SecurityContextHolder.clearContext();
        if (jdbc.queryForObject("SELECT count(*) FROM precio_litro", Integer.class) == 0) {
            jdbc.update("INSERT INTO precio_litro (precio, vigente_desde) VALUES (1.000, '2000-01-01')");
        }
        acopiador = usuario("ACOPIADOR");
        Unidad unidad = new Unidad();
        unidad.setPlaca("CE" + sufijo);
        unidad.setResponsable(acopiador);
        unidadId = unidadRepository.save(unidad).getId();
    }

    // ------------------------------------------------------------------ el ejemplo de la auditoria

    @Test
    void elEjemploDeLaAuditoriaDaExactamenteSolesQuinientosNoventaConElPagoOriginalIntacto() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        UUID proveedorId = proveedor();
        List<UUID> entregas = seisEntregasDe100Litros(proveedorId);
        UUID deficiente = entregas.get(0);
        UUID analisisId = analisis(deficiente, "F-" + sufijo);

        JsonNode pago = generarPago(admin, proveedorId);
        UUID pagoId = UUID.fromString(pago.get("id").asText());
        assertThat(pago.get("litrosTotales").decimalValue()).isEqualByComparingTo("600");
        assertThat(pago.get("totalAPagar").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(pago.get("ajusteCalidad").decimalValue()).isEqualByComparingTo("0");

        // 100 L de calidad deficiente a 0.90, con su analisis y su motivo
        JsonNode ajuste = aplicarCalidad(admin, pagoId, deficiente, "0.900", analisisId, "Agua en la leche", 201);
        assertThat(ajuste.get("precioAnterior").decimalValue()).isEqualByComparingTo("1.000");
        assertThat(ajuste.get("precioNormal").decimalValue()).isEqualByComparingTo("1.000");
        assertThat(ajuste.get("efectoEnElTotal").decimalValue()).isEqualByComparingTo("-10.00"); // 100 x (0.90 - 1.00)
        assertThat(ajuste.get("analisisCalidadId").asText()).isEqualTo(analisisId.toString());
        assertThat(ajuste.get("analisisFolio").asText()).isEqualTo("F-" + sufijo);
        assertThat(ajuste.get("analisisResultado").asText()).isEqualTo("OBSERVADO");
        assertThat(ajuste.get("usuarioNombre").asText()).isNotBlank();

        // 500 x 1.00 + 100 x 0.90 = 590.00 exactos (con un precio unico por pago habria dado 589.80)
        JsonNode detalle = obtenerPago(admin, pagoId);
        assertThat(detalle.get("totalAPagar").decimalValue()).isEqualByComparingTo("590.00");
        assertThat(detalle.get("ajusteCalidad").decimalValue()).isEqualByComparingTo("-10.00");
        assertThat(detalle.get("precioLitroEfectivo").decimalValue()).isEqualByComparingTo("1.000"); // el precio del pago no cambia
        // el pago original es inmutable
        assertThat(detalle.get("total").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(jdbc.queryForObject("SELECT litros_totales || '|' || precio_litro || '|' || total FROM pago WHERE id = ?", String.class, pagoId))
                .isEqualTo("600.00|1.000|600.00");

        // el listado y el historial por proveedor traen el mismo total reconocido
        for (String url : List.of("/api/pagos", "/api/pagos/proveedor/" + proveedorId + "/paginado")) {
            JsonNode lista = json.readTree(mvc.perform(get(url).header("Authorization", admin)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).get("contenido");
            JsonNode fila = null;
            for (JsonNode p : lista) {
                if (p.get("id").asText().equals(pagoId.toString())) {
                    fila = p;
                }
            }
            assertThat(fila).as("el pago aparece en " + url).isNotNull();
            assertThat(fila.get("totalAPagar").decimalValue()).isEqualByComparingTo("590.00");
        }

        // trazabilidad entrega -> analisis -> precio, y las demas entregas siguen al precio normal
        JsonNode lineas = lineas(admin, pagoId);
        JsonNode lineaDeficiente = lineaDe(lineas, deficiente);
        assertThat(lineaDeficiente.get("precioPropioDeCalidad").asBoolean()).isTrue();
        assertThat(lineaDeficiente.get("precioAplicado").decimalValue()).isEqualByComparingTo("0.900");
        assertThat(lineaDeficiente.get("precioBase").decimalValue()).isEqualByComparingTo("1.000");
        assertThat(lineaDeficiente.get("analisisCalidadId").asText()).isEqualTo(analisisId.toString());
        assertThat(lineaDeficiente.get("motivoAjusteCalidad").asText()).isEqualTo("Agua en la leche");
        for (UUID otra : entregas.subList(1, 6)) {
            JsonNode l = lineaDe(lineas, otra);
            assertThat(l.get("precioPropioDeCalidad").asBoolean()).isFalse();
            assertThat(l.get("precioAplicado").decimalValue()).isEqualByComparingTo("1.000");
        }

        // la boleta se genera con el total reconocido
        MvcResult boleta = mvc.perform(get("/api/pagos/" + pagoId + "/boleta").header("Authorization", admin)).andExpect(status().isOk()).andReturn();
        assertThat(boleta.getResponse().getContentAsByteArray().length).isGreaterThan(1000);

        // historial de precios de calidad del pago
        JsonNode historial = json.readTree(mvc.perform(get("/api/pagos/" + pagoId + "/ajustes-calidad").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(historial).hasSize(1);
        assertThat(historial.get(0).get("registroAcopioId").asText()).isEqualTo(deficiente.toString());
    }

    // ------------------------------------------------------------------ "la entrega manda"

    @Test
    void elAjusteDelPagoCompletoSoloAlcanzaALasEntregasSinPrecioDeCalidadPropio() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        UUID proveedorId = proveedor();
        List<UUID> entregas = seisEntregasDe100Litros(proveedorId);
        UUID deficiente = entregas.get(0);
        UUID analisisId = analisis(deficiente, "M-" + sufijo);
        UUID pagoId = UUID.fromString(generarPago(admin, proveedorId).get("id").asText());

        aplicarCalidad(admin, pagoId, deficiente, "0.900", analisisId, "Agua en la leche", 201);
        mvc.perform(withJson(post("/api/pagos/" + pagoId + "/ajuste-precio").header("Authorization", admin),
                "{\"precioLitroNuevo\":0.800,\"motivo\":\"Ajuste general del pago\"}")).andExpect(status().isCreated());

        JsonNode detalle = obtenerPago(admin, pagoId);
        assertThat(detalle.get("totalAPagar").decimalValue()).isEqualByComparingTo("490.00");  // 100 x 0.90 + 500 x 0.80
        assertThat(detalle.get("precioLitroEfectivo").decimalValue()).isEqualByComparingTo("0.800");
        JsonNode lineas = lineas(admin, pagoId);
        assertThat(lineaDe(lineas, deficiente).get("precioAplicado").decimalValue()).isEqualByComparingTo("0.900");   // la entrega manda
        assertThat(lineaDe(lineas, entregas.get(1)).get("precioAplicado").decimalValue()).isEqualByComparingTo("0.800");

        // y un nuevo precio de calidad de esa entrega tiene como "anterior" el propio (0.90), no el del pago (0.80)
        JsonNode otro = aplicarCalidad(admin, pagoId, deficiente, "0.850", analisisId, "Segundo analisis", 201);
        assertThat(otro.get("precioAnterior").decimalValue()).isEqualByComparingTo("0.900");
        assertThat(obtenerPago(admin, pagoId).get("totalAPagar").decimalValue()).isEqualByComparingTo("485.00"); // 85 + 400
    }

    // ------------------------------------------------------------------ ajustes de litros al precio de LA ENTREGA

    @Test
    void losAjustesDeLitrosSeValoranAlPrecioDeLaEntregaDinamicosHastaLiquidarlos() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        String calidad = tokenDeRol("CALIDAD");
        UUID proveedorId = proveedor();
        List<UUID> entregas = seisEntregasDe100Litros(proveedorId);
        UUID conPrecioPropio = entregas.get(0);
        UUID sinPrecioPropio = entregas.get(1);
        UUID analisisId = analisis(conPrecioPropio, "L-" + sufijo);
        UUID pagoId = UUID.fromString(generarPago(admin, proveedorId).get("id").asText());
        aplicarCalidad(admin, pagoId, conPrecioPropio, "0.900", analisisId, "Agua en la leche", 201);

        // correcciones posteriores a la liquidacion: una de cada tipo de entrega
        corregir(calidad, conPrecioPropio, "95", "Se volvio a medir");
        corregir(calidad, sinPrecioPropio, "95", "Se volvio a medir");
        JsonNode propio = ajusteDe(admin, pagoId, conPrecioPropio);
        JsonNode normal = ajusteDe(admin, pagoId, sinPrecioPropio);
        assertThat(propio.get("precioLitro").decimalValue()).isEqualByComparingTo("0.900");  // a SU precio
        assertThat(propio.get("monto").decimalValue()).isEqualByComparingTo("-4.50");
        assertThat(normal.get("precioLitro").decimalValue()).isEqualByComparingTo("1.000");  // al del pago
        assertThat(normal.get("monto").decimalValue()).isEqualByComparingTo("-5.00");

        // si cambia el precio de calidad de esa entrega, su ajuste PENDIENTE lo sigue
        aplicarCalidad(admin, pagoId, conPrecioPropio, "0.800", analisisId, "Nuevo analisis", 201);
        propio = ajusteDe(admin, pagoId, conPrecioPropio);
        assertThat(propio.get("precioLitro").decimalValue()).isEqualByComparingTo("0.800");
        assertThat(propio.get("monto").decimalValue()).isEqualByComparingTo("-4.00");
        assertThat(propio.get("montoAlCrear").decimalValue()).isEqualByComparingTo("-4.50");
        assertThat(ajusteDe(admin, pagoId, sinPrecioPropio).get("monto").decimalValue()).isEqualByComparingTo("-5.00"); // no le afecta

        // al liquidarlo queda congelado: un nuevo precio de calidad ya no lo mueve
        mvc.perform(patch("/api/pagos/ajustes/" + propio.get("id").asText() + "/liquidar").header("Authorization", admin)).andExpect(status().isOk());
        aplicarCalidad(admin, pagoId, conPrecioPropio, "0.700", analisisId, "Tercer analisis", 201);
        propio = ajusteDe(admin, pagoId, conPrecioPropio);
        assertThat(propio.get("estado").asText()).isEqualTo("LIQUIDADO");
        assertThat(propio.get("monto").decimalValue()).isEqualByComparingTo("-4.00");

        // y una correccion posterior de esa entrega ya se crea al precio nuevo (0.70)
        corregir(calidad, conPrecioPropio, "90", "Ultima verificacion");
        JsonNode ultimo = null;
        for (JsonNode a : ajustes(admin, pagoId)) {
            if (a.get("estado").asText().equals("PENDIENTE") && a.get("registroAcopioId").asText().equals(conPrecioPropio.toString())) {
                ultimo = a;
            }
        }
        assertThat(ultimo).isNotNull();
        assertThat(ultimo.get("litrosDiferencia").decimalValue()).isEqualByComparingTo("-5");
        assertThat(ultimo.get("precioLitro").decimalValue()).isEqualByComparingTo("0.700");
        assertThat(ultimo.get("monto").decimalValue()).isEqualByComparingTo("-3.50");
    }

    // ------------------------------------------------------------------ validaciones y permisos

    @Test
    void soloAdminAplicaYElAnalisisDebeSerDeLaMismaEntrega() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        UUID proveedorId = proveedor();
        List<UUID> entregas = seisEntregasDe100Litros(proveedorId);
        UUID entrega = entregas.get(0);
        UUID analisisPropio = analisis(entrega, "P-" + sufijo);
        UUID analisisDeOtra = analisis(entregas.get(1), "O-" + sufijo);
        UUID pagoId = UUID.fromString(generarPago(admin, proveedorId).get("id").asText());

        // permisos de escritura: solo ADMIN
        for (String rol : List.of("CALIDAD", "RECEPCION", "VENTAS", "PRODUCCION")) {
            aplicarCalidad(tokenDeRol(rol), pagoId, entrega, "0.900", analisisPropio, "No deberia poder", 403);
        }
        // validaciones de negocio
        aplicarCalidad(admin, pagoId, entrega, "0.900", analisisDeOtra, "Analisis de otra entrega", 400);
        aplicarCalidad(admin, pagoId, entrega, "0.900", UUID.randomUUID(), "Analisis inexistente", 404);
        aplicarCalidad(admin, pagoId, entrega, "1.000", analisisPropio, "Igual al precio actual", 400);
        aplicarCalidad(admin, pagoId, entrega, "0.900", analisisPropio, "   ", 400);
        aplicarCalidad(admin, pagoId, UUID.randomUUID(), "0.900", analisisPropio, "Entrega que no esta en el pago", 404);
        aplicarCalidad(admin, UUID.randomUUID(), entrega, "0.900", analisisPropio, "Pago inexistente", 404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ajuste_calidad_entrega a JOIN pago_linea l ON l.id = a.pago_linea_id WHERE l.pago_id = ?", Integer.class, pagoId))
                .isZero(); // ninguno de los rechazados dejo huella

        aplicarCalidad(admin, pagoId, entrega, "0.900", analisisPropio, "Si corresponde", 201);
        aplicarCalidad(admin, pagoId, entrega, "0.9", analisisPropio, "Igual aunque cambie la escala", 400);

        // lectura del historial: ADMIN, RECEPCION y CALIDAD; los demas no. CALIDAD no puede leer el pago ni sus lineas.
        for (String rol : List.of("RECEPCION", "CALIDAD")) {
            mvc.perform(get("/api/pagos/" + pagoId + "/ajustes-calidad").header("Authorization", tokenDeRol(rol))).andExpect(status().isOk());
        }
        for (String rol : List.of("VENTAS", "PRODUCCION")) {
            mvc.perform(get("/api/pagos/" + pagoId + "/ajustes-calidad").header("Authorization", tokenDeRol(rol))).andExpect(status().isForbidden());
        }
        String tokenCalidad = tokenDeRol("CALIDAD");
        mvc.perform(get("/api/pagos/" + pagoId).header("Authorization", tokenCalidad)).andExpect(status().isForbidden());
        mvc.perform(get("/api/pagos/" + pagoId + "/lineas").header("Authorization", tokenCalidad)).andExpect(status().isForbidden());
        mvc.perform(get("/api/pagos/" + UUID.randomUUID() + "/ajustes-calidad").header("Authorization", admin)).andExpect(status().isNotFound());
        // el historial no expone datos personales del proveedor
        String cuerpo = mvc.perform(get("/api/pagos/" + pagoId + "/ajustes-calidad").header("Authorization", tokenDeRol("RECEPCION")))
                .andReturn().getResponse().getContentAsString();
        assertThat(cuerpo).doesNotContain("dni").doesNotContain("telefono");
    }

    // ------------------------------------------------------------------ concurrencia

    @Test
    void ajustesSimultaneosDeUnaEntregaFormanUnaCadenaDondeCadaPrecioAnteriorEsElNuevoDelPrevio() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        UUID proveedorId = proveedor();
        UUID entrega = seisEntregasDe100Litros(proveedorId).get(0);
        UUID analisisId = analisis(entrega, "C-" + sufijo);
        UUID pagoId = UUID.fromString(generarPago(admin, proveedorId).get("id").asText());

        String[] precios = {"0.950", "0.900", "0.850", "0.800", "0.750"};
        ExecutorService pool = Executors.newFixedThreadPool(precios.length);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Integer>> resultados = new ArrayList<>();
        for (String precio : precios) {
            resultados.add(pool.submit(() -> {
                salida.await();
                return mvc.perform(withJson(post("/api/pagos/" + pagoId + "/lineas/" + entrega + "/ajuste-calidad").header("Authorization", admin),
                        "{\"precioLitroNuevo\":" + precio + ",\"analisisCalidadId\":\"" + analisisId + "\",\"motivo\":\"Simultaneo " + precio + "\"}"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        salida.countDown();
        for (Future<Integer> r : resultados) {
            assertThat(r.get(60, TimeUnit.SECONDS)).isEqualTo(201);
        }
        pool.shutdown();

        List<java.util.Map<String, Object>> filas = jdbc.queryForList(
                "SELECT a.precio_anterior AS anterior, a.precio_nuevo AS nuevo FROM ajuste_calidad_entrega a JOIN pago_linea l ON l.id = a.pago_linea_id "
                        + "WHERE l.pago_id = ? ORDER BY a.creado_en, a.id", pagoId);
        assertThat(filas).hasSize(precios.length);
        BigDecimal esperado = new BigDecimal("1.000"); // el precio normal del pago
        for (var fila : filas) {
            assertThat((BigDecimal) fila.get("anterior")).isEqualByComparingTo(esperado);
            esperado = (BigDecimal) fila.get("nuevo");
        }
        // el precio aplicado y el total reconocen el ultimo de la cadena
        JsonNode detalle = obtenerPago(admin, pagoId);
        assertThat(lineaDe(lineas(admin, pagoId), entrega).get("precioAplicado").decimalValue()).isEqualByComparingTo(esperado);
        assertThat(detalle.get("totalAPagar").decimalValue()).isEqualByComparingTo(
                new BigDecimal("500").add(new BigDecimal("100").multiply(esperado)).setScale(2));
    }

    // ------------------------------------------------------------------ ayudas

    private MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder builder, String cuerpo) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    private String token(String email, String password) throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + json.readTree(login.getResponse().getContentAsString()).get("token").asText();
    }

    private String tokenDeRol(String rolNombre) throws Exception {
        return token(usuario(rolNombre).getEmail(), "Clave1234");
    }

    private Usuario usuario(String rolNombre) {
        Rol rol = rolRepository.findByNombre(rolNombre).orElseThrow();
        Usuario u = new Usuario();
        u.setNombre("Usuario " + rolNombre + " " + sufijo);
        u.setEmail(rolNombre.toLowerCase() + sufijo + "-" + UUID.randomUUID().toString().substring(0, 6) + "@planta.com");
        u.setPasswordHash(passwordEncoder.encode("Clave1234"));
        u.setRol(rol);
        u.setActivo(true);
        return usuarioRepository.save(u);
    }

    private UUID proveedor() {
        return proveedorService.crear(new ProveedorRequest("Proveedor Calidad " + sufijo,
                String.format("7%07d", (prueba * 41 + (int) (System.nanoTime() % 1000)) % 10_000_000), null, null)).id();
    }

    /** Seis entregas de 100 L en seis dias distintos de la semana [hoy-6, hoy]; la primera es la mas antigua. */
    private List<UUID> seisEntregasDe100Litros(UUID proveedorId) {
        List<UUID> ids = new ArrayList<>();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(acopiador.getEmail(), null));
        try {
            for (int i = 0; i < 6; i++) {
                ids.add(registroAcopioService.registrarOIgnorarSiDuplicado(new RegistroAcopioDTO(UUID.randomUUID().toString(), proveedorId, unidadId,
                        LocalDateTime.of(hoy.minusDays(6 - i), LocalTime.of(6, 0)), new BigDecimal("100.00"), null, null, null, false, null, null)).id());
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
        return ids;
    }

    private UUID analisis(UUID registroId, String folio) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO analisis_calidad (id, uuid_cliente, registro_acopio_id, folio_muestra, resultado) VALUES (?, ?, ?, ?, 'OBSERVADO')",
                id, UUID.randomUUID().toString(), registroId, folio);
        return id;
    }

    private JsonNode generarPago(String admin, UUID proveedorId) throws Exception {
        MvcResult r = mvc.perform(withJson(post("/api/pagos/generar").header("Authorization", admin),
                        "{\"proveedorId\":\"" + proveedorId + "\",\"semanaInicio\":\"" + hoy.minusDays(6) + "\",\"semanaFin\":\"" + hoy + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode aplicarCalidad(String token, UUID pagoId, UUID registroId, String precio, UUID analisisId, String motivo, int estado) throws Exception {
        MvcResult r = mvc.perform(withJson(post("/api/pagos/" + pagoId + "/lineas/" + registroId + "/ajuste-calidad").header("Authorization", token),
                "{\"precioLitroNuevo\":" + precio + ",\"analisisCalidadId\":\"" + analisisId + "\",\"motivo\":\"" + motivo + "\"}"))
                .andExpect(status().is(estado)).andReturn();
        return estado == 201 ? json.readTree(r.getResponse().getContentAsString()) : null;
    }

    private void corregir(String token, UUID registroId, String litros, String motivo) throws Exception {
        mvc.perform(withJson(post("/api/registros-acopio/" + registroId + "/correcciones").header("Authorization", token),
                "{\"litrosCorregido\":" + litros + ",\"motivo\":\"" + motivo + "\"}")).andExpect(status().isCreated());
    }

    private JsonNode obtenerPago(String admin, UUID pagoId) throws Exception {
        return json.readTree(mvc.perform(get("/api/pagos/" + pagoId).header("Authorization", admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode lineas(String token, UUID pagoId) throws Exception {
        return json.readTree(mvc.perform(get("/api/pagos/" + pagoId + "/lineas").header("Authorization", token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode ajustes(String token, UUID pagoId) throws Exception {
        return json.readTree(mvc.perform(get("/api/pagos/" + pagoId + "/ajustes").header("Authorization", token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** El ajuste de litros MAS RECIENTE de una entrega. */
    private JsonNode ajusteDe(String token, UUID pagoId, UUID registroId) throws Exception {
        for (JsonNode a : ajustes(token, pagoId)) { // el servicio devuelve del mas reciente al mas antiguo
            if (a.get("registroAcopioId").asText().equals(registroId.toString())) {
                return a;
            }
        }
        throw new AssertionError("No hay ajuste de litros para la entrega " + registroId);
    }

    private JsonNode lineaDe(JsonNode lineas, UUID registroId) {
        for (JsonNode l : lineas) {
            if (l.get("registroAcopioId").asText().equals(registroId.toString())) {
                return l;
            }
        }
        throw new AssertionError("No hay linea para el registro " + registroId);
    }


    // ------------------------------------------------------------------ cierre: CALIDAD ve los precios de su entrega

    @Test
    void calidadConsultaLosPreciosDeCalidadDeUnaEntregaSinAccesoGeneralAPagos() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        String calidad = tokenDeRol("CALIDAD");
        UUID proveedorId = proveedor();
        List<UUID> entregas = seisEntregasDe100Litros(proveedorId);
        UUID entrega = entregas.get(0);
        UUID analisisId = analisis(entrega, "Q-" + sufijo);
        UUID pagoId = UUID.fromString(generarPago(admin, proveedorId).get("id").asText());
        String url = "/api/analisis-calidad/registro/" + entrega + "/precios-calidad";

        // sin precios de calidad: lista vacia (no es un error)
        assertThat(json.readTree(mvc.perform(get(url).header("Authorization", calidad)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())).isEmpty();

        aplicarCalidad(admin, pagoId, entrega, "0.900", analisisId, "Agua en la leche", 201);
        aplicarCalidad(admin, pagoId, entrega, "0.850", analisisId, "Segundo analisis", 201);

        String cuerpo = mvc.perform(get(url).header("Authorization", calidad)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode historial = json.readTree(cuerpo);
        assertThat(historial).hasSize(2);
        assertThat(historial.get(0).get("precioLitroNuevo").decimalValue()).isEqualByComparingTo("0.850"); // mas reciente primero
        assertThat(historial.get(1).get("precioLitroNuevo").decimalValue()).isEqualByComparingTo("0.900");
        assertThat(historial.get(0).get("analisisFolio").asText()).isEqualTo("Q-" + sufijo);
        assertThat(historial.get(0).get("motivo").asText()).isEqualTo("Segundo analisis");
        assertThat(cuerpo).doesNotContain("dni").doesNotContain("telefono").doesNotContain("Proveedor Calidad"); // nada del proveedor

        // otra entrega del mismo pago: vacia
        assertThat(json.readTree(mvc.perform(get("/api/analisis-calidad/registro/" + entregas.get(1) + "/precios-calidad")
                .header("Authorization", calidad)).andReturn().getResponse().getContentAsString())).isEmpty();

        // CALIDAD sigue sin acceso general a los pagos
        mvc.perform(get("/api/pagos/" + pagoId).header("Authorization", calidad)).andExpect(status().isForbidden());
        mvc.perform(get("/api/pagos/" + pagoId + "/lineas").header("Authorization", calidad)).andExpect(status().isForbidden());
        mvc.perform(get("/api/pagos").header("Authorization", calidad)).andExpect(status().isForbidden());

        // permisos del nuevo endpoint: CALIDAD y ADMIN; los demas no; registro inexistente 404
        mvc.perform(get(url).header("Authorization", admin)).andExpect(status().isOk());
        for (String rol : List.of("RECEPCION", "VENTAS", "PRODUCCION")) {
            mvc.perform(get(url).header("Authorization", tokenDeRol(rol))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/analisis-calidad/registro/" + UUID.randomUUID() + "/precios-calidad").header("Authorization", calidad))
                .andExpect(status().isNotFound());
    }
}
