package com.planta.acopio.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.dto.AjusteInventarioRequest;
import com.planta.acopio.dto.PrecioQuesoRequest;
import com.planta.acopio.dto.ProveedorRequest;
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
import com.planta.acopio.service.ProveedorService;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase H contra PostgreSQL real y la cadena de seguridad real (JWT, MockMvc). Recorre los criterios de aceptacion:
 * venta PUBLICA anonima sin ficha; registro voluntario (sin autorizacion no hay ficha; con ella, ficha y evidencia; la comercial aparte,
 * revocable); coincidencias por telefono que no se unen solas; un proveedor no se duplica como cliente; reglas MAYORISTA y PROVEEDOR;
 * compras en el historial del proveedor sin tocar sus liquidaciones; retiro de reserva con y sin comprador vinculado; fusion manual;
 * app movil sin cambios; datos enmascarados para VENTAS; aviso de datos personales publico.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClientesCompradoresIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    private static final AtomicInteger PRUEBAS = new AtomicInteger(300);
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private PrecioQuesoService precioQuesoService;
    @Autowired private AjusteInventarioService ajusteInventarioService;
    @Autowired private ProveedorService proveedorService;

    private String admin;
    private String ventas;
    private String sufijo;
    private Presentacion queso;
    private final LocalDate hoy = LocalDate.now(LIMA);

    @BeforeEach
    void preparar() throws Exception {
        sufijo = String.valueOf(PRUEBAS.incrementAndGet());
        admin = token("admin@planta.com", "Admin1234");
        ventas = token(usuario("VENTAS").getEmail(), "Clave1234");
        queso = presentacionConStock(50);
    }

    // ------------------------------------------------------------------ venta anonima y registro voluntario

    @Test
    void unaVentaPublicaAnonimaNoCreaFichaYQuienRechazaElRegistroCompraIgual() throws Exception {
        long fichasAntes = contar("cliente");
        JsonNode venta = vender(ventas, "PUBLICO", null, 2, 201);
        assertThat(venta.get("comprador").isNull()).isTrue();
        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(contar("cliente")).isEqualTo(fichasAntes);

        // registrarse SIN autorizacion: no se crea ficha (y la venta ya se hizo igual)
        enviar(post("/api/clientes"), ventas, registro("Rosa " + sufijo, telefono(1), false, false), 400);
        assertThat(contar("cliente")).isEqualTo(fichasAntes);
    }

    @Test
    void elRegistroVoluntarioGuardaLaEvidenciaYNoActivaPromocionesPorSiSolo() throws Exception {
        JsonNode c = enviar(post("/api/clientes"), ventas, registro("Rosa Quispe " + sufijo, telefono(2), true, false), 201);
        UUID id = UUID.fromString(c.get("id").asText());
        assertThat(c.get("registro").get("vigente").asBoolean()).isTrue();
        assertThat(c.get("comunicaciones").get("vigente").asBoolean()).isFalse();
        assertThat(c.get("datosCompletos").asBoolean()).isFalse(); // VENTAS lo ve enmascarado
        assertThat(c.get("telefonos").get(0).asText()).startsWith("•••").doesNotContain("987");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cliente_consentimiento WHERE cliente_id = ? AND finalidad = 'REGISTRO_CLIENTE' AND estado = 'OTORGADO'",
                Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cliente_consentimiento WHERE cliente_id = ? AND finalidad = 'COMUNICACIONES_COMERCIALES'",
                Long.class, id)).isZero();

        // la autorizacion comercial se da aparte y se revoca igual de facil
        JsonNode con = enviar(post("/api/clientes/" + id + "/comunicaciones"), ventas,
                "{\"otorgada\":true,\"medios\":[\"WHATSAPP\"],\"forma\":\"VERBAL_PRESENCIAL\",\"avisoVersion\":\"2026-10-24\"}", 200);
        assertThat(con.get("comunicaciones").get("vigente").asBoolean()).isTrue();
        assertThat(listar("/api/clientes?conComunicaciones=true&busqueda=" + sufijo, admin).get("contenido")).hasSize(1);
        JsonNode sin = enviar(post("/api/clientes/" + id + "/comunicaciones"), ventas, "{\"otorgada\":false,\"forma\":\"VERBAL_PRESENCIAL\"}", 200);
        assertThat(sin.get("comunicaciones").get("vigente").asBoolean()).isFalse();
        assertThat(sin.get("comunicaciones").get("estado").asText()).isEqualTo("REVOCADO");
        assertThat(listar("/api/clientes?conComunicaciones=true&busqueda=" + sufijo, admin).get("contenido")).isEmpty();
        // la evidencia queda completa (otorgar y revocar), ADMIN ve el telefono completo
        assertThat(json.readTree(leer(admin, "/api/clientes/" + id + "/consentimientos"))).hasSize(3);
        assertThat(json.readTree(leer(admin, "/api/clientes/" + id)).get("telefonos").get(0).asText()).isEqualTo(telefono(2));
    }

    @Test
    void unTelefonoCompartidoSoloSugiereYNoUneFichasSolas() throws Exception {
        enviar(post("/api/clientes"), ventas, registro("Ana Ruiz " + sufijo, telefono(3), true, false), 201);
        // otra persona con el mismo telefono: 409 hasta que el trabajador confirme que es otra persona
        enviar(post("/api/clientes"), ventas, registro("Luis Ruiz " + sufijo, telefono(3), true, false), 409);
        String confirmado = registro("Luis Ruiz " + sufijo, telefono(3), true, false).replace("\"confirmadoSinCoincidencia\":false", "\"confirmadoSinCoincidencia\":true");
        enviar(post("/api/clientes"), ventas, confirmado, 201);
        JsonNode sugerencias = enviar(post("/api/compradores/sugerencias"), ventas, "{\"telefono\":\"" + telefono(3) + "\"}", 200);
        assertThat(sugerencias).hasSize(2);
        assertThat(sugerencias.get(0).get("coincidencia").asText()).isEqualTo("TELEFONO");
        assertThat(contar("cliente WHERE nombre LIKE '%Ruiz " + sufijo + "'")).isEqualTo(2); // dos fichas, nada fusionado
    }

    @Test
    void unProveedorQueCompraNoSeDuplicaComoClienteYSusComprasVanASuHistorialSinTocarLiquidaciones() throws Exception {
        String dni = String.format("8%07d", Integer.parseInt(sufijo));
        UUID proveedor = proveedorService.crear(new ProveedorRequest("Juan Proveedor " + sufijo, dni, "+51" + telefono(4).substring(3), null)).id();
        // registrarlo como cliente con su DNI: 409 (es proveedor)
        enviar(post("/api/clientes"), ventas, registro("Juan " + sufijo, telefono(5), true, false).replace("\"dni\":null", "\"dni\":\"" + dni + "\""), 409);
        JsonNode sug = enviar(post("/api/compradores/sugerencias"), ventas, "{\"dni\":\"" + dni + "\"}", 200);
        assertThat(sug.get(0).get("tipo").asText()).isEqualTo("PROVEEDOR");
        assertThat(sug.get(0).get("documento").asText()).doesNotContain(dni.substring(0, 4));

        long pagos = contar("pago");
        // canal PROVEEDOR: necesita al proveedor; con un cliente o sin comprador, 400
        vender(ventas, "PROVEEDOR", null, 1, 400);
        JsonNode v = vender(ventas, "PROVEEDOR", "{\"tipo\":\"PROVEEDOR\",\"id\":\"" + proveedor + "\"}", 2, 201);
        assertThat(v.get("total").decimalValue()).isEqualByComparingTo("16.00"); // precio de PROVEEDOR (8.00)
        assertThat(v.get("comprador").get("tipo").asText()).isEqualTo("PROVEEDOR");
        // tambien puede comprar a precio PUBLICO: se conserva el canal usado
        JsonNode publico = vender(ventas, "PUBLICO", "{\"tipo\":\"PROVEEDOR\",\"id\":\"" + proveedor + "\"}", 1, 201);
        assertThat(publico.get("tipoCliente").asText()).isEqualTo("PUBLICO");
        assertThat(publico.get("total").decimalValue()).isEqualByComparingTo("10.00");

        JsonNode resumen = json.readTree(leer(admin, "/api/proveedores/" + proveedor + "/historial/resumen"));
        assertThat(resumen.get("secciones").toString()).contains("COMPRAS");
        assertThat(resumen.get("compras").get("totalCompras").asLong()).isEqualTo(2);
        assertThat(resumen.get("compras").get("montoTotal").decimalValue()).isEqualByComparingTo("26.00");
        assertThat(json.readTree(leer(admin, "/api/proveedores/" + proveedor + "/historial/compras")).get("contenido")).hasSize(2);
        assertThat(contar("pago")).isEqualTo(pagos); // sin liquidaciones nuevas ni descuentos
        assertThat(contar("cliente WHERE nombre LIKE '%" + sufijo + "'")).isZero();
    }

    @Test
    void mayoristaSoloConUnClienteQueAdminHabilito() throws Exception {
        JsonNode c = enviar(post("/api/clientes"), ventas, registro("Bodega " + sufijo, telefono(6), true, false), 201);
        String ref = "{\"tipo\":\"CLIENTE\",\"id\":\"" + c.get("id").asText() + "\"}";
        vender(ventas, "MAYORISTA", null, 1, 400);
        vender(ventas, "MAYORISTA", ref, 1, 400);
        enviar(put("/api/clientes/" + c.get("id").asText() + "/mayorista"), ventas, "{\"mayorista\":true,\"motivo\":\"x\"}", 403);
        enviar(put("/api/clientes/" + c.get("id").asText() + "/mayorista"), admin, "{\"mayorista\":true,\"motivo\":\"Compra por mayor\"}", 200);
        JsonNode v = vender(ventas, "MAYORISTA", ref, 3, 201);
        assertThat(v.get("total").decimalValue()).isEqualByComparingTo("27.00"); // precio MAYORISTA (9.00)
        assertThat(v.get("comprador").get("nombre").asText()).isEqualTo("Bodega " + sufijo);

        // la copia historica no cambia si se edita la ficha
        enviar(put("/api/clientes/" + c.get("id").asText()), admin, "{\"nombre\":\"Bodega Renombrada " + sufijo + "\"}", 200);
        JsonNode otra = json.readTree(leer(admin, "/api/ventas/" + v.get("id").asText()));
        assertThat(otra.get("comprador").get("nombre").asText()).isEqualTo("Bodega " + sufijo);
        JsonNode historial = json.readTree(leer(ventas, "/api/clientes/" + c.get("id").asText() + "/historial"));
        assertThat(historial.get("compras").get("totalCompras").asLong()).isEqualTo(1);
        assertThat(historial.get("compras").get("anio").asInt()).isEqualTo(hoy.getYear());
    }

    // ------------------------------------------------------------------ reservas

    @Test
    void elRetiroUsaElCompradorConfirmadoEnLaReservaSinPedirDatosYSinCrearFichas() throws Exception {
        JsonNode c = enviar(post("/api/clientes"), ventas, registro("Marta " + sufijo, telefono(7), true, false), 201);
        UUID reserva = reservaConfirmada("Marta R.", telefono(7), 2);
        enviar(put("/api/reservas/" + reserva + "/comprador"), ventas, "{\"tipo\":\"CLIENTE\",\"id\":\"" + c.get("id").asText() + "\"}", 200);
        long fichas = contar("cliente");
        JsonNode venta = enviar(post("/api/reservas/" + reserva + "/retirar"), ventas, "{\"uuidCliente\":\"" + UUID.randomUUID() + "\"}", 201);
        assertThat(venta.get("comprador").get("id").asText()).isEqualTo(c.get("id").asText());
        assertThat(venta.get("reservaId").asText()).isEqualTo(reserva.toString());
        assertThat(venta.get("tipoCliente").asText()).isEqualTo("PUBLICO");
        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(contar("cliente")).isEqualTo(fichas);
        assertThat(json.readTree(leer(ventas, "/api/clientes/" + c.get("id").asText() + "/historial")).get("compras").get("desdeReserva").asLong()).isEqualTo(1);
    }

    @Test
    void unaReservaSinCompradorVinculadoSeRetiraComoAntesYNoCreaUnaFicha() throws Exception {
        long fichas = contar("cliente");
        UUID reserva = reservaConfirmada("Pedro Web " + sufijo, telefono(8), 1);
        JsonNode venta = enviar(post("/api/reservas/" + reserva + "/retirar"), ventas, "{\"uuidCliente\":\"" + UUID.randomUUID() + "\"}", 201);
        assertThat(venta.get("comprador").get("tipo").isNull()).isTrue();
        assertThat(venta.get("comprador").get("nombre").asText()).isEqualTo("Pedro Web " + sufijo); // el nombre que dio al reservar
        assertThat(contar("cliente")).isEqualTo(fichas);
        assertThat(jdbc.queryForObject("SELECT estado FROM reserva WHERE id = ?", String.class, reserva)).isEqualTo("RETIRADA");
    }

    @Test
    void vincularYDesvincularElCompradorDejaConstanciaEnLaReserva() throws Exception {
        JsonNode c = enviar(post("/api/clientes"), ventas, registro("Lidia " + sufijo, telefono(9), true, false), 201);
        UUID reserva = reservaConfirmada("Lidia", telefono(9), 1);
        JsonNode r = enviar(put("/api/reservas/" + reserva + "/comprador"), ventas, "{\"tipo\":\"CLIENTE\",\"id\":\"" + c.get("id").asText() + "\"}", 200);
        assertThat(r.get("comprador").get("nombre").asText()).isEqualTo("Lidia " + sufijo);
        JsonNode sin = enviar(delete("/api/reservas/" + reserva + "/comprador"), ventas, null, 200);
        assertThat(sin.get("comprador").isNull()).isTrue();
        assertThat(sin.get("eventos").toString()).contains("COMPRADOR_VINCULADO").contains("COMPRADOR_DESVINCULADO");
    }

    // ------------------------------------------------------------------ fusion manual

    @Test
    void laFusionEsManualConMotivoMueveLasComprasYRechazaDocumentosDistintos() throws Exception {
        JsonNode a = enviar(post("/api/clientes"), ventas, registro("Duplicada A " + sufijo, telefono(10), true, false), 201);
        JsonNode b = enviar(post("/api/clientes"), ventas, registro("Duplicada B " + sufijo, telefono(11), true, false), 201);
        vender(ventas, "PUBLICO", "{\"tipo\":\"CLIENTE\",\"id\":\"" + a.get("id").asText() + "\"}", 1, 201);
        enviar(post("/api/clientes/" + a.get("id").asText() + "/fusionar"), ventas, "{\"destinoId\":\"" + b.get("id").asText() + "\",\"motivo\":\"x\"}", 403);
        JsonNode f = enviar(post("/api/clientes/" + a.get("id").asText() + "/fusionar"), admin,
                "{\"destinoId\":\"" + b.get("id").asText() + "\",\"motivo\":\"Misma persona, confirmado en caja\"}", 200);
        assertThat(f.get("ventasMovidas").asInt()).isEqualTo(1);
        assertThat(f.get("telefonosAgregados").asInt()).isEqualTo(1);
        JsonNode origen = json.readTree(leer(admin, "/api/clientes/" + a.get("id").asText()));
        assertThat(origen.get("activo").asBoolean()).isFalse();
        assertThat(origen.get("fusionadoEnId").asText()).isEqualTo(b.get("id").asText());
        assertThat(json.readTree(leer(admin, "/api/clientes/" + b.get("id").asText() + "/historial")).get("compras").get("totalCompras").asLong()).isEqualTo(1);
        assertThat(contar("cliente_fusion WHERE motivo = 'Misma persona, confirmado en caja'")).isEqualTo(1);

        // DNI distintos: no son la misma persona
        String dniC = String.format("6%07d", Integer.parseInt(sufijo)), dniD = String.format("5%07d", Integer.parseInt(sufijo));
        JsonNode cc = enviar(post("/api/clientes"), ventas, registro("Con DNI C " + sufijo, telefono(12), true, false).replace("\"dni\":null", "\"dni\":\"" + dniC + "\""), 201);
        JsonNode dd = enviar(post("/api/clientes"), ventas, registro("Con DNI D " + sufijo, telefono(13), true, false).replace("\"dni\":null", "\"dni\":\"" + dniD + "\""), 201);
        enviar(post("/api/clientes/" + cc.get("id").asText() + "/fusionar"), admin, "{\"destinoId\":\"" + dd.get("id").asText() + "\",\"motivo\":\"prueba\"}", 409);
    }

    // ------------------------------------------------------------------ refactor: tipo, canal, reservas en la tabla, exportacion

    @Test
    void elTipoYElCanalSeValidanAlRegistrarYNoSeConfunden() throws Exception {
        // por defecto: regular y canal PUBLICO
        JsonNode regular = enviar(post("/api/clientes"), ventas, registro("Regular " + sufijo, telefono(20), true, false), 201);
        assertThat(regular.get("mayorista").asBoolean()).isFalse();
        assertThat(regular.get("canalHabitual").asText()).isEqualTo("PUBLICO");

        String base = registro("Mayorista " + sufijo, telefono(21), true, false);
        // VENTAS no habilita mayoristas; ADMIN si, pero con motivo
        enviar(post("/api/clientes"), ventas, conTipo(base, true, "Compra por mayor", "MAYORISTA"), 400);
        enviar(post("/api/clientes"), admin, conTipo(base, true, null, "MAYORISTA"), 400);
        // el canal MAYORISTA exige ser mayorista; PROVEEDOR nunca es de un cliente; valores inventados se rechazan
        enviar(post("/api/clientes"), admin, conTipo(base, false, null, "MAYORISTA"), 400);
        enviar(post("/api/clientes"), admin, conTipo(base, false, null, "PROVEEDOR"), 400);
        enviar(post("/api/clientes"), admin, conTipo(base, false, null, "VIP"), 400);
        assertThat(contar("cliente WHERE nombre = 'Mayorista " + sufijo + "'")).isZero();

        JsonNode m = enviar(post("/api/clientes"), admin, conTipo(base, true, "Compra por mayor para su bodega", "MAYORISTA"), 201);
        assertThat(m.get("mayorista").asBoolean()).isTrue();
        assertThat(m.get("canalHabitual").asText()).isEqualTo("MAYORISTA");
        assertThat(jdbc.queryForObject("SELECT mayorista_motivo FROM cliente WHERE id = ?", String.class, UUID.fromString(m.get("id").asText())))
                .isEqualTo("Compra por mayor para su bodega");
        // la sugerencia lleva el canal que el panel propone; la venta MAYORISTA funciona sin pasos extra
        JsonNode sug = enviar(post("/api/compradores/sugerencias"), ventas, "{\"telefono\":\"" + telefono(21) + "\"}", 200);
        assertThat(sug.get(0).get("canalHabitual").asText()).isEqualTo("MAYORISTA");
        vender(ventas, "MAYORISTA", "{\"tipo\":\"CLIENTE\",\"id\":\"" + m.get("id").asText() + "\"}", 1, 201);
        // al quitarle la condicion de mayorista, el canal propuesto vuelve a PUBLICO
        JsonNode quitado = enviar(put("/api/clientes/" + m.get("id").asText() + "/mayorista"), admin, "{\"mayorista\":false,\"motivo\":\"Ya no compra por mayor\"}", 200);
        assertThat(quitado.get("canalHabitual").asText()).isEqualTo("PUBLICO");
    }

    @Test
    void laTablaMuestraElResumenDeReservasYSeActualizaTrasCadaCambio() throws Exception {
        JsonNode c = enviar(post("/api/clientes"), ventas, registro("Con reservas " + sufijo, telefono(22), true, false), 201);
        String id = c.get("id").asText();
        assertThat(c.get("reservas").get("activas").asInt()).isZero();

        UUID r1 = reservaConfirmada("Con reservas", telefono(22), 1);
        UUID r2 = reservaConfirmada("Con reservas", telefono(22), 1);
        // una reserva con el mismo telefono pero SIN vincular no se cuenta (el telefono solo sugiere)
        assertThat(filaDe(id).get("reservas").get("total").asInt()).isZero();
        enviar(put("/api/reservas/" + r1 + "/comprador"), ventas, "{\"tipo\":\"CLIENTE\",\"id\":\"" + id + "\"}", 200);
        enviar(put("/api/reservas/" + r2 + "/comprador"), ventas, "{\"tipo\":\"CLIENTE\",\"id\":\"" + id + "\"}", 200);
        JsonNode resumen = filaDe(id).get("reservas");
        assertThat(resumen.get("activas").asInt()).isEqualTo(2);
        assertThat(resumen.get("confirmadas").asInt()).isEqualTo(2);
        assertThat(resumen.get("proximoRetiro").asText()).isEqualTo(hoy.toString());

        // retirar una: deja de estar activa pero sigue en el total
        enviar(post("/api/reservas/" + r1 + "/retirar"), ventas, "{\"uuidCliente\":\"" + UUID.randomUUID() + "\"}", 201);
        resumen = filaDe(id).get("reservas");
        assertThat(resumen.get("activas").asInt()).isEqualTo(1);
        assertThat(resumen.get("total").asInt()).isEqualTo(2);
        // una confirmada cuya fecha ya paso cuenta como vencida (mismo estado efectivo que la pantalla de reservas)
        jdbc.update("UPDATE reserva SET fecha_retiro = ? WHERE id = ?", hoy.minusDays(1), r2);
        resumen = json.readTree(leer(ventas, "/api/clientes/" + id)).get("reservas");
        assertThat(resumen.get("activas").asInt()).isZero();
        assertThat(resumen.get("proximoRetiro").isNull()).isTrue();
    }

    @Test
    void laExportacionDeClientesRespetaLosFiltrosYEsSoloDeAdmin() throws Exception {
        enviar(post("/api/clientes"), ventas, registro("Exporta Uno " + sufijo, telefono(23), true, true), 201);
        enviar(post("/api/clientes"), ventas, registro("Exporta Dos " + sufijo, telefono(24), true, false), 201);
        mvc.perform(get("/api/exportaciones/clientes").header("Authorization", ventas)).andExpect(status().isForbidden());

        java.util.List<String> todos = nombresExportados("busqueda=Exporta+" + sufijo);
        assertThat(todos).containsExactlyInAnyOrder("Exporta Uno " + sufijo, "Exporta Dos " + sufijo);
        java.util.List<String> conPromos = nombresExportados("conComunicaciones=true&busqueda=Exporta+" + sufijo);
        assertThat(conPromos).containsExactly("Exporta Uno " + sufijo);
    }

    private JsonNode filaDe(String id) throws Exception {
        for (JsonNode fila : listar("/api/clientes?busqueda=" + sufijo + "&size=100", ventas).get("contenido")) {
            if (fila.get("id").asText().equals(id)) return fila;
        }
        throw new AssertionError("La ficha " + id + " no aparece en el padrón");
    }

    private java.util.List<String> nombresExportados(String query) throws Exception {
        byte[] xlsx = mvc.perform(get("/api/exportaciones/clientes?" + query).header("Authorization", admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        java.util.List<String> nombres = new java.util.ArrayList<>();
        try (var libro = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
            var hoja = libro.getSheetAt(0);
            int columna = -1, encabezados = -1;
            for (int f = 0; f <= hoja.getLastRowNum() && columna < 0; f++) {
                var fila = hoja.getRow(f);
                if (fila == null) continue;
                for (var celda : fila) {
                    if ("Cliente".equals(celda.toString())) { columna = celda.getColumnIndex(); encabezados = f; }
                }
            }
            assertThat(columna).isNotNegative();
            for (int f = encabezados + 1; f <= hoja.getLastRowNum(); f++) {
                var fila = hoja.getRow(f);
                if (fila != null && fila.getCell(columna) != null && fila.getCell(columna).toString().startsWith("Exporta ")
                        && fila.getCell(columna).toString().endsWith(" " + sufijo)) {
                    nombres.add(fila.getCell(columna).toString());
                }
            }
        }
        return nombres;
    }

    private static String conTipo(String registro, boolean mayorista, String motivo, String canal) {
        return registro.substring(0, registro.length() - 1) + ",\"mayorista\":" + mayorista + ",\"motivoMayorista\":"
                + (motivo == null ? "null" : "\"" + motivo + "\"") + ",\"canalHabitual\":\"" + canal + "\"}";
    }

    // ------------------------------------------------------------------ movil y avisos

    @Test
    void laAppMovilSigueVendiendoSinCompradorEnCualquierCanal() throws Exception {
        for (String canal : new String[]{"PUBLICO", "MAYORISTA", "PROVEEDOR"}) {
            MvcResult r = mvc.perform(post("/api/ventas").header("Authorization", ventas).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"uuidCliente\":\"" + UUID.randomUUID() + "\",\"fecha\":\"" + hoy + "\",\"tipoCliente\":\"" + canal
                                    + "\",\"presentacionId\":\"" + queso.getId() + "\",\"cantidad\":1,\"precioUnitario\":10}"))
                    .andExpect(status().isCreated()).andReturn();
            String cuerpo = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(cuerpo).doesNotContain("comprador");
        }
    }

    @Test
    void elAvisoDeDatosEsPublicoYDiceQueFaltaSinInventarDatos() throws Exception {
        MvcResult r = mvc.perform(get("/api/catalogo/privacidad")).andExpect(status().isOk()).andReturn();
        JsonNode aviso = json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(aviso.get("completo").asBoolean()).isFalse();
        assertThat(aviso.get("faltantes").size()).isPositive();
        assertThat(aviso.get("avisoReserva").toString()).contains("gestionar esta reserva");
        assertThat(aviso.get("responsable").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ auxiliares

    private String telefono(int n) {
        return String.format("+519%04d%04d", Integer.parseInt(sufijo) % 10000, n);
    }

    private static String registro(String nombre, String telefono, boolean autoriza, boolean promociones) {
        return "{\"nombre\":\"" + nombre + "\",\"telefono\":\"" + telefono + "\",\"dni\":null,"
                + "\"autorizacionRegistro\":{\"otorgada\":" + autoriza + ",\"forma\":\"VERBAL_PRESENCIAL\"},"
                + "\"comunicaciones\":{\"otorgada\":" + promociones + ",\"medios\":[\"WHATSAPP\"],\"forma\":\"VERBAL_PRESENCIAL\"},"
                + "\"avisoVersion\":\"2026-10-24\",\"confirmadoSinCoincidencia\":false}";
    }

    private JsonNode vender(String token, String canal, String comprador, int cantidad, int estado) throws Exception {
        String cuerpo = "{\"uuidCliente\":\"" + UUID.randomUUID() + "\",\"fecha\":\"" + hoy + "\",\"tipoCliente\":\"" + canal + "\",\"detalles\":[{\"presentacionId\":\""
                + queso.getId() + "\",\"cantidad\":" + cantidad + "}]" + (comprador == null ? "" : ",\"comprador\":" + comprador) + "}";
        return enviar(post("/api/ventas/carrito"), token, cuerpo, estado);
    }

    private JsonNode enviar(MockHttpServletRequestBuilder req, String token, String cuerpo, int estado) throws Exception {
        req.header("Authorization", token);
        if (cuerpo != null) req.contentType(MediaType.APPLICATION_JSON).content(cuerpo.getBytes(StandardCharsets.UTF_8));
        MvcResult r = mvc.perform(req).andExpect(status().is(estado)).andReturn();
        String texto = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return texto.isEmpty() ? null : json.readTree(texto);
    }

    private String leer(String token, String ruta) throws Exception {
        return mvc.perform(get(ruta).header("Authorization", token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode listar(String ruta, String token) throws Exception {
        return json.readTree(leer(token, ruta));
    }

    private long contar(String tablaYFiltro) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tablaYFiltro, Long.class);
    }

    /** Reserva CONFIRMADA de hoy por la web (insertada directamente), con el precio PUBLICO vigente cotizado. */
    private UUID reservaConfirmada(String nombre, String telefono, int cantidad) {
        UUID id = UUID.randomUUID();
        LocalDateTime ahora = LocalDateTime.now(LIMA);
        jdbc.update("INSERT INTO reserva (id, codigo, uuid_cliente, estado, nombre_cliente, telefono, fecha_retiro, fecha_maxima_retiro, creado_en, expira_en, total, "
                        + "requiere_aceptacion) VALUES (?, ?, ?, 'CONFIRMADA', ?, ?, ?, ?, ?, ?, ?, false)",
                id, ("R" + id.toString().replace("-", "")).substring(0, 12).toUpperCase(), UUID.randomUUID().toString(), nombre, telefono, hoy, hoy.plusDays(3),
                ahora, ahora.plusHours(24), new BigDecimal("10.00").multiply(BigDecimal.valueOf(cantidad)));
        jdbc.update("INSERT INTO reserva_detalle (reserva_id, presentacion_id, cantidad, precio_unitario) VALUES (?, ?, ?, 10.00)", id, queso.getId(), cantidad);
        return id;
    }

    private Presentacion presentacionConStock(int stock) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null));
        try {
            String s = String.valueOf(System.nanoTime());
            Familia f = new Familia();
            f.setNombre("Familia H " + s);
            Producto p = new Producto();
            p.setNombre("Queso H " + s);
            p.setFamilia(familiaRepository.save(f));
            p = productoRepository.save(p);
            Presentacion pr = new Presentacion();
            pr.setNombre("Pieza");
            pr.setProducto(p);
            pr.setUnidadConteo("pieza");
            pr = presentacionRepository.save(pr);
            LocalDate desde = LocalDate.of(2000, 1, 1);
            precioQuesoService.crear(new PrecioQuesoRequest(pr.getId(), "PUBLICO", new BigDecimal("10.00"), desde));
            precioQuesoService.crear(new PrecioQuesoRequest(pr.getId(), "MAYORISTA", new BigDecimal("9.00"), desde));
            precioQuesoService.crear(new PrecioQuesoRequest(pr.getId(), "PROVEEDOR", new BigDecimal("8.00"), desde));
            ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), stock, "Stock inicial de la prueba"));
            return pr;
        } finally {
            SecurityContextHolder.clearContext();
        }
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
        u.setNombre("Usuario " + rolNombre + " " + sufijo);
        u.setEmail(rolNombre.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@planta.com");
        u.setPasswordHash(passwordEncoder.encode("Clave1234"));
        u.setRol(rol);
        u.setActivo(true);
        return usuarioRepository.save(u);
    }
}
