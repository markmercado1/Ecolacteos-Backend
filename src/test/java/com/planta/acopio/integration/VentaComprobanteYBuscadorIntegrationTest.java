package com.planta.acopio.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.dto.AjusteInventarioRequest;
import com.planta.acopio.dto.CrearVentaCarritoRequest;
import com.planta.acopio.dto.DetalleVentaRequest;
import com.planta.acopio.dto.PrecioQuesoRequest;
import com.planta.acopio.dto.VentaRequest;
import com.planta.acopio.dto.VentaResumenResponse;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.exception.ConflictoNegocioException;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comprobante de la venta (V48) y buscador de la pantalla "Nueva venta", contra PostgreSQL real:
 * - cada venta nace con su serie y numero: nota de venta NV01 por defecto, boleta B001 con su propia secuencia;
 * - un reintento con el mismo uuidCliente devuelve la misma venta con el mismo numero; con otro tipo es un conflicto;
 * - el listado y el detalle traen comprobante, vendedor y hora, y cada linea su codigo de presentacion;
 * - el buscador ordena por cercania (empieza / palabra / contiene), ignora acentos y mayusculas, muestra lo que no se puede vender con su motivo
 *   y no devuelve productos desactivados.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VentaComprobanteYBuscadorIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
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
        autenticar();
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ comprobante

    @Test
    void laNotaDeVentaEsElTipoPorDefectoYCadaSerieTieneSuPropioCorrelativo() {
        Presentacion p = presentacion("Producto comprobante", "10.00", 50);

        VentaResumenResponse nota1 = vender(null, new DetalleVentaRequest(p.getId(), 1));
        VentaResumenResponse nota2 = vender("NOTA_VENTA", new DetalleVentaRequest(p.getId(), 1));
        VentaResumenResponse boleta1 = vender("BOLETA", new DetalleVentaRequest(p.getId(), 1));
        VentaResumenResponse boleta2 = vender("boleta", new DetalleVentaRequest(p.getId(), 1)); // sin distinguir mayusculas
        VentaResumenResponse nota3 = vender("  ", new DetalleVentaRequest(p.getId(), 1));       // en blanco = nota de venta

        assertThat(nota1.tipoComprobante()).isEqualTo("NOTA_VENTA");
        assertThat(nota1.comprobante()).matches("NV01-\\d{8}");
        assertThat(numero(nota2)).isEqualTo(numero(nota1) + 1);
        assertThat(numero(nota3)).isEqualTo(numero(nota2) + 1);

        assertThat(boleta1.tipoComprobante()).isEqualTo("BOLETA");
        assertThat(boleta1.comprobante()).matches("B001-\\d{8}");
        assertThat(numero(boleta2)).isEqualTo(numero(boleta1) + 1);
        // las series no se mezclan: una boleta no consume numeros de nota de venta
        assertThat(numero(nota3)).isEqualTo(numero(nota1) + 2);
    }

    @Test
    void laRespuestaDeRegistrarYaTraeVendedorHoraYCodigoDeCadaLinea() {
        Presentacion p = presentacion("Producto respuesta", "12.50", 10);

        VentaResumenResponse venta = vender("BOLETA", new DetalleVentaRequest(p.getId(), 2));

        assertThat(venta.vendedorNombre()).isNotBlank();
        assertThat(venta.creadoEn()).isNotNull(); // lo rellena la BD: la respuesta del POST ya lo trae
        assertThat(venta.detalles()).hasSize(1);
        assertThat(venta.detalles().get(0).codigo()).matches("PRD-\\d{3,}");
        assertThat(venta.total()).isEqualByComparingTo("25.00");
    }

    @Test
    void unReintentoConElMismoUuidDevuelveLaMismaVentaYElMismoNumeroYConOtroTipoEsUnConflicto() {
        Presentacion p = presentacion("Producto idempotencia", "5.00", 10);
        String uuid = UUID.randomUUID().toString();

        VentaResumenResponse primera = ventaService.registrarCarritoOIgnorarSiDuplicado(request(uuid, "BOLETA", p, 1));
        VentaResumenResponse reintento = ventaService.registrarCarritoOIgnorarSiDuplicado(request(uuid, "BOLETA", p, 1));
        assertThat(reintento.id()).isEqualTo(primera.id());
        assertThat(reintento.comprobante()).isEqualTo(primera.comprobante());
        // sin indicar el tipo (un reintento que no lo repite) tampoco cambia nada
        assertThat(ventaService.registrarCarritoOIgnorarSiDuplicado(request(uuid, null, p, 1)).comprobante()).isEqualTo(primera.comprobante());

        assertThatThrownBy(() -> ventaService.registrarCarritoOIgnorarSiDuplicado(request(uuid, "NOTA_VENTA", p, 1)))
                .isInstanceOf(ConflictoNegocioException.class);
    }

    @Test
    void unTipoDeComprobanteDesconocidoEsUn400() throws Exception {
        Presentacion p = presentacion("Producto tipo invalido", "5.00", 10);
        mvc.perform(post("/api/ventas/carrito").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"uuidCliente\":\"" + UUID.randomUUID() + "\",\"fecha\":\"" + hoy + "\",\"tipoCliente\":\"PUBLICO\","
                                + "\"tipoComprobante\":\"FACTURA\",\"detalles\":[{\"presentacionId\":\"" + p.getId() + "\",\"cantidad\":1}]}")
                                .getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lasVentasDeLaAppMovilYElListadoYDetalleTambienTraenComprobanteVendedorYHora() throws Exception {
        Presentacion p = presentacion("Producto movil", "8.00", 10);
        // contrato movil: una sola linea, sin tipo de comprobante -> nota de venta numerada
        var movil = ventaService.registrarOIgnorarSiDuplicado(
                new VentaRequest(UUID.randomUUID().toString(), hoy, "PUBLICO", p.getId(), 1, new BigDecimal("8.00")));
        autenticar();

        MvcResult detalle = mvc.perform(get("/api/ventas/" + movil.id()).header("Authorization", admin)).andExpect(status().isOk()).andReturn();
        JsonNode d = json.readTree(detalle.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(d.get("tipoComprobante").asText()).isEqualTo("NOTA_VENTA");
        assertThat(d.get("comprobante").asText()).matches("NV01-\\d{8}");
        assertThat(d.get("vendedorNombre").asText()).isNotBlank();
        assertThat(d.get("creadoEn").isNull()).isFalse();
        assertThat(d.get("detalles").get(0).get("codigo").asText()).matches("PRD-\\d{3,}");

        MvcResult lista = mvc.perform(get("/api/ventas").header("Authorization", admin).param("size", "100")).andExpect(status().isOk()).andReturn();
        JsonNode contenido = json.readTree(lista.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("contenido");
        boolean encontrada = false;
        for (JsonNode fila : contenido) {
            assertThat(fila.get("comprobante").asText()).matches("(NV01|B001)-\\d{8}");
            if (fila.get("id").asText().equals(movil.id().toString())) encontrada = true;
        }
        assertThat(encontrada).isTrue();
    }

    @Test
    void elComprobanteEnPdfSeDescargaConSuNumeroYUnaVentaInexistenteEsUn404() throws Exception {
        Presentacion p = presentacion("Producto pdf", "7.50", 10);
        VentaResumenResponse venta = vender("BOLETA", new DetalleVentaRequest(p.getId(), 2));
        autenticar();

        MvcResult r = mvc.perform(get("/api/ventas/" + venta.id() + "/comprobante").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();
        byte[] pdf = r.getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        assertThat(r.getResponse().getHeader("Content-Disposition")).contains("inline").contains(venta.comprobante() + ".pdf");
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        com.lowagie.text.pdf.PdfReader lector = new com.lowagie.text.pdf.PdfReader(pdf);
        try {
            String texto = new com.lowagie.text.pdf.parser.PdfTextExtractor(lector).getTextFromPage(1);
            assertThat(texto).contains(venta.comprobante()).contains("S/ 15.00").contains("SON: QUINCE CON 00/100 SOLES");
        } finally {
            lector.close();
        }

        mvc.perform(get("/api/ventas/" + UUID.randomUUID() + "/comprobante").header("Authorization", admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/ventas/" + venta.id() + "/comprobante")).andExpect(status().is4xxClientError());
    }

    // ------------------------------------------------------------------ historial: rango de fechas, busqueda por comprobante y totales

    @Test
    void elHistorialFiltraPorRangoDeFechasBuscaPorComprobanteYLosTotalesSumanTodoElFiltro() throws Exception {
        Presentacion p = presentacion("Producto historial", "10.00", 100);
        LocalDate d1 = LocalDate.of(2020, 3, 10), d2 = LocalDate.of(2020, 3, 12), d3 = LocalDate.of(2020, 3, 15);
        VentaResumenResponse v1 = venderEn(d1, new DetalleVentaRequest(p.getId(), 1));  // 10.00
        VentaResumenResponse v2 = venderEn(d2, new DetalleVentaRequest(p.getId(), 2));  // 20.00
        VentaResumenResponse v3 = venderEn(d3, new DetalleVentaRequest(p.getId(), 3));  // 30.00

        assertThat(idsDelListado("desde", "2020-03-11", "hasta", "2020-03-15")).containsExactlyInAnyOrder(v2.id().toString(), v3.id().toString());
        assertThat(idsDelListado("desde", "2020-03-10", "hasta", "2020-03-12")).containsExactlyInAnyOrder(v1.id().toString(), v2.id().toString());
        assertThat(idsDelListado("desde", "2020-03-12", "hasta", "2020-03-12")).containsExactly(v2.id().toString()); // extremos inclusivos
        assertThat(idsDelListado("fecha", "2020-03-12")).containsExactly(v2.id().toString());
        // la fecha exacta manda sobre el rango
        assertThat(idsDelListado("fecha", "2020-03-12", "desde", "2020-03-01", "hasta", "2020-03-31")).containsExactly(v2.id().toString());

        // por el numero completo del comprobante (sin distinguir mayusculas)
        assertThat(idsDelListado("desde", "2020-03-01", "hasta", "2020-03-31", "busqueda", v3.comprobante().toLowerCase()))
                .containsExactly(v3.id().toString());

        mvc.perform(get("/api/ventas").header("Authorization", admin).param("desde", "2020-03-15").param("hasta", "2020-03-10"))
                .andExpect(status().isBadRequest());

        // totales: todas las ventas del filtro, no solo una pagina
        assertThat(resumen("desde", "2020-03-10", "hasta", "2020-03-15")).containsExactly(3L, "60.00");
        assertThat(resumen("desde", "2020-03-11", "hasta", "2020-03-15")).containsExactly(2L, "50.00");
        assertThat(resumen("fecha", "2020-03-10")).containsExactly(1L, "10.00");
        assertThat(resumen("desde", "2020-03-10", "hasta", "2020-03-15", "busqueda", v2.comprobante())).containsExactly(1L, "20.00");
        assertThat(resumen("desde", "2020-03-10", "hasta", "2020-03-15", "tipoCliente", "MAYORISTA")).containsExactly(0L, "0.00");
        assertThat(resumen("desde", "2019-01-01", "hasta", "2019-01-31")).containsExactly(0L, "0.00");
        mvc.perform(get("/api/ventas/resumen").header("Authorization", admin).param("desde", "2020-03-15").param("hasta", "2020-03-10"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/ventas/resumen")).andExpect(status().is4xxClientError());
    }

    private List<String> idsDelListado(String... paramYValor) throws Exception {
        var req = get("/api/ventas").header("Authorization", admin).param("size", "100");
        for (int i = 0; i < paramYValor.length; i += 2) req = req.param(paramYValor[i], paramYValor[i + 1]);
        JsonNode contenido = json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("contenido");
        List<String> ids = new ArrayList<>();
        contenido.forEach(n -> ids.add(n.get("id").asText()));
        return ids;
    }

    /** [cantidad, total] del endpoint de totales. */
    private List<Object> resumen(String... paramYValor) throws Exception {
        var req = get("/api/ventas/resumen").header("Authorization", admin);
        for (int i = 0; i < paramYValor.length; i += 2) req = req.param(paramYValor[i], paramYValor[i + 1]);
        JsonNode r = json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        return List.of(r.get("cantidad").asLong(), new BigDecimal(r.get("total").asText()).setScale(2).toPlainString());
    }

    private VentaResumenResponse venderEn(LocalDate fecha, DetalleVentaRequest... detalles) {
        autenticar();
        return ventaService.registrarCarritoOIgnorarSiDuplicado(
                new CrearVentaCarritoRequest(UUID.randomUUID().toString(), fecha, "PUBLICO", List.of(detalles), null, null, null));
    }

    // ------------------------------------------------------------------ buscador

    @Test
    void elBuscadorPoneAlPrincipioLoQueEmpiezaConElTextoIgnoraAcentosYMuestraLoQueNoSePuedeVender() throws Exception {
        String t = "qz" + Long.toString(System.nanoTime(), 36);
        Presentacion empieza = presentacion(t + "Lácteo fresco", "9.90", 60);           // el producto EMPIEZA con el texto
        Presentacion palabra = presentacion("Pan de " + t + "lacteo", "4.00", 5);        // una PALABRA empieza con el texto
        Presentacion contiene = presentacion("Mega" + t + "lacteo", "3.00", 7);          // solo lo CONTIENE
        Presentacion sinStock = presentacion(t + "lacteo agotado", "2.00", 0);
        Presentacion sinPrecio = presentacion(t + "lacteo sin precio", null, 4);
        Presentacion desactivado = presentacion(t + "lacteo apagado", "1.00", 3);
        Producto apagado = desactivado.getProducto();
        apagado.setActivo(false);
        productoRepository.save(apagado);

        JsonNode r = buscar(t.toUpperCase() + "LACTEO", "PUBLICO"); // mayusculas y sin tilde: igual encuentra "Lácteo"
        List<String> ids = new ArrayList<>();
        r.forEach(n -> ids.add(n.get("presentacionId").asText()));

        assertThat(ids).doesNotContain(desactivado.getId().toString());
        assertThat(ids).contains(empieza.getId().toString(), palabra.getId().toString(), contiene.getId().toString(),
                sinStock.getId().toString(), sinPrecio.getId().toString());
        // los que EMPIEZAN con el texto van antes que el de "palabra", y este antes que el que solo lo "contiene"
        int posPalabra = ids.indexOf(palabra.getId().toString());
        int posContiene = ids.indexOf(contiene.getId().toString());
        for (Presentacion p : List.of(empieza, sinStock, sinPrecio)) assertThat(ids.indexOf(p.getId().toString())).isLessThan(posPalabra);
        assertThat(posPalabra).isLessThan(posContiene);
        // dentro del mismo rango, lo que se puede vender va primero y lo agotado o sin precio despues (sigue visible)
        int posEmpieza = ids.indexOf(empieza.getId().toString());
        assertThat(posEmpieza).isLessThan(ids.indexOf(sinStock.getId().toString()));
        assertThat(posEmpieza).isLessThan(ids.indexOf(sinPrecio.getId().toString()));
        assertThat(r.get(0).get("presentacionId").asText()).isEqualTo(empieza.getId().toString()); // la primera fila es la mejor opcion para vender

        JsonNode fila = fila(r, empieza);
        assertThat(fila.get("disponible").asBoolean()).isTrue();
        assertThat(fila.get("motivo").isNull()).isTrue();
        assertThat(fila.get("stockVendible").asInt()).isEqualTo(60);
        assertThat(new BigDecimal(fila.get("precio").asText())).isEqualByComparingTo("9.90");
        assertThat(fila.get("codigo").asText()).matches("PRD-\\d{3,}");
        assertThat(fila.get("productoNombre").asText()).isEqualTo(t + "Lácteo fresco");
        assertThat(fila.get("presentacionNombre").asText()).isEqualTo("Unidad");

        JsonNode agotado = fila(r, sinStock);
        assertThat(agotado.get("disponible").asBoolean()).isFalse();
        assertThat(agotado.get("motivo").asText()).isEqualTo("SIN_STOCK");
        JsonNode sinPrecioFila = fila(r, sinPrecio);
        assertThat(sinPrecioFila.get("disponible").asBoolean()).isFalse();
        assertThat(sinPrecioFila.get("motivo").asText()).isEqualTo("SIN_PRECIO");
        assertThat(sinPrecioFila.get("precio").isNull()).isTrue();

        // el precio depende del canal: en MAYORISTA nadie tiene precio configurado
        JsonNode mayorista = buscar(t, "MAYORISTA");
        assertThat(fila(mayorista, empieza).get("motivo").asText()).isEqualTo("SIN_PRECIO");
    }

    @Test
    void elBuscadorBuscaTambienPorCodigoRespetaElLimiteYSinTextoDevuelveVacio() throws Exception {
        String t = "wy" + Long.toString(System.nanoTime(), 36);
        List<Presentacion> varias = new ArrayList<>();
        for (int i = 0; i < 5; i++) varias.add(presentacion(t + "item" + i, "1.00", 1));

        assertThat(buscar(t, "PUBLICO")).hasSize(5);
        assertThat(buscarConLimite(t, "PUBLICO", 3)).hasSize(3);
        assertThat(buscarConLimite(t, "PUBLICO", 0)).hasSize(1); // el minimo es 1

        String codigo = codigoDe(varias.get(0));
        JsonNode porCodigo = buscar(codigo.toLowerCase(), "PUBLICO");
        assertThat(porCodigo.get(0).get("codigo").asText()).isEqualTo(codigo); // el codigo exacto va primero

        assertThat(buscar("", "PUBLICO")).isEmpty();
        assertThat(buscar("   ", "PUBLICO")).isEmpty();
        assertThat(buscar("%", "PUBLICO")).isEmpty(); // el comodin se toma literal: ninguna presentacion lleva "%" en el nombre
    }

    @Test
    void elBuscadorExigeSesionYUnCanalValido() throws Exception {
        mvc.perform(get("/api/ventas/presentaciones").param("q", "x").param("tipoCliente", "PUBLICO").param("fecha", hoy.toString()))
                .andExpect(status().is4xxClientError());
        mvc.perform(get("/api/ventas/presentaciones").header("Authorization", admin)
                        .param("q", "x").param("tipoCliente", "NO_EXISTE").param("fecha", hoy.toString()))
                .andExpect(status().is4xxClientError());
    }

    // ------------------------------------------------------------------ auxiliares

    private JsonNode buscar(String q, String canal) throws Exception {
        return buscarConLimite(q, canal, null);
    }

    private JsonNode buscarConLimite(String q, String canal, Integer limite) throws Exception {
        var req = get("/api/ventas/presentaciones").header("Authorization", admin)
                .param("q", q).param("tipoCliente", canal).param("fecha", hoy.toString());
        if (limite != null) req = req.param("limite", String.valueOf(limite));
        MvcResult r = mvc.perform(req).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode fila(JsonNode resultado, Presentacion p) {
        for (JsonNode n : resultado) if (n.get("presentacionId").asText().equals(p.getId().toString())) return n;
        throw new AssertionError("La presentacion " + p.getId() + " no esta en el resultado: " + resultado);
    }

    private String codigoDe(Presentacion p) {
        return presentacionRepository.findById(p.getId()).orElseThrow().getCodigo();
    }

    private static long numero(VentaResumenResponse v) {
        return Long.parseLong(v.comprobante().substring(v.comprobante().indexOf('-') + 1));
    }

    private void autenticar() {
        // MockMvc limpia el contexto de seguridad al terminar cada peticion: se vuelve a autenticar al vendedor.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null));
    }

    private CrearVentaCarritoRequest request(String uuid, String tipoComprobante, Presentacion p, int cantidad) {
        return new CrearVentaCarritoRequest(uuid, hoy, "PUBLICO", List.of(new DetalleVentaRequest(p.getId(), cantidad)), null, null, tipoComprobante);
    }

    private VentaResumenResponse vender(String tipoComprobante, DetalleVentaRequest... detalles) {
        autenticar();
        return ventaService.registrarCarritoOIgnorarSiDuplicado(
                new CrearVentaCarritoRequest(UUID.randomUUID().toString(), hoy, "PUBLICO", List.of(detalles), null, null, tipoComprobante));
    }

    /** precio null = sin precio configurado en ningun canal. */
    private Presentacion presentacion(String nombreProducto, String precio, int stock) {
        String s = String.valueOf(System.nanoTime());
        Familia f = new Familia();
        f.setNombre("Familia V48 " + s);
        Producto p = new Producto();
        p.setNombre(nombreProducto);
        p.setFamilia(familiaRepository.save(f));
        p = productoRepository.save(p);
        Presentacion pr = new Presentacion();
        pr.setNombre("Unidad");
        pr.setProducto(p);
        pr.setUnidadConteo("unidad");
        pr = presentacionRepository.save(pr);
        if (precio != null) precioQuesoService.crear(new PrecioQuesoRequest(pr.getId(), "PUBLICO", new BigDecimal(precio), LocalDate.of(2000, 1, 1)));
        if (stock > 0) ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), stock, "Stock inicial de la prueba"));
        autenticar();
        return pr;
    }

    private String token() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@planta.com\",\"password\":\"Admin1234\"}"))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + json.readTree(login.getResponse().getContentAsString()).get("token").asText();
    }
}
