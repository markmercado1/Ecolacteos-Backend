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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pagos efectivos contra PostgreSQL real y la cadena de seguridad real (login con JWT, MockMvc):
 * - un adelanto (pago sin aplicar) se aplica despues; los pagos parciales llevan la liquidacion a PAGADO; el saldo se deriva, no se guarda;
 * - un pago aplicado nunca supera lo que le queda ni el saldo de la liquidacion (y si falla, no se guarda nada);
 * - reenviar el mismo uuidCliente o la misma operacion bancaria no crea otro pago, ni siquiera si llegan a la vez;
 * - aplicaciones simultaneas a la misma liquidacion o al mismo pago nunca pasan del tope;
 * - anular conserva la fila y devuelve el saldo; solo ADMIN anula;
 * - los ajustes de litros y los precios de calidad entran en el saldo; ADMIN y RECEPCION operan, los demas roles no; sin DNI ni telefono.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PagosEfectivosIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    private static final AtomicInteger PRUEBAS = new AtomicInteger(8000);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private ProveedorService proveedorService;
    @Autowired private RegistroAcopioService registroAcopioService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private DataSource dataSource;

    private int prueba;
    private String sufijo;
    private Usuario acopiador;
    private UUID unidadId;
    private LocalDate hoy;
    private String admin;
    private String recepcion;

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
        unidad.setPlaca("PE" + sufijo);
        unidad.setResponsable(acopiador);
        unidadId = unidadRepository.save(unidad).getId();
        admin = token("admin@planta.com", "Admin1234");
        recepcion = tokenDeRol("RECEPCION");
    }

    // ------------------------------------------------------------------ adelanto, pagos parciales y saldo derivado

    @Test
    void unAdelantoSeAplicaDespuesYLosPagosParcialesLlevanLaLiquidacionAPagado() throws Exception {
        UUID proveedorId = proveedor();
        seisEntregasDe100Litros(proveedorId);
        UUID pagoId = pagoDe(proveedorId);

        // adelanto: efectivo sin aplicar, registrado por RECEPCION
        JsonNode adelanto = registrar(recepcion, proveedorId, "EFECTIVO", "250.00", null, null, null, 201);
        assertThat(adelanto.get("estado").asText()).isEqualTo("REGISTRADO");
        assertThat(adelanto.get("aplicado").decimalValue()).isEqualByComparingTo("0");
        assertThat(adelanto.get("sinAplicar").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(adelanto.get("usuarioNombre").asText()).isNotBlank();

        JsonNode saldo = saldoProveedor(recepcion, proveedorId);
        assertThat(saldo.get("liquidaciones")).hasSize(1);
        assertThat(saldo.get("liquidaciones").get(0).get("saldo").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(saldo.get("liquidaciones").get(0).get("estado").asText()).isEqualTo("PENDIENTE");
        assertThat(saldo.get("adelantosSinAplicar").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(saldo.get("saldoNeto").decimalValue()).isEqualByComparingTo("350.00");

        // el adelanto se aplica a la liquidacion
        JsonNode aplicado = aplicar(recepcion, UUID.fromString(adelanto.get("id").asText()), pagoId, "250.00", 200);
        assertThat(aplicado.get("aplicado").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(aplicado.get("sinAplicar").decimalValue()).isEqualByComparingTo("0");
        assertThat(aplicado.get("aplicaciones")).hasSize(1);
        assertThat(aplicado.get("aplicaciones").get(0).get("pagoId").asText()).isEqualTo(pagoId.toString());

        JsonNode s1 = saldoDe(recepcion, pagoId);
        assertThat(s1.get("saldo").decimalValue()).isEqualByComparingTo("350.00");
        assertThat(s1.get("estado").asText()).isEqualTo("PARCIAL");
        assertThat(saldoProveedor(recepcion, proveedorId).get("adelantosSinAplicar").decimalValue()).isEqualByComparingTo("0");

        // el resto, por transferencia aplicada en el mismo registro
        JsonNode resto = registrar(recepcion, proveedorId, "TRANSFERENCIA", "350.00", "BCP", "OP-" + sufijo, ap(new String[]{pagoId.toString(), "350.00"}), 201);
        assertThat(resto.get("aplicado").decimalValue()).isEqualByComparingTo("350.00");
        JsonNode s2 = saldoDe(recepcion, pagoId);
        assertThat(s2.get("saldo").decimalValue()).isEqualByComparingTo("0");
        assertThat(s2.get("estado").asText()).isEqualTo("PAGADO");
        assertThat(s2.get("aplicado").decimalValue()).isEqualByComparingTo("600.00");

        // la liquidacion (la deuda) no se toca: el desembolso es otra cosa
        assertThat(jdbc.queryForObject("SELECT total FROM pago WHERE id = ?", BigDecimal.class, pagoId)).isEqualByComparingTo("600.00");
        assertThat(jdbc.queryForObject("SELECT comprobante_generado FROM pago WHERE id = ?", Boolean.class, pagoId)).isFalse();
    }

    // ------------------------------------------------------------------ topes

    @Test
    void unPagoAplicadoNuncaSuperaLoQueLeQuedaNiElSaldoYSiFallaNoSeGuardaNada() throws Exception {
        UUID proveedorId = proveedor();
        seisEntregasDe100Litros(proveedorId);
        UUID pagoId = pagoDe(proveedorId);

        // registrar y aplicar mas que el saldo (600): rechazado y NO se crea ni el pago
        String uuid = UUID.randomUUID().toString();
        mvc.perform(withJson(post("/api/pagos-proveedor").header("Authorization", admin),
                cuerpo(uuid, proveedorId, "EFECTIVO", "700.00", null, null, ap(new String[]{pagoId.toString(), "601.00"}))))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pago_proveedor WHERE uuid_cliente = ?", Integer.class, uuid)).isZero();

        // aplicar mas de lo que le queda al pago
        UUID cinco = UUID.fromString(registrar(admin, proveedorId, "EFECTIVO", "500.00", null, null, null, 201).get("id").asText());
        aplicar(admin, cinco, pagoId, "501.00", 409);
        assertThat(aplicaciones(cinco)).isZero();
        aplicar(admin, cinco, pagoId, "500.00", 200);
        aplicar(admin, cinco, pagoId, "0.01", 409); // ya no le queda nada

        // aplicar mas que el saldo de la liquidacion (le faltan 100)
        UUID mil = UUID.fromString(registrar(admin, proveedorId, "EFECTIVO", "1000.00", null, null, null, 201).get("id").asText());
        aplicar(admin, mil, pagoId, "100.01", 409);
        aplicar(admin, mil, pagoId, "100.00", 200);
        assertThat(saldoDe(admin, pagoId).get("estado").asText()).isEqualTo("PAGADO");
        aplicar(admin, mil, pagoId, "0.01", 409); // saldo cero: no hay a que aplicar
        assertThat(aplicaciones(mil)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ idempotencia

    @Test
    void reenviarElMismoUuidODeLaMismaOperacionNoCreaOtroPago() throws Exception {
        UUID proveedorId = proveedor();
        String uuid = UUID.randomUUID().toString();

        JsonNode primero = registrarCon(admin, uuid, proveedorId, "TRANSFERENCIA", "120.00", "BCP", "OP-A" + sufijo, null, 201);
        JsonNode reenvio = registrarCon(admin, uuid, proveedorId, "TRANSFERENCIA", "120.00", "BCP", "OP-A" + sufijo, null, 200);
        assertThat(reenvio.get("id").asText()).isEqualTo(primero.get("id").asText());
        assertThat(pagosDe(proveedorId)).isEqualTo(1);

        // mismo uuid con otros datos: conflicto, no se pisa
        registrarCon(admin, uuid, proveedorId, "TRANSFERENCIA", "999.00", "BCP", "OP-A" + sufijo, null, 409);
        assertThat(pagosDe(proveedorId)).isEqualTo(1);

        // la misma operacion bancaria con otro uuid: si los datos son los mismos es el mismo pago; si no, conflicto
        JsonNode mismaOperacion = registrarCon(admin, UUID.randomUUID().toString(), proveedorId, "TRANSFERENCIA", "120.00", " bcp ", "op-a" + sufijo, null, 200);
        assertThat(mismaOperacion.get("id").asText()).isEqualTo(primero.get("id").asText());
        registrarCon(admin, UUID.randomUUID().toString(), proveedorId, "TRANSFERENCIA", "121.00", "BCP", "OP-A" + sufijo, null, 409);
        UUID otro = proveedor();
        registrarCon(admin, UUID.randomUUID().toString(), otro, "TRANSFERENCIA", "120.00", "BCP", "OP-A" + sufijo, null, 409);
        assertThat(pagosDe(proveedorId)).isEqualTo(1);
        assertThat(pagosDe(otro)).isZero();

        // al anular el pago, su operacion queda libre y puede registrarse de nuevo
        mvc.perform(withJson(post("/api/pagos-proveedor/" + primero.get("id").asText() + "/anular").header("Authorization", admin), "{\"motivo\":\"Operacion mal digitada\"}"))
                .andExpect(status().isOk());
        registrarCon(admin, UUID.randomUUID().toString(), proveedorId, "TRANSFERENCIA", "120.00", "BCP", "OP-A" + sufijo, null, 201);
        assertThat(pagosDe(proveedorId)).isEqualTo(2);
    }

    @Test
    void reenviosSimultaneosDelMismoPagoCreanUnSoloPago() throws Exception {
        UUID proveedorId = proveedor();
        String uuid = UUID.randomUUID().toString();
        List<Integer> estados = enParalelo(8, i -> mvc.perform(withJson(post("/api/pagos-proveedor").header("Authorization", admin),
                cuerpo(uuid, proveedorId, "EFECTIVO", "80.00", null, null, null))).andReturn().getResponse().getStatus());

        assertThat(estados).allMatch(e -> e == 201 || e == 200);
        assertThat(estados.stream().filter(e -> e == 201).count()).isEqualTo(1);
        assertThat(pagosDe(proveedorId)).isEqualTo(1);

        // misma operacion bancaria con uuid distintos, a la vez
        UUID otro = proveedor();
        String op = "OP-B" + sufijo;
        List<Integer> estados2 = enParalelo(8, i -> mvc.perform(withJson(post("/api/pagos-proveedor").header("Authorization", admin),
                cuerpo(UUID.randomUUID().toString(), otro, "TRANSFERENCIA", "55.00", "BBVA", op, null))).andReturn().getResponse().getStatus());
        assertThat(estados2).allMatch(e -> e == 201 || e == 200);
        assertThat(estados2.stream().filter(e -> e == 201).count()).isEqualTo(1);
        assertThat(pagosDe(otro)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ concurrencia

    @Test
    void aplicacionesSimultaneasALaMismaLiquidacionNuncaPasanDelSaldo() throws Exception {
        UUID proveedorId = proveedor();
        seisEntregasDe100Litros(proveedorId);
        UUID pagoId = pagoDe(proveedorId); // saldo 600
        List<UUID> pagos = new ArrayList<>();
        for (int i = 0; i < 6; i++) { // seis pagos de 200 sin aplicar
            pagos.add(UUID.fromString(registrar(admin, proveedorId, "EFECTIVO", "200.00", null, null, null, 201).get("id").asText()));
        }

        List<Integer> estados = enParalelo(6, i -> mvc.perform(withJson(post("/api/pagos-proveedor/" + pagos.get(i) + "/aplicaciones").header("Authorization", admin),
                aplicacionesJson(pagoId, "200.00"))).andReturn().getResponse().getStatus());

        assertThat(estados.stream().filter(e -> e == 200).count()).isEqualTo(3); // 3 x 200 = 600
        assertThat(estados.stream().filter(e -> e == 409).count()).isEqualTo(3);
        JsonNode saldo = saldoDe(admin, pagoId);
        assertThat(saldo.get("aplicado").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(saldo.get("saldo").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void aplicacionesSimultaneasDelMismoPagoNuncaPasanDeSuMonto() throws Exception {
        UUID proveedorId = proveedor();
        seisEntregasDe100Litros(proveedorId);
        UUID pagoId = pagoDe(proveedorId);
        UUID cien = UUID.fromString(registrar(admin, proveedorId, "EFECTIVO", "100.00", null, null, null, 201).get("id").asText());

        List<Integer> estados = enParalelo(5, i -> mvc.perform(withJson(post("/api/pagos-proveedor/" + cien + "/aplicaciones").header("Authorization", admin),
                aplicacionesJson(pagoId, "30.00"))).andReturn().getResponse().getStatus());

        assertThat(estados.stream().filter(e -> e == 200).count()).isEqualTo(3); // 3 x 30 = 90 <= 100
        assertThat(estados.stream().filter(e -> e == 409).count()).isEqualTo(2);
        assertThat(aplicaciones(cien)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT sum(monto) FROM pago_proveedor_aplicacion WHERE pago_proveedor_id = ?", BigDecimal.class, cien)).isEqualByComparingTo("90.00");
    }


    @Test
    void aplicarEsperaElBloqueoDeLaLiquidacionYAnularElDelPago() throws Exception {
        UUID proveedorId = proveedor();
        seisEntregasDe100Litros(proveedorId);
        UUID pagoId = pagoDe(proveedorId);
        UUID pagoProv = UUID.fromString(registrar(admin, proveedorId, "EFECTIVO", "100.00", null, null, null, 201).get("id").asText());

        // otra transaccion tiene tomada la fila de la LIQUIDACION: aplicar tiene que esperar a que la suelte
        assertThat(estadoMientrasOtraTransaccionBloquea("pago", pagoId,
                () -> mvc.perform(withJson(post("/api/pagos-proveedor/" + pagoProv + "/aplicaciones").header("Authorization", admin), aplicacionesJson(pagoId, "40.00")))
                        .andReturn().getResponse().getStatus())).isEqualTo(200);

        // lo mismo con la fila del PAGO: aplicar y anular esperan
        assertThat(estadoMientrasOtraTransaccionBloquea("pago_proveedor", pagoProv,
                () -> mvc.perform(withJson(post("/api/pagos-proveedor/" + pagoProv + "/aplicaciones").header("Authorization", admin), aplicacionesJson(pagoId, "10.00")))
                        .andReturn().getResponse().getStatus())).isEqualTo(200);
        assertThat(estadoMientrasOtraTransaccionBloquea("pago_proveedor", pagoProv,
                () -> mvc.perform(withJson(post("/api/pagos-proveedor/" + pagoProv + "/anular").header("Authorization", admin), "{\"motivo\":\"Prueba de bloqueo\"}"))
                        .andReturn().getResponse().getStatus())).isEqualTo(200);
    }

    /**
     * Toma la fila indicada con FOR NO KEY UPDATE (no choca con el FOR KEY SHARE que toma por si sola la clave foranea de un INSERT, pero SI con el
     * FOR UPDATE que pide el servicio: asi solo espera quien de verdad bloquea la fila), lanza la llamada en otro hilo, comprueba que NO termina mientras la fila esta tomada, la suelta y devuelve su estado. */
    private int estadoMientrasOtraTransaccionBloquea(String tabla, UUID id, java.util.concurrent.Callable<Integer> llamada) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT 1 FROM " + tabla + " WHERE id = '" + id + "' FOR NO KEY UPDATE");
            }
            Future<Integer> f = pool.submit(llamada);
            Thread.sleep(1500);
            assertThat(f.isDone()).as("la llamada deberia esperar el bloqueo de " + tabla).isFalse();
            c.commit();
            return f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------------------ anulacion

    @Test
    void anularConservaLaFilaDevuelveElSaldoYSoloLoHaceAdmin() throws Exception {
        UUID proveedorId = proveedor();
        seisEntregasDe100Litros(proveedorId);
        UUID pagoId = pagoDe(proveedorId);
        UUID pagoProv = UUID.fromString(registrar(recepcion, proveedorId, "EFECTIVO", "300.00", null, null,
                ap(new String[]{pagoId.toString(), "300.00"}), 201).get("id").asText());
        assertThat(saldoDe(admin, pagoId).get("saldo").decimalValue()).isEqualByComparingTo("300.00");

        // RECEPCION no anula; sin motivo tampoco
        mvc.perform(withJson(post("/api/pagos-proveedor/" + pagoProv + "/anular").header("Authorization", recepcion), "{\"motivo\":\"Error\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(withJson(post("/api/pagos-proveedor/" + pagoProv + "/anular").header("Authorization", admin), "{\"motivo\":\"   \"}"))
                .andExpect(status().isBadRequest());
        assertThat(estadoDe(pagoProv)).isEqualTo("REGISTRADO");

        JsonNode anulado = json.readTree(mvc.perform(withJson(post("/api/pagos-proveedor/" + pagoProv + "/anular").header("Authorization", admin),
                "{\"motivo\":\"Se registro dos veces\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(anulado.get("estado").asText()).isEqualTo("ANULADO");
        assertThat(anulado.get("anuladoMotivo").asText()).isEqualTo("Se registro dos veces");
        assertThat(anulado.get("anuladoPorNombre").asText()).isNotBlank();
        assertThat(anulado.get("anuladoEn").isNull()).isFalse();
        assertThat(anulado.get("sinAplicar").decimalValue()).isEqualByComparingTo("0"); // anulado: nada aplicable

        // la fila y su aplicacion se conservan; el saldo vuelve a 600
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pago_proveedor WHERE id = ?", Integer.class, pagoProv)).isEqualTo(1);
        assertThat(aplicaciones(pagoProv)).isEqualTo(1);
        JsonNode saldo = saldoDe(admin, pagoId);
        assertThat(saldo.get("saldo").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(saldo.get("aplicado").decimalValue()).isEqualByComparingTo("0");
        assertThat(saldoProveedor(admin, proveedorId).get("adelantosSinAplicar").decimalValue()).isEqualByComparingTo("0"); // un anulado no es adelanto

        // no se anula dos veces ni se aplica un pago anulado
        mvc.perform(withJson(post("/api/pagos-proveedor/" + pagoProv + "/anular").header("Authorization", admin), "{\"motivo\":\"Otra vez\"}"))
                .andExpect(status().isConflict());
        aplicar(admin, pagoProv, pagoId, "10.00", 409);
    }

    // ------------------------------------------------------------------ ajustes de litros y precios de calidad

    @Test
    void losAjustesDeLitrosYLosPreciosDeCalidadEntranEnElSaldo() throws Exception {
        UUID proveedorId = proveedor();
        List<UUID> entregas = seisEntregasDe100Litros(proveedorId);
        UUID analisisId = analisis(entregas.get(0), "S-" + sufijo);
        UUID pagoId = pagoDe(proveedorId);
        assertThat(saldoDe(admin, pagoId).get("totalAPagar").decimalValue()).isEqualByComparingTo("600.00");

        // precio de calidad: 100 L a 0.90 => total reconocido 590.00, y el saldo parte de ahi
        mvc.perform(withJson(post("/api/pagos/" + pagoId + "/lineas/" + entregas.get(0) + "/ajuste-calidad").header("Authorization", admin),
                "{\"precioLitroNuevo\":0.900,\"analisisCalidadId\":\"" + analisisId + "\",\"motivo\":\"Agua\"}")).andExpect(status().isCreated());
        JsonNode s0 = saldoDe(admin, pagoId);
        assertThat(s0.get("totalAPagar").decimalValue()).isEqualByComparingTo("590.00");
        assertThat(s0.get("saldo").decimalValue()).isEqualByComparingTo("590.00");

        // correccion de litros posterior a la liquidacion en otra entrega: 100 -> 95 = -5 L a 1.00 = -5.00
        corregir(admin, entregas.get(1), "95.00", "Medicion en planta");
        JsonNode s1 = saldoDe(admin, pagoId);
        assertThat(s1.get("ajustesLitros").decimalValue()).isEqualByComparingTo("-5.00");
        assertThat(s1.get("totalConAjustes").decimalValue()).isEqualByComparingTo("585.00");
        assertThat(s1.get("saldo").decimalValue()).isEqualByComparingTo("585.00");

        // se paga todo lo que hay
        UUID pagoProv = UUID.fromString(registrar(admin, proveedorId, "EFECTIVO", "585.00", null, null,
                ap(new String[]{pagoId.toString(), "585.00"}), 201).get("id").asText());
        assertThat(saldoDe(admin, pagoId).get("estado").asText()).isEqualTo("PAGADO");

        // otra correccion hacia arriba (95 -> 105 = +10 L) deja un saldo de 10.00 por pagar
        corregir(admin, entregas.get(1), "105.00", "Segunda medicion");
        JsonNode s2 = saldoDe(admin, pagoId);
        assertThat(s2.get("saldo").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(s2.get("estado").asText()).isEqualTo("PARCIAL");

        // y una hacia abajo (105 -> 90 = -15 L) lo deja con 5.00 A FAVOR del proveedor: no se puede aplicar mas
        corregir(admin, entregas.get(1), "90.00", "Tercera medicion");
        JsonNode s3 = saldoDe(admin, pagoId);
        assertThat(s3.get("saldo").decimalValue()).isEqualByComparingTo("-5.00");
        assertThat(s3.get("estado").asText()).isEqualTo("SALDO_A_FAVOR");
        UUID otro = UUID.fromString(registrar(admin, proveedorId, "EFECTIVO", "20.00", null, null, null, 201).get("id").asText());
        aplicar(admin, otro, pagoId, "1.00", 409);
        assertThat(saldoProveedor(admin, proveedorId).get("saldoNeto").decimalValue()).isEqualByComparingTo("-25.00"); // -5.00 a favor, -20.00 de adelanto
        assertThat(pagoProv).isNotNull();
    }

    // ------------------------------------------------------------------ permisos, datos y consultas

    @Test
    void soloAdminYRecepcionOperanYNingunaRespuestaTraeDniNiTelefono() throws Exception {
        UUID proveedorId = proveedor();
        seisEntregasDe100Litros(proveedorId);
        UUID pagoId = pagoDe(proveedorId);
        JsonNode creado = registrar(recepcion, proveedorId, "EFECTIVO", "50.00", null, null, null, 201);
        String id = creado.get("id").asText();

        for (String rol : List.of("CALIDAD", "VENTAS", "PRODUCCION", "ACOPIADOR")) {
            String token = tokenDeRol(rol);
            mvc.perform(get("/api/pagos-proveedor").header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(get("/api/pagos-proveedor/" + id).header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(get("/api/pagos-proveedor/saldos").param("pagoIds", pagoId.toString()).header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(get("/api/pagos-proveedor/proveedor/" + proveedorId + "/saldo").header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(withJson(post("/api/pagos-proveedor").header("Authorization", token), cuerpo(UUID.randomUUID().toString(), proveedorId, "EFECTIVO", "1.00", null, null, null)))
                    .andExpect(status().isForbidden());
            mvc.perform(withJson(post("/api/pagos-proveedor/" + id + "/aplicaciones").header("Authorization", token), aplicacionesJson(pagoId, "1.00")))
                    .andExpect(status().isForbidden());
        }
        assertThat(pagosDe(proveedorId)).isEqualTo(1);

        // sin DNI ni telefono, ni en el pago ni en los saldos
        String dni = jdbc.queryForObject("SELECT dni FROM proveedor WHERE id = ?", String.class, proveedorId);
        for (String url : List.of("/api/pagos-proveedor/" + id, "/api/pagos-proveedor?proveedorId=" + proveedorId,
                "/api/pagos-proveedor/proveedor/" + proveedorId + "/saldo", "/api/pagos-proveedor/saldos?pagoIds=" + pagoId)) {
            String cuerpo = mvc.perform(get(url).header("Authorization", recepcion)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(cuerpo).doesNotContain(dni).doesNotContainIgnoringCase("telefono").doesNotContainIgnoringCase("\"dni\"");
        }
    }

    @Test
    void losDatosInvalidosSeRechazanConElCodigoCorrecto() throws Exception {
        UUID proveedorId = proveedor();
        seisEntregasDe100Litros(proveedorId);
        UUID pagoId = pagoDe(proveedorId);

        // transferencia o deposito sin banco / operacion; banco sin operacion
        registrar(admin, proveedorId, "TRANSFERENCIA", "10.00", null, null, null, 400);
        registrar(admin, proveedorId, "DEPOSITO", "10.00", "BCP", null, null, 400);
        registrar(admin, proveedorId, "EFECTIVO", "10.00", "BCP", null, null, 400);
        // monto cero o negativo, modalidad desconocida
        registrar(admin, proveedorId, "EFECTIVO", "0", null, null, null, 400);
        registrar(admin, proveedorId, "EFECTIVO", "-5", null, null, null, 400);
        mvc.perform(withJson(post("/api/pagos-proveedor").header("Authorization", admin),
                cuerpo(UUID.randomUUID().toString(), proveedorId, "CHEQUE", "10.00", null, null, null))).andExpect(status().isBadRequest());
        // proveedor inexistente
        mvc.perform(withJson(post("/api/pagos-proveedor").header("Authorization", admin),
                cuerpo(UUID.randomUUID().toString(), UUID.randomUUID(), "EFECTIVO", "10.00", null, null, null))).andExpect(status().isNotFound());
        // liquidacion de OTRO proveedor, inexistente o repetida en la misma solicitud
        UUID otro = proveedor();
        registrar(admin, otro, "EFECTIVO", "10.00", null, null, ap(new String[]{pagoId.toString(), "10.00"}), 400);
        registrar(admin, proveedorId, "EFECTIVO", "10.00", null, null, ap(new String[]{UUID.randomUUID().toString(), "10.00"}), 404);
        registrar(admin, proveedorId, "EFECTIVO", "10.00", null, null,
                ap(new String[]{pagoId.toString(), "4.00"}, new String[]{pagoId.toString(), "4.00"}), 400);
        assertThat(pagosDe(proveedorId)).isZero();
        assertThat(pagosDe(otro)).isZero();
        // pago inexistente
        mvc.perform(get("/api/pagos-proveedor/" + UUID.randomUUID()).header("Authorization", admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/pagos-proveedor/proveedor/" + UUID.randomUUID() + "/saldo").header("Authorization", admin)).andExpect(status().isNotFound());
    }

    @Test
    void elListadoPaginadoFiltraPorProveedorEstadoYBusqueda() throws Exception {
        UUID a = proveedor();
        UUID b = proveedor();
        UUID pa1 = UUID.fromString(registrar(admin, a, "EFECTIVO", "10.00", null, null, null, 201).get("id").asText());
        registrar(admin, a, "TRANSFERENCIA", "20.00", "BCP", "XYZ-" + sufijo, null, 201);
        registrar(admin, b, "EFECTIVO", "30.00", null, null, null, 201);
        mvc.perform(withJson(post("/api/pagos-proveedor/" + pa1 + "/anular").header("Authorization", admin), "{\"motivo\":\"Prueba\"}")).andExpect(status().isOk());

        assertThat(listar("proveedorId=" + a)).hasSize(2);
        assertThat(listar("proveedorId=" + a + "&estado=ANULADO")).hasSize(1);
        assertThat(listar("proveedorId=" + a + "&estado=REGISTRADO").get(0).get("modalidad").asText()).isEqualTo("TRANSFERENCIA");
        assertThat(listar("proveedorId=" + a + "&busqueda=xyz-" + sufijo)).hasSize(1); // por numero de operacion, sin distinguir mayusculas
        assertThat(listar("proveedorId=" + b + "&busqueda=xyz-" + sufijo)).isEmpty();
        assertThat(listar("proveedorId=" + a + "&size=1")).hasSize(1);
        mvc.perform(get("/api/pagos-proveedor?estado=OTRO").header("Authorization", recepcion)).andExpect(status().isBadRequest());
        JsonNode pagina = json.readTree(mvc.perform(get("/api/pagos-proveedor?proveedorId=" + a + "&size=1").header("Authorization", recepcion))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(pagina.get("totalElementos").asInt()).isEqualTo(2);
        assertThat(pagina.get("totalPaginas").asInt()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ ayudas

    private JsonNode listar(String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/pagos-proveedor?" + query).header("Authorization", recepcion)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("contenido");
    }

    private interface Llamada {
        int ejecutar(int i) throws Exception;
    }

    private List<Integer> enParalelo(int hilos, Llamada llamada) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Integer>> resultados = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            int n = i;
            resultados.add(pool.submit(() -> {
                salida.await();
                return llamada.ejecutar(n);
            }));
        }
        salida.countDown();
        List<Integer> estados = new ArrayList<>();
        for (Future<Integer> r : resultados) {
            estados.add(r.get(90, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return estados;
    }

    private String cuerpo(String uuid, UUID proveedorId, String modalidad, String monto, String banco, String operacion, List<String[]> aplicaciones) {
        StringBuilder sb = new StringBuilder("{\"uuidCliente\":\"" + uuid + "\",\"proveedorId\":\"" + proveedorId + "\",\"modalidad\":\"" + modalidad + "\",\"monto\":" + monto);
        if (banco != null) {
            sb.append(",\"banco\":\"").append(banco).append("\"");
        }
        if (operacion != null) {
            sb.append(",\"nroOperacion\":\"").append(operacion).append("\"");
        }
        if (aplicaciones != null) {
            sb.append(",\"aplicaciones\":[");
            for (int i = 0; i < aplicaciones.size(); i++) {
                sb.append(i > 0 ? "," : "").append("{\"pagoId\":\"").append(aplicaciones.get(i)[0]).append("\",\"monto\":").append(aplicaciones.get(i)[1]).append("}");
            }
            sb.append("]");
        }
        return sb.append("}").toString();
    }

    private static List<String[]> ap(String[]... filas) {
        return List.of(filas);
    }

    private String aplicacionesJson(UUID pagoId, String monto) {
        return "{\"aplicaciones\":[{\"pagoId\":\"" + pagoId + "\",\"monto\":" + monto + "}]}";
    }

    private JsonNode registrar(String token, UUID proveedorId, String modalidad, String monto, String banco, String operacion, List<String[]> aplicaciones, int estado)
            throws Exception {
        return registrarCon(token, UUID.randomUUID().toString(), proveedorId, modalidad, monto, banco, operacion, aplicaciones, estado);
    }

    private JsonNode registrarCon(String token, String uuid, UUID proveedorId, String modalidad, String monto, String banco, String operacion, List<String[]> aplicaciones,
                                  int estado) throws Exception {
        MvcResult r = mvc.perform(withJson(post("/api/pagos-proveedor").header("Authorization", token),
                cuerpo(uuid, proveedorId, modalidad, monto, banco, operacion, aplicaciones))).andExpect(status().is(estado)).andReturn();
        return estado < 300 ? json.readTree(r.getResponse().getContentAsString()) : null;
    }

    private JsonNode aplicar(String token, UUID pagoProveedorId, UUID pagoId, String monto, int estado) throws Exception {
        MvcResult r = mvc.perform(withJson(post("/api/pagos-proveedor/" + pagoProveedorId + "/aplicaciones").header("Authorization", token), aplicacionesJson(pagoId, monto)))
                .andExpect(status().is(estado)).andReturn();
        return estado < 300 ? json.readTree(r.getResponse().getContentAsString()) : null;
    }

    private JsonNode saldoDe(String token, UUID pagoId) throws Exception {
        return json.readTree(mvc.perform(get("/api/pagos-proveedor/saldos").param("pagoIds", pagoId.toString()).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0);
    }

    private JsonNode saldoProveedor(String token, UUID proveedorId) throws Exception {
        return json.readTree(mvc.perform(get("/api/pagos-proveedor/proveedor/" + proveedorId + "/saldo").header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private int pagosDe(UUID proveedorId) {
        return jdbc.queryForObject("SELECT count(*) FROM pago_proveedor WHERE proveedor_id = ?", Integer.class, proveedorId);
    }

    private int aplicaciones(UUID pagoProveedorId) {
        return jdbc.queryForObject("SELECT count(*) FROM pago_proveedor_aplicacion WHERE pago_proveedor_id = ?", Integer.class, pagoProveedorId);
    }

    private String estadoDe(UUID pagoProveedorId) {
        return jdbc.queryForObject("SELECT estado FROM pago_proveedor WHERE id = ?", String.class, pagoProveedorId);
    }

    private UUID pagoDe(UUID proveedorId) throws Exception {
        MvcResult r = mvc.perform(withJson(post("/api/pagos/generar").header("Authorization", admin),
                        "{\"proveedorId\":\"" + proveedorId + "\",\"semanaInicio\":\"" + hoy.minusDays(6) + "\",\"semanaFin\":\"" + hoy + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    private void corregir(String token, UUID registroId, String litros, String motivo) throws Exception {
        mvc.perform(withJson(post("/api/registros-acopio/" + registroId + "/correcciones").header("Authorization", token),
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

    private UUID proveedor() {
        return proveedorService.crear(new ProveedorRequest("Proveedor Efectivo " + sufijo,
                String.format("6%07d", (prueba * 37 + (int) (System.nanoTime() % 100000)) % 10_000_000), "999" + sufijo, null)).id();
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
}
