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
 * Fase B (litros efectivos) contra PostgreSQL real y la cadena de seguridad real (login con JWT, MockMvc):
 * - una correccion previa a liquidar SI llega al pago (se liquidan los litros efectivos) y el registro original no cambia;
 * - una correccion posterior genera un ajuste desde lo liquidado, valorado al precio efectivo (incluye ajustes de calidad);
 * - un ajuste PENDIENTE sigue al precio del pago y al liquidarlo queda congelado;
 * - motivo obligatorio, correccion sin cambio rechazada y permisos por rol;
 * - concurrencia: correcciones simultaneas y liquidaciones simultaneas.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LitrosEfectivosIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    private static final AtomicInteger PRUEBAS = new AtomicInteger(6000);

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
        unidad.setPlaca("LE" + sufijo);
        unidad.setResponsable(acopiador);
        unidadId = unidadRepository.save(unidad).getId();
    }

    // ------------------------------------------------------------------ flujo completo

    @Test
    void laCorreccionPreviaLlegaAlPagoLasPosterioresAjustanAlPrecioEfectivoYElOriginalNuncaCambia() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        String calidad = tokenDeRol("CALIDAD");
        UUID proveedorId = proveedor();
        UUID a = registrar(proveedorId, 100, hoy.minusDays(3));
        UUID b = registrar(proveedorId, 40, hoy.minusDays(2));

        // 1. Correccion ANTES de liquidar (100 -> 90): se registra, pero aun no hay pago, asi que no hay ajuste.
        JsonNode previa = corregir(calidad, a, "90", "Balde mal medido", 201);
        assertThat(previa.get("litrosAnterior").decimalValue()).isEqualByComparingTo("100");

        // 2. Liquidar: se pagan los litros EFECTIVOS (90 + 40), el registro original sigue en 100.
        JsonNode pago = generarPago(admin, proveedorId);
        UUID pagoId = UUID.fromString(pago.get("id").asText());
        assertThat(pago.get("litrosTotales").decimalValue()).isEqualByComparingTo("130");
        assertThat(pago.get("totalAPagar").decimalValue()).isEqualByComparingTo("130.00");
        assertThat(litrosDelRegistro(a)).isEqualByComparingTo("100");

        JsonNode lineas = lineas(admin, pagoId);
        JsonNode lineaA = lineaDe(lineas, a);
        assertThat(lineaA.get("litrosOriginales").decimalValue()).isEqualByComparingTo("100");
        assertThat(lineaA.get("litrosLiquidados").decimalValue()).isEqualByComparingTo("90");
        assertThat(lineaA.get("litrosContabilizados").decimalValue()).isEqualByComparingTo("90");
        assertThat(lineaA.get("correccionAplicadaId").asText()).isEqualTo(previa.get("id").asText());
        assertThat(lineaDe(lineas, b).get("litrosLiquidados").decimalValue()).isEqualByComparingTo("40");
        assertThat(jdbc.queryForObject("SELECT litros_totales - (SELECT sum(litros_liquidados) FROM pago_linea WHERE pago_id = ?) FROM pago WHERE id = ?",
                BigDecimal.class, pagoId, pagoId)).isEqualByComparingTo("0"); // el total del pago es la suma de sus lineas

        // 3. Correccion DESPUES de liquidar (90 -> 85): ajuste de -5 desde lo liquidado (no -15 desde el original).
        corregir(calidad, a, "85", "Se volvio a pesar", 201);
        JsonNode ajuste = unicoAjuste(admin, pagoId);
        assertThat(ajuste.get("litrosDiferencia").decimalValue()).isEqualByComparingTo("-5");
        assertThat(ajuste.get("precioLitro").decimalValue()).isEqualByComparingTo("1.000");
        assertThat(ajuste.get("monto").decimalValue()).isEqualByComparingTo("-5.00");
        assertThat(jdbc.queryForObject("SELECT litros_contabilizados_antes FROM ajuste_pago WHERE id = ?", BigDecimal.class,
                UUID.fromString(ajuste.get("id").asText()))).isEqualByComparingTo("90");
        assertThat(lineaDe(lineas(admin, pagoId), a).get("litrosContabilizados").decimalValue()).isEqualByComparingTo("85");

        // 4. Ajuste de CALIDAD del pago (1.00 -> 0.90): el total baja y el ajuste PENDIENTE sigue al nuevo precio.
        ajustarPrecio(admin, pagoId, "0.900", "Leche de menor calidad");
        assertThat(obtenerPago(admin, pagoId).get("totalAPagar").decimalValue()).isEqualByComparingTo("117.00"); // 130 x 0.90
        ajuste = unicoAjuste(admin, pagoId);
        assertThat(ajuste.get("precioLitro").decimalValue()).isEqualByComparingTo("0.900");
        assertThat(ajuste.get("monto").decimalValue()).isEqualByComparingTo("-4.50");
        assertThat(ajuste.get("montoAlCrear").decimalValue()).isEqualByComparingTo("-5.00");
        // 117.00 - 4.50 = 112.50 = 125 L x 0.90: el total y el ajuste cuadran con los litros efectivos y el precio efectivo
        assertThat(new BigDecimal("117.00").add(ajuste.get("monto").decimalValue())).isEqualByComparingTo("112.50");

        // 5. Al liquidar el ajuste su precio se congela: un nuevo ajuste de calidad ya no lo mueve.
        mvc.perform(patch("/api/pagos/ajustes/" + ajuste.get("id").asText() + "/liquidar").header("Authorization", admin))
                .andExpect(status().isOk());
        ajustarPrecio(admin, pagoId, "0.800", "Segundo ajuste de calidad");
        ajuste = unicoAjuste(admin, pagoId);
        assertThat(ajuste.get("estado").asText()).isEqualTo("LIQUIDADO");
        assertThat(ajuste.get("monto").decimalValue()).isEqualByComparingTo("-4.50");
        assertThat(obtenerPago(admin, pagoId).get("totalAPagar").decimalValue()).isEqualByComparingTo("104.00"); // 130 x 0.80

        // 6. Una correccion posterior (85 -> 80) se valora al precio efectivo de AHORA (0.80) y parte de lo ya contabilizado (85).
        corregir(calidad, a, "80", "Ultima verificacion", 201);
        JsonNode nuevo = null;
        for (JsonNode x : ajustes(admin, pagoId)) {
            if (x.get("estado").asText().equals("PENDIENTE")) {
                nuevo = x;
            }
        }
        assertThat(nuevo).isNotNull();
        assertThat(nuevo.get("litrosDiferencia").decimalValue()).isEqualByComparingTo("-5");
        assertThat(nuevo.get("precioLitro").decimalValue()).isEqualByComparingTo("0.800");
        assertThat(nuevo.get("monto").decimalValue()).isEqualByComparingTo("-4.00");
        assertThat(lineaDe(lineas(admin, pagoId), a).get("litrosContabilizados").decimalValue()).isEqualByComparingTo("80");

        // Los litros recibidos nunca se sobrescribieron.
        assertThat(litrosDelRegistro(a)).isEqualByComparingTo("100");
        assertThat(litrosDelRegistro(b)).isEqualByComparingTo("40");
    }

    // ------------------------------------------------------------------ validaciones y permisos

    @Test
    void laCorreccionExigeMotivoYUnCambioRealYSoloCalidadOAdminPuedenCorregir() throws Exception {
        String calidad = tokenDeRol("CALIDAD");
        UUID proveedorId = proveedor();
        UUID a = registrar(proveedorId, 100, hoy.minusDays(3));

        corregir(calidad, a, "90", null, 400);
        corregir(calidad, a, "90", "   ", 400);
        corregir(calidad, a, "100", "Sin cambio real", 400);        // igual al original
        corregir(calidad, a, "90", "Primera correccion valida", 201);
        corregir(calidad, a, "90.004", "Sin cambio tras redondear", 400); // 90.00 = vigente
        corregir(calidad, a, "100", "Se vuelve al valor original", 201);  // contra la vigente (90), no contra el original
        assertThat(jdbc.queryForObject("SELECT count(*) FROM correccion_registro WHERE registro_acopio_id = ?", Integer.class, a)).isEqualTo(2);
        assertThat(litrosDelRegistro(a)).isEqualByComparingTo("100");

        for (String rol : List.of("RECEPCION", "VENTAS", "PRODUCCION")) {
            mvc.perform(withJson(post("/api/registros-acopio/" + a + "/correcciones").header("Authorization", tokenDeRol(rol)),
                    "{\"litrosCorregido\":80,\"motivo\":\"No deberia poder\"}")).andExpect(status().isForbidden());
        }
        mvc.perform(withJson(post("/api/registros-acopio/" + a + "/correcciones").header("Authorization", token("admin@planta.com", "Admin1234")),
                "{\"litrosCorregido\":80,\"motivo\":\"El admin si puede\"}")).andExpect(status().isCreated());
    }

    @Test
    void elDetalleDeLineasLoLeenAdminYRecepcionPeroNoVentas() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        UUID proveedorId = proveedor();
        registrar(proveedorId, 50, hoy.minusDays(2));
        UUID pagoId = UUID.fromString(generarPago(admin, proveedorId).get("id").asText());

        mvc.perform(get("/api/pagos/" + pagoId + "/lineas").header("Authorization", tokenDeRol("RECEPCION"))).andExpect(status().isOk());
        mvc.perform(get("/api/pagos/" + pagoId + "/lineas").header("Authorization", tokenDeRol("VENTAS"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/pagos/" + UUID.randomUUID() + "/lineas").header("Authorization", admin)).andExpect(status().isNotFound());
        // sin datos personales del proveedor (RNF-12)
        String cuerpo = mvc.perform(get("/api/pagos/" + pagoId + "/lineas").header("Authorization", tokenDeRol("RECEPCION")))
                .andReturn().getResponse().getContentAsString();
        assertThat(cuerpo).doesNotContain("dni").doesNotContain("telefono");
    }

    // ------------------------------------------------------------------ concurrencia

    @Test
    void correccionesSimultaneasSobreUnaEntregaLiquidadaFormanUnaCadenaSinDobleConteo() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        String calidad = tokenDeRol("CALIDAD");
        UUID proveedorId = proveedor();
        UUID a = registrar(proveedorId, 100, hoy.minusDays(3));
        UUID pagoId = UUID.fromString(generarPago(admin, proveedorId).get("id").asText());

        int hilos = 6;
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Integer>> resultados = new ArrayList<>();
        for (int i = 1; i <= hilos; i++) {
            String valor = String.valueOf(100 - i); // 99, 98, ... 94: todos distintos del original y entre si
            resultados.add(pool.submit(() -> {
                salida.await();
                return mvc.perform(withJson(post("/api/registros-acopio/" + a + "/correcciones").header("Authorization", calidad),
                        "{\"litrosCorregido\":" + valor + ",\"motivo\":\"Correccion simultanea " + valor + "\"}")).andReturn().getResponse().getStatus();
            }));
        }
        salida.countDown();
        for (Future<Integer> r : resultados) {
            assertThat(r.get(60, TimeUnit.SECONDS)).isEqualTo(201);
        }
        pool.shutdown();

        // La cadena de ajustes es continua: cada uno parte de lo que dejo el anterior y la suma lleva de lo liquidado al ultimo valor.
        List<java.util.Map<String, Object>> filas = jdbc.queryForList(
                "SELECT a.litros_contabilizados_antes AS antes, a.litros_diferencia AS dif, c.litros_corregido AS corregido "
                        + "FROM ajuste_pago a JOIN correccion_registro c ON c.id = a.correccion_registro_id "
                        + "WHERE a.registro_acopio_id = ? ORDER BY a.creado_en, a.id", a);
        assertThat(filas).hasSize(hilos);
        BigDecimal esperadoAntes = new BigDecimal("100");
        for (var fila : filas) {
            assertThat((BigDecimal) fila.get("antes")).isEqualByComparingTo(esperadoAntes);
            assertThat(((BigDecimal) fila.get("antes")).add((BigDecimal) fila.get("dif"))).isEqualByComparingTo((BigDecimal) fila.get("corregido"));
            esperadoAntes = (BigDecimal) fila.get("corregido");
        }
        assertThat(lineaDe(lineas(admin, pagoId), a).get("litrosContabilizados").decimalValue()).isEqualByComparingTo(esperadoAntes);
    }

    @Test
    void dosLiquidacionesSimultaneasDelMismoRangoSolapadoDejanUnSoloPagoYUnaSolaLineaPorEntrega() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        UUID proveedorId = proveedor();
        UUID a = registrar(proveedorId, 100, hoy.minusDays(2));
        UUID b = registrar(proveedorId, 60, hoy.minusDays(1));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Integer>> resultados = new ArrayList<>();
        for (int desplazamiento : new int[]{6, 5}) { // semanaInicio distinta: pasa el UNIQUE(proveedor, semana) pero se solapan
            resultados.add(pool.submit(() -> {
                salida.await();
                return mvc.perform(withJson(post("/api/pagos/generar").header("Authorization", admin),
                        "{\"proveedorId\":\"" + proveedorId + "\",\"semanaInicio\":\"" + hoy.minusDays(desplazamiento) + "\",\"semanaFin\":\"" + hoy + "\"}"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        salida.countDown();
        List<Integer> estados = new ArrayList<>();
        for (Future<Integer> r : resultados) {
            estados.add(r.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(estados).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pago WHERE proveedor_id = ?", Integer.class, proveedorId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pago_linea WHERE registro_acopio_id IN (?, ?)", Integer.class, a, b)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pago_registro_acopio WHERE registro_acopio_id IN (?, ?)", Integer.class, a, b)).isEqualTo(2);
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
        return proveedorService.crear(new ProveedorRequest("Proveedor Litros " + sufijo,
                String.format("8%07d", (prueba * 37 + (int) (System.nanoTime() % 1000)) % 10_000_000), null, null)).id();
    }

    private UUID registrar(UUID proveedorId, int litros, LocalDate dia) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(acopiador.getEmail(), null));
        try {
            return registroAcopioService.registrarOIgnorarSiDuplicado(new RegistroAcopioDTO(UUID.randomUUID().toString(), proveedorId, unidadId,
                    LocalDateTime.of(dia, java.time.LocalTime.of(6, 0)), BigDecimal.valueOf(litros), null, null, null, false, null, null)).id();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private JsonNode corregir(String token, UUID registroId, String litros, String motivo, int estado) throws Exception {
        String cuerpo = "{\"litrosCorregido\":" + litros + (motivo == null ? "" : ",\"motivo\":\"" + motivo + "\"") + "}";
        MvcResult r = mvc.perform(withJson(post("/api/registros-acopio/" + registroId + "/correcciones").header("Authorization", token), cuerpo))
                .andExpect(status().is(estado)).andReturn();
        return estado == 201 ? json.readTree(r.getResponse().getContentAsString()) : null;
    }

    private JsonNode generarPago(String admin, UUID proveedorId) throws Exception {
        MvcResult r = mvc.perform(withJson(post("/api/pagos/generar").header("Authorization", admin),
                        "{\"proveedorId\":\"" + proveedorId + "\",\"semanaInicio\":\"" + hoy.minusDays(6) + "\",\"semanaFin\":\"" + hoy + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private void ajustarPrecio(String admin, UUID pagoId, String precio, String motivo) throws Exception {
        mvc.perform(withJson(post("/api/pagos/" + pagoId + "/ajuste-precio").header("Authorization", admin),
                "{\"precioLitroNuevo\":" + precio + ",\"motivo\":\"" + motivo + "\"}")).andExpect(status().isCreated());
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

    private JsonNode unicoAjuste(String token, UUID pagoId) throws Exception {
        JsonNode todos = ajustes(token, pagoId);
        assertThat(todos).hasSize(1);
        return todos.get(0);
    }

    private JsonNode lineaDe(JsonNode lineas, UUID registroId) {
        for (JsonNode l : lineas) {
            if (l.get("registroAcopioId").asText().equals(registroId.toString())) {
                return l;
            }
        }
        throw new AssertionError("No hay linea para el registro " + registroId);
    }

    private BigDecimal litrosDelRegistro(UUID registroId) {
        return jdbc.queryForObject("SELECT litros FROM registro_acopio WHERE id = ?", BigDecimal.class, registroId);
    }
}
