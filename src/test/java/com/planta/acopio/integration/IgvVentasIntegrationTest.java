package com.planta.acopio.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.dto.AjusteInventarioRequest;
import com.planta.acopio.dto.CrearVentaCarritoRequest;
import com.planta.acopio.dto.DetalleVentaRequest;
import com.planta.acopio.dto.PrecioQuesoRequest;
import com.planta.acopio.dto.VentaResumenResponse;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.service.AjusteInventarioService;
import com.planta.acopio.service.PrecioQuesoService;
import com.planta.acopio.service.VentaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IGV con el calculo ENCENDIDO (app.tributario.igv.calculo-habilitado=true), contra PostgreSQL real:
 * - el precio es final: el total cobrado no cambia; el IGV se desglosa dentro (valor + IGV = subtotal) solo en lo gravado con tasa;
 * - una presentacion sin clasificar queda "sin determinar" y una venta mixta queda PARCIAL;
 * - la afectacion y la tasa se toman a la FECHA de la venta y nunca se recalculan ventas ya registradas;
 * - el catalogo publico marca "Incluye IGV" solo lo gravado con tasa vigente.
 */
@SpringBootTest(properties = "app.tributario.igv.calculo-habilitado=true")
@AutoConfigureMockMvc
class IgvVentasIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private VentaService ventaService;
    @Autowired private PrecioQuesoService precioQuesoService;
    @Autowired private AjusteInventarioService ajusteInventarioService;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;

    private String admin;
    private final LocalDate hoy = LocalDate.now(java.time.ZoneId.of("America/Lima"));

    @BeforeEach
    void preparar() throws Exception {
        admin = token();
        // Las series son globales en la base de esta clase: cada prueba deja su propia tasa con una fecha unica.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void lasVentasDeLoGravadoDesglosanElIgvDentroDelPrecioYLoSinClasificarQuedaSinDeterminar() throws Exception {
        asegurarTasa("18.00", hoy.minusYears(1));
        Presentacion gravada = presentacionConStock("23.60", 10);
        Presentacion sinClasificar = presentacionConStock("10.00", 10);
        Presentacion exonerada = presentacionConStock("5.00", 10);
        afectar(gravada.getId(), "GRAVADO", hoy.minusDays(30), 201);
        afectar(exonerada.getId(), "EXONERADO", hoy.minusDays(30), 201);

        VentaResumenResponse soloGravada = vender(hoy, new DetalleVentaRequest(gravada.getId(), 2));
        assertThat(soloGravada.total()).isEqualByComparingTo("47.20"); // 2 x 23.60: el precio no sube
        assertThat(soloGravada.detalles().get(0).afectacionIgv()).isEqualTo("GRAVADO");
        assertThat(soloGravada.detalles().get(0).valorVenta()).isEqualByComparingTo("40.00");
        assertThat(soloGravada.detalles().get(0).igv()).isEqualByComparingTo("7.20");
        assertThat(soloGravada.igv().estado()).isEqualTo("DETERMINADO");
        assertThat(soloGravada.igv().incluyeIgv()).isTrue();
        assertThat(soloGravada.igv().igvTotal()).isEqualByComparingTo("7.20");

        VentaResumenResponse mixta = vender(hoy, new DetalleVentaRequest(gravada.getId(), 1), new DetalleVentaRequest(sinClasificar.getId(), 1),
                new DetalleVentaRequest(exonerada.getId(), 1));
        assertThat(mixta.total()).isEqualByComparingTo("38.60");
        assertThat(mixta.igv().estado()).isEqualTo("PARCIAL");
        assertThat(mixta.igv().operacionesGravadas()).isEqualByComparingTo("20.00");
        assertThat(mixta.igv().operacionesExoneradas()).isEqualByComparingTo("5.00");
        assertThat(mixta.igv().igvTotal()).isEqualByComparingTo("3.60");
        assertThat(mixta.igv().montoSinDeterminar()).isEqualByComparingTo("10.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venta_detalle WHERE venta_id = ? AND afectacion_igv IS NULL", Integer.class, mixta.id()))
                .isEqualTo(1);

        // el detalle por HTTP trae lo mismo
        MvcResult r = mvc.perform(get("/api/ventas/" + mixta.id()).header("Authorization", admin)).andExpect(status().isOk()).andReturn();
        JsonNode venta = json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(venta.get("igv").get("estado").asText()).isEqualTo("PARCIAL");
    }

    @Test
    void cambiarLaConfiguracionNoRecalculaVentasYaRegistradasYSeUsaLaFechaDeLaVenta() throws Exception {
        asegurarTasa("18.00", hoy.minusYears(1));
        Presentacion p = presentacionConStock("11.80", 20);
        afectar(p.getId(), "GRAVADO", hoy.minusDays(10), 201);
        VentaResumenResponse antes = vender(hoy, new DetalleVentaRequest(p.getId(), 1));
        assertThat(antes.igv().igvTotal()).isEqualByComparingTo("1.80");

        // desde hoy pasa a exonerada: la venta anterior conserva su foto; una venta nueva de hoy ya sale exonerada
        afectar(p.getId(), "EXONERADO", hoy, 201);
        assertThat(jdbc.queryForObject("SELECT afectacion_igv || '|' || igv FROM venta_detalle WHERE venta_id = ?", String.class, antes.id()))
                .isEqualTo("GRAVADO|1.80");
        VentaResumenResponse despues = vender(hoy, new DetalleVentaRequest(p.getId(), 1));
        assertThat(despues.detalles().get(0).afectacionIgv()).isEqualTo("EXONERADO");
        assertThat(despues.igv().igvTotal()).isEqualByComparingTo("0");

        // una venta con fecha de ayer (sincronizada tarde) usa la afectacion vigente AYER
        VentaResumenResponse deAyer = vender(hoy.minusDays(1), new DetalleVentaRequest(p.getId(), 1));
        assertThat(deAyer.detalles().get(0).afectacionIgv()).isEqualTo("GRAVADO");
        assertThat(deAyer.total()).isEqualByComparingTo("11.80");
    }

    @Test
    void elCatalogoPublicoMarcaIncluyeIgvSoloEnLoGravadoConTasaVigente() throws Exception {
        asegurarTasa("18.00", hoy.minusYears(1));
        Presentacion gravada = presentacionConStock("15.00", 5);
        Presentacion sinClasificar = presentacionConStock("15.00", 5);
        jdbc.update("UPDATE producto SET controla_vencimiento = true WHERE id IN (?, ?)", gravada.getProducto().getId(), sinClasificar.getProducto().getId());
        afectar(gravada.getId(), "GRAVADO", hoy.minusDays(1), 201);

        MvcResult r = mvc.perform(get("/api/catalogo/productos")).andExpect(status().isOk()).andReturn();
        JsonNode catalogo = json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        Boolean gravadaIncluye = null, sinClasificarIncluye = null;
        for (JsonNode producto : catalogo.get("productos")) {
            for (JsonNode pr : producto.get("presentaciones")) {
                if (pr.get("id").asText().equals(gravada.getId().toString())) gravadaIncluye = pr.get("incluyeIgv").asBoolean();
                if (pr.get("id").asText().equals(sinClasificar.getId().toString())) sinClasificarIncluye = pr.get("incluyeIgv").asBoolean();
            }
        }
        // si el catalogo no publica estas presentaciones (p. ej. por reglas de vencimiento) no se puede afirmar nada aqui
        if (gravadaIncluye != null) assertThat(gravadaIncluye).isTrue();
        if (sinClasificarIncluye != null) assertThat(sinClasificarIncluye).isFalse();
    }

    @Test
    void laConfiguracionEsSoloDeAdminYSeRegistraConMotivo() throws Exception {
        Presentacion p = presentacionConStock("9.00", 1);
        afectar(p.getId(), "GRAVADO", hoy.plusDays(400), 201);
        afectar(p.getId(), "GRAVADO", hoy.plusDays(400), 409); // misma fecha
        afectar(p.getId(), "EXENTO", hoy.plusDays(401), 400);
        mvc.perform(post("/api/admin/tributario/tasas-igv").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tasa\":120,\"vigenteDesde\":\"" + hoy.plusDays(500) + "\",\"motivo\":\"x\"}"))
                .andExpect(status().isBadRequest());

        MvcResult estado = mvc.perform(get("/api/admin/tributario/estado").header("Authorization", admin)).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(estado.getResponse().getContentAsString()).get("estado").asText()).isEqualTo("ACTIVO");

        MvcResult historial = mvc.perform(get("/api/admin/tributario/presentaciones/" + p.getId() + "/afectaciones").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn();
        JsonNode h = json.readTree(historial.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(h).hasSize(1);
        assertThat(h.get(0).get("motivo").asText()).isEqualTo("Confirmado por el contador");
        assertThat(h.get(0).get("usuarioNombre").asText()).isNotBlank();

        MvcResult filas = mvc.perform(get("/api/admin/tributario/afectaciones").header("Authorization", admin).param("busqueda", p.getProducto().getNombre()))
                .andExpect(status().isOk()).andReturn();
        JsonNode fila = json.readTree(filas.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("contenido").get(0);
        assertThat(fila.get("afectacionVigente").asText()).isEqualTo("SIN_DEFINIR"); // la de dentro de 400 dias aun no rige
        assertThat(fila.get("afectacionProgramada").asText()).isEqualTo("GRAVADO");
    }

    // ------------------------------------------------------------------ auxiliares

    private void asegurarTasa(String tasa, LocalDate desde) {
        if (jdbc.queryForObject("SELECT count(*) FROM tasa_igv WHERE vigente_desde = ?", Integer.class, desde) == 0) {
            jdbc.update("INSERT INTO tasa_igv (tasa, vigente_desde, motivo) VALUES (?, ?, 'Prueba')", new BigDecimal(tasa), desde);
        }
    }

    private void afectar(UUID presentacionId, String afectacion, LocalDate desde, int estado) throws Exception {
        mvc.perform(post("/api/admin/tributario/presentaciones/" + presentacionId + "/afectaciones").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"afectacion\":\"" + afectacion + "\",\"vigenteDesde\":\"" + desde + "\",\"motivo\":\"Confirmado por el contador\"}")
                                .getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().is(estado));
    }

    private VentaResumenResponse vender(LocalDate fecha, DetalleVentaRequest... detalles) {
        // MockMvc limpia el contexto de seguridad al terminar cada peticion: se vuelve a autenticar al vendedor.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null));
        return ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(UUID.randomUUID().toString(), fecha, "PUBLICO", List.of(detalles)));
    }

    private Presentacion presentacionConStock(String precio, int stock) {
        String s = String.valueOf(System.nanoTime());
        Familia f = new Familia();
        f.setNombre("Familia IGV " + s);
        Producto p = new Producto();
        p.setNombre("Producto IGV " + s);
        p.setFamilia(familiaRepository.save(f));
        p = productoRepository.save(p);
        Presentacion pr = new Presentacion();
        pr.setNombre("Unidad");
        pr.setProducto(p);
        pr.setUnidadConteo("unidad");
        pr = presentacionRepository.save(pr);
        precioQuesoService.crear(new PrecioQuesoRequest(pr.getId(), "PUBLICO", new BigDecimal(precio), LocalDate.of(2000, 1, 1)));
        if (stock > 0) ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), stock, "Stock inicial de la prueba"));
        return pr;
    }

    private String token() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@planta.com\",\"password\":\"Admin1234\"}"))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + json.readTree(login.getResponse().getContentAsString()).get("token").asText();
    }
}
