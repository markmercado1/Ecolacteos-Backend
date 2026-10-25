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
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase D (historial consolidado) contra PostgreSQL real y la cadena de seguridad real. Un proveedor con zona historica, entregas corregidas, analisis
 * y alertas, un precio de calidad, una liquidacion, un ajuste posterior, un pago aplicado y un adelanto:
 * - los totales del resumen CUADRAN con la suma de las lineas (entregas) y con las demas pantallas (pagos y saldos);
 * - cada rol recibe solo sus secciones (CALIDAD sin pagos, RECEPCION sin calidad, solo ADMIN ve las zonas) y ningun dato personal;
 * - la zona de cada entrega es la de ese momento, y una entrega sin periodo de zona se ve como tal.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HistorialConsolidadoIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    private static final AtomicInteger PRUEBAS = new AtomicInteger(9000);

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
    private String admin;

    @BeforeEach
    void preparar() throws Exception {
        prueba = PRUEBAS.incrementAndGet();
        sufijo = String.valueOf(prueba);
        hoy = LocalDate.now();
        SecurityContextHolder.clearContext();
        if (jdbc.queryForObject("SELECT count(*) FROM precio_litro", Integer.class) == 0) {
            jdbc.update("INSERT INTO precio_litro (precio, vigente_desde) VALUES (1.000, '2000-01-01')");
        }
        acopiador = usuario("ACOPIADOR");
        Unidad unidad = new Unidad();
        unidad.setPlaca("HC" + sufijo);
        unidad.setResponsable(acopiador);
        unidadId = unidadRepository.save(unidad).getId();
        admin = token("admin@planta.com", "Admin1234");
    }

    /** Escenario completo; devuelve el proveedor y las entregas (e0..e5 liquidadas, e6 reciente sin liquidar, e7 anterior a toda zona). */
    private record Escenario(UUID proveedorId, UUID pagoId, List<UUID> entregas) {}

    private Escenario escenario() throws Exception {
        UUID zona1 = zona("Zona Norte " + sufijo);
        UUID zona2 = zona("Zona Sur " + sufijo);
        UUID proveedorId = proveedorService.crear(new ProveedorRequest("Proveedor Historial " + sufijo,
                String.format("5%07d", (prueba * 29 + (int) (System.nanoTime() % 100000)) % 10_000_000), "988" + sufijo, zona2)).id();
        // el periodo abierto (zona Sur) empezo al crear al proveedor; antes estuvo en la zona Norte desde hace 7 dias
        Timestamp inicioAbierto = jdbc.queryForObject("SELECT inicio_en FROM historial_zona WHERE proveedor_id = ? AND fin_en IS NULL", Timestamp.class, proveedorId);
        jdbc.update("INSERT INTO historial_zona (proveedor_id, zona_id, desde, hasta, inicio_en, fin_en) VALUES (?, ?, ?, ?, ?, ?)",
                proveedorId, zona1, hoy.minusDays(7), hoy, Timestamp.valueOf(hoy.minusDays(7).atStartOfDay()), inicioAbierto);

        List<UUID> entregas = new ArrayList<>();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(acopiador.getEmail(), null));
        try {
            for (int i = 0; i < 5; i++) { // hoy-6 .. hoy-2: zona Norte
                entregas.add(registrar(proveedorId, LocalDateTime.of(hoy.minusDays(6 - i), LocalTime.of(6, 0))));
            }
            entregas.add(registrar(proveedorId, LocalDateTime.now())); // e5: ya en la zona Sur
            entregas.add(registrar(proveedorId, LocalDateTime.of(hoy.minusDays(1), LocalTime.of(7, 0)).minusDays(20))); // e6: hace 21 dias, sin zona ni pago
        } finally {
            SecurityContextHolder.clearContext();
        }
        // calidad: e0 rechazada y luego aprobada (cuenta el ultimo), e1 observada, e2 aprobada; una alerta en e0
        analisis(entregas.get(0), "A-" + sufijo, "RECHAZADO", LocalDateTime.now().minusMinutes(5));
        analisis(entregas.get(0), "B-" + sufijo, "APROBADO", LocalDateTime.now().minusMinutes(1));
        analisis(entregas.get(1), "C-" + sufijo, "OBSERVADO", LocalDateTime.now().minusMinutes(2));
        analisis(entregas.get(2), "D-" + sufijo, "APROBADO", LocalDateTime.now().minusMinutes(2));
        jdbc.update("INSERT INTO alerta_anomalia (registro_acopio_id, tipo, severidad) VALUES (?, 'RIESGO_ADULTERACION', 'ALTA')", entregas.get(0));
        // una correccion ANTES de liquidar: e2 de 100 a 95 L
        corregir(entregas.get(2), "95.00", "Medicion en planta");

        UUID pagoId = pagoDe(proveedorId); // liquida e0..e5 (e5 es de hoy)
        UUID analisisE1 = UUID.fromString(jdbc.queryForObject("SELECT id::text FROM analisis_calidad WHERE registro_acopio_id = ?", String.class, entregas.get(1)));
        mvc.perform(withJson(post("/api/pagos/" + pagoId + "/lineas/" + entregas.get(1) + "/ajuste-calidad").header("Authorization", admin),
                "{\"precioLitroNuevo\":0.900,\"analisisCalidadId\":\"" + analisisE1 + "\",\"motivo\":\"Observada\"}")).andExpect(status().isCreated());
        corregir(entregas.get(3), "90.00", "Segunda medicion"); // despues de liquidar: ajuste de -10 L
        // un pago de 200 aplicado y un adelanto de 50 sin aplicar
        mvc.perform(withJson(post("/api/pagos-proveedor").header("Authorization", admin), "{\"uuidCliente\":\"" + UUID.randomUUID() + "\",\"proveedorId\":\""
                + proveedorId + "\",\"modalidad\":\"EFECTIVO\",\"monto\":200.00,\"aplicaciones\":[{\"pagoId\":\"" + pagoId + "\",\"monto\":200.00}]}"))
                .andExpect(status().isCreated());
        mvc.perform(withJson(post("/api/pagos-proveedor").header("Authorization", admin), "{\"uuidCliente\":\"" + UUID.randomUUID() + "\",\"proveedorId\":\""
                + proveedorId + "\",\"modalidad\":\"EFECTIVO\",\"monto\":50.00}")).andExpect(status().isCreated());
        return new Escenario(proveedorId, pagoId, entregas);
    }

    // ------------------------------------------------------------------ totales que cuadran

    @Test
    void elResumenDeAdminCuadraConLasLineasYConLasDemasPantallas() throws Exception {
        Escenario e = escenario();
        JsonNode r = resumen(admin, e.proveedorId());
        assertThat(textos(r.get("secciones"))).containsExactly("ENTREGAS", "CALIDAD", "PAGOS", "ZONAS", "COMPRAS"); // Fase H: COMPRAS solo para ADMIN
        assertThat(r.get("zonaActualNombre").asText()).isEqualTo("Zona Sur " + sufijo);

        // entregas: 7 (e0..e6); recibidos 700; efectivos = 700 - 5 (e2) - 10 (e3 corregida despues) = 685
        JsonNode en = r.get("entregas");
        assertThat(en.get("total").asInt()).isEqualTo(7);
        assertThat(en.get("litrosRecibidos").decimalValue()).isEqualByComparingTo("700.00");
        assertThat(en.get("litrosEfectivos").decimalValue()).isEqualByComparingTo("685.00");
        assertThat(en.get("conCorreccion").asInt()).isEqualTo(2);
        assertThat(en.get("sinZona").asInt()).isEqualTo(1); // e6, anterior a todo periodo de zona

        // las lineas (pagina de entregas, de a 3) suman lo mismo que el resumen
        BigDecimal recibidos = BigDecimal.ZERO, efectivos = BigDecimal.ZERO, importes = BigDecimal.ZERO;
        int vistas = 0;
        for (int pagina = 0; pagina < 3; pagina++) {
            JsonNode p = entregas(admin, e.proveedorId(), pagina, 3);
            assertThat(p.get("totalElementos").asInt()).isEqualTo(7);
            for (JsonNode fila : p.get("contenido")) {
                vistas++;
                recibidos = recibidos.add(fila.get("litrosOriginales").decimalValue());
                efectivos = efectivos.add(fila.get("litrosEfectivos").decimalValue());
                if (!fila.get("liquidacion").isNull()) {
                    importes = importes.add(fila.get("liquidacion").get("importe").decimalValue());
                }
            }
        }
        assertThat(vistas).isEqualTo(7);
        assertThat(recibidos).isEqualByComparingTo(en.get("litrosRecibidos").decimalValue());
        assertThat(efectivos).isEqualByComparingTo(en.get("litrosEfectivos").decimalValue());

        // pagos: una liquidacion de 6 entregas liquidadas con litros efectivos: 100 + 100 + 95 + 100 + 100 + 100 = 595 L; la e1 a 0.90 => 595 - 10 = 585.00
        JsonNode pa = r.get("pagos");
        assertThat(pa.get("liquidaciones").asInt()).isEqualTo(1);
        assertThat(pa.get("litrosLiquidados").decimalValue()).isEqualByComparingTo("595.00");
        assertThat(pa.get("totalOriginal").decimalValue()).isEqualByComparingTo("595.00");
        assertThat(pa.get("totalReconocido").decimalValue()).isEqualByComparingTo("585.00");
        assertThat(pa.get("ajusteCalidad").decimalValue()).isEqualByComparingTo("-10.00");
        assertThat(pa.get("ajustesLitros").decimalValue()).isEqualByComparingTo("-10.00"); // e3: 100 -> 90 despues de liquidar
        assertThat(pa.get("totalConAjustes").decimalValue()).isEqualByComparingTo("575.00");
        assertThat(pa.get("pagado").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(pa.get("saldoLiquidaciones").decimalValue()).isEqualByComparingTo("375.00");
        assertThat(pa.get("adelantosSinAplicar").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(pa.get("saldoNeto").decimalValue()).isEqualByComparingTo("325.00");
        assertThat(pa.get("entregasSinLiquidar").asInt()).isEqualTo(1);
        assertThat(pa.get("litrosSinLiquidar").decimalValue()).isEqualByComparingTo("100.00");

        // cuadre con la suma de las lineas: los importes de la liquidacion, redondeados una vez, son el total a pagar del pago
        assertThat(importes.setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo(pa.get("totalReconocido").decimalValue());
        // cuadre con las demas pantallas: el historial de pagos del proveedor y su saldo
        JsonNode pagos = json.readTree(mvc.perform(get("/api/pagos/proveedor/" + e.proveedorId()).header("Authorization", admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        BigDecimal sumaTotales = BigDecimal.ZERO;
        for (JsonNode p : pagos) sumaTotales = sumaTotales.add(p.get("totalAPagar").decimalValue());
        assertThat(sumaTotales).isEqualByComparingTo(pa.get("totalReconocido").decimalValue());
        JsonNode saldo = json.readTree(mvc.perform(get("/api/pagos-proveedor/proveedor/" + e.proveedorId() + "/saldo").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(saldo.get("saldoLiquidaciones").decimalValue()).isEqualByComparingTo(pa.get("saldoLiquidaciones").decimalValue());
        assertThat(saldo.get("saldoNeto").decimalValue()).isEqualByComparingTo(pa.get("saldoNeto").decimalValue());
        // identidades del propio resumen
        assertThat(pa.get("totalConAjustes").decimalValue()).isEqualByComparingTo(pa.get("totalReconocido").decimalValue().add(pa.get("ajustesLitros").decimalValue()));
        assertThat(pa.get("saldoLiquidaciones").decimalValue()).isEqualByComparingTo(pa.get("totalConAjustes").decimalValue().subtract(pa.get("pagado").decimalValue()));
        assertThat(pa.get("saldoNeto").decimalValue()).isEqualByComparingTo(pa.get("saldoLiquidaciones").decimalValue().subtract(pa.get("adelantosSinAplicar").decimalValue()));

        // calidad: ultimo analisis de e0 = APROBADO, e1 OBSERVADO, e2 APROBADO; 4 entregas sin analisis; una alerta; un precio de calidad
        JsonNode ca = r.get("calidad");
        assertThat(ca.get("analizadas").asInt()).isEqualTo(3);
        assertThat(ca.get("sinAnalisis").asInt()).isEqualTo(4);
        assertThat(ca.get("aprobadas").asInt()).isEqualTo(2);
        assertThat(ca.get("observadas").asInt()).isEqualTo(1);
        assertThat(ca.get("rechazadas").asInt()).isZero();
        assertThat(ca.get("alertas").asInt()).isEqualTo(1);
        assertThat(ca.get("conPrecioDeCalidad").asInt()).isEqualTo(1);
        assertThat(ca.get("analizadas").asInt() + ca.get("sinAnalisis").asInt()).isEqualTo(en.get("total").asInt());
        assertThat(ca.get("aprobadas").asInt() + ca.get("observadas").asInt() + ca.get("rechazadas").asInt()).isEqualTo(ca.get("analizadas").asInt());

        // zonas (solo ADMIN): el periodo Sur actual y el Norte anterior, mas reciente primero
        assertThat(r.get("zonas")).hasSize(2);
    }

    // ------------------------------------------------------------------ zona, calidad y liquidacion de cada entrega

    @Test
    void cadaEntregaMuestraSuZonaDeEseMomentoSuCalidadYSuLiquidacion() throws Exception {
        Escenario e = escenario();
        JsonNode lista = entregas(admin, e.proveedorId(), 0, 20).get("contenido");
        assertThat(lista).hasSize(7);
        // mas reciente primero: e5 (ahora), e4, e3, e2, e1, e0, e6 (hace 21 dias)
        assertThat(lista.get(0).get("id").asText()).isEqualTo(e.entregas().get(5).toString());
        assertThat(lista.get(6).get("id").asText()).isEqualTo(e.entregas().get(6).toString());

        JsonNode e5 = fila(lista, e.entregas().get(5));
        assertThat(e5.get("zonaNombre").asText()).isEqualTo("Zona Sur " + sufijo); // entrego despues del cambio de zona
        JsonNode e4 = fila(lista, e.entregas().get(4));
        assertThat(e4.get("zonaNombre").asText()).isEqualTo("Zona Norte " + sufijo); // la zona de ese momento, no la actual
        assertThat(fila(lista, e.entregas().get(6)).get("zonaNombre").isNull()).isTrue(); // sin periodo de zona: se ve como tal, no se rellena

        JsonNode e2 = fila(lista, e.entregas().get(2)); // corregida antes de liquidar: 100 -> 95
        assertThat(e2.get("litrosOriginales").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(e2.get("litrosEfectivos").decimalValue()).isEqualByComparingTo("95.00");
        assertThat(e2.get("corregida").asBoolean()).isTrue();
        assertThat(e2.get("liquidacion").get("litrosLiquidados").decimalValue()).isEqualByComparingTo("95.00");

        JsonNode e1 = fila(lista, e.entregas().get(1)); // precio de calidad propio
        assertThat(e1.get("calidad").get("resultado").asText()).isEqualTo("OBSERVADO");
        assertThat(e1.get("calidad").get("precioPropio").asBoolean()).isTrue();
        assertThat(e1.get("liquidacion").get("precioAplicado").decimalValue()).isEqualByComparingTo("0.900");
        assertThat(e1.get("liquidacion").get("importe").decimalValue()).isEqualByComparingTo("90.00000");

        JsonNode e0 = fila(lista, e.entregas().get(0)); // dos analisis: cuenta el ultimo; una alerta
        assertThat(e0.get("calidad").get("resultado").asText()).isEqualTo("APROBADO");
        assertThat(e0.get("calidad").get("analisis").asInt()).isEqualTo(2);
        assertThat(e0.get("calidad").get("alertas").asInt()).isEqualTo(1);
        assertThat(e0.get("calidad").get("precioPropio").asBoolean()).isFalse();
        assertThat(e0.get("liquidacion").get("pagoId").asText()).isEqualTo(e.pagoId().toString());

        JsonNode e6 = fila(lista, e.entregas().get(6)); // sin liquidar y sin analisis
        assertThat(e6.get("liquidacion").isNull()).isTrue();
        assertThat(e6.get("calidad").get("resultado").isNull()).isTrue();
        assertThat(e6.get("calidad").get("analisis").asInt()).isZero();
    }

    // ------------------------------------------------------------------ secciones por rol

    @Test
    void cadaRolRecibeSoloSusSeccionesYNingunDatoPersonal() throws Exception {
        Escenario e = escenario();
        String calidad = tokenDeRol("CALIDAD");
        String recepcion = tokenDeRol("RECEPCION");
        String dni = jdbc.queryForObject("SELECT dni FROM proveedor WHERE id = ?", String.class, e.proveedorId());

        JsonNode c = resumen(calidad, e.proveedorId());
        assertThat(textos(c.get("secciones"))).containsExactly("ENTREGAS", "CALIDAD");
        assertThat(c.get("entregas").isNull()).isFalse();
        assertThat(c.get("calidad").isNull()).isFalse();
        assertThat(c.get("pagos").isNull()).isTrue();
        assertThat(c.get("zonas").isNull()).isTrue();
        JsonNode ec = entregas(calidad, e.proveedorId(), 0, 20).get("contenido");
        assertThat(ec.get(0).get("calidad").isNull()).isFalse();
        assertThat(ec.get(0).get("liquidacion").isNull()).isTrue(); // CALIDAD no ve importes ni liquidaciones, aunque la entrega este liquidada
        assertThat(fila(ec, e.entregas().get(0)).get("liquidacion").isNull()).isTrue();

        JsonNode r = resumen(recepcion, e.proveedorId());
        assertThat(textos(r.get("secciones"))).containsExactly("ENTREGAS", "PAGOS");
        assertThat(r.get("calidad").isNull()).isTrue();
        assertThat(r.get("pagos").isNull()).isFalse();
        assertThat(r.get("zonas").isNull()).isTrue();
        assertThat(r.get("pagos").get("saldoNeto").decimalValue()).isEqualByComparingTo("325.00");
        JsonNode er = entregas(recepcion, e.proveedorId(), 0, 20).get("contenido");
        assertThat(fila(er, e.entregas().get(1)).get("calidad").isNull()).isTrue(); // RECEPCION no ve calidad
        assertThat(fila(er, e.entregas().get(1)).get("liquidacion").isNull()).isFalse();

        for (String token : List.of(admin, calidad, recepcion)) {
            for (String url : List.of("/api/proveedores/" + e.proveedorId() + "/historial/resumen", "/api/proveedores/" + e.proveedorId() + "/historial/entregas")) {
                String cuerpo = mvc.perform(get(url).header("Authorization", token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                assertThat(cuerpo).doesNotContain(dni).doesNotContainIgnoringCase("telefono").doesNotContainIgnoringCase("\"dni\"");
            }
        }
        for (String rol : List.of("VENTAS", "PRODUCCION", "ACOPIADOR")) {
            mvc.perform(get("/api/proveedores/" + e.proveedorId() + "/historial/resumen").header("Authorization", tokenDeRol(rol))).andExpect(status().isForbidden());
            mvc.perform(get("/api/proveedores/" + e.proveedorId() + "/historial/entregas").header("Authorization", tokenDeRol(rol))).andExpect(status().isForbidden());
        }
    }

    @Test
    void unProveedorSinMovimientosSeVeVacioYUnoInexistenteDa404() throws Exception {
        UUID proveedorId = proveedorService.crear(new ProveedorRequest("Proveedor Vacio " + sufijo,
                String.format("4%07d", (prueba * 31 + (int) (System.nanoTime() % 100000)) % 10_000_000), null, null)).id();
        JsonNode r = resumen(admin, proveedorId);
        assertThat(r.get("entregas").get("total").asInt()).isZero();
        assertThat(r.get("entregas").get("primeraEntrega").isNull()).isTrue();
        assertThat(r.get("pagos").get("liquidaciones").asInt()).isZero();
        assertThat(r.get("pagos").get("saldoNeto").decimalValue()).isEqualByComparingTo("0");
        assertThat(r.get("calidad").get("analizadas").asInt()).isZero();
        assertThat(entregas(admin, proveedorId, 0, 20).get("contenido")).isEmpty();
        mvc.perform(get("/api/proveedores/" + UUID.randomUUID() + "/historial/resumen").header("Authorization", admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/proveedores/" + UUID.randomUUID() + "/historial/entregas").header("Authorization", admin)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ ayudas

    private static List<String> textos(JsonNode lista) {
        List<String> t = new ArrayList<>();
        lista.forEach(n -> t.add(n.asText()));
        return t;
    }

    private JsonNode fila(JsonNode lista, UUID id) {
        for (JsonNode f : lista) {
            if (f.get("id").asText().equals(id.toString())) return f;
        }
        throw new AssertionError("No esta la entrega " + id);
    }

    private JsonNode resumen(String token, UUID proveedorId) throws Exception {
        return json.readTree(mvc.perform(get("/api/proveedores/" + proveedorId + "/historial/resumen").header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode entregas(String token, UUID proveedorId, int pagina, int tamano) throws Exception {
        return json.readTree(mvc.perform(get("/api/proveedores/" + proveedorId + "/historial/entregas?page=" + pagina + "&size=" + tamano).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private UUID registrar(UUID proveedorId, LocalDateTime fechaHora) {
        return registroAcopioService.registrarOIgnorarSiDuplicado(new RegistroAcopioDTO(UUID.randomUUID().toString(), proveedorId, unidadId,
                fechaHora, new BigDecimal("100.00"), null, null, null, false, null, null)).id();
    }

    private UUID zona(String nombre) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO zona (id, nombre) VALUES (?, ?)", id, nombre);
        return id;
    }

    private void analisis(UUID registroId, String folio, String resultado, LocalDateTime creadoEn) {
        jdbc.update("INSERT INTO analisis_calidad (id, uuid_cliente, registro_acopio_id, folio_muestra, resultado, creado_en) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), UUID.randomUUID().toString(), registroId, folio, resultado, Timestamp.valueOf(creadoEn));
    }

    private UUID pagoDe(UUID proveedorId) throws Exception {
        MvcResult r = mvc.perform(withJson(post("/api/pagos/generar").header("Authorization", admin),
                        "{\"proveedorId\":\"" + proveedorId + "\",\"semanaInicio\":\"" + hoy.minusDays(6) + "\",\"semanaFin\":\"" + hoy + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    private void corregir(UUID registroId, String litros, String motivo) throws Exception {
        mvc.perform(withJson(post("/api/registros-acopio/" + registroId + "/correcciones").header("Authorization", admin),
                "{\"litrosCorregido\":" + litros + ",\"motivo\":\"" + motivo + "\"}")).andExpect(status().isCreated());
    }

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
}
