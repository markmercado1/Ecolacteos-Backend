package com.planta.acopio.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.dto.AjusteInventarioRequest;
import com.planta.acopio.dto.CrearVentaCarritoRequest;
import com.planta.acopio.dto.DetalleSalidaRequest;
import com.planta.acopio.dto.DetalleVentaRequest;
import com.planta.acopio.dto.FinalizarLoteRequest;
import com.planta.acopio.dto.PrecioQuesoRequest;
import com.planta.acopio.dto.VencimientoSalidaRequest;
import com.planta.acopio.entity.EstadoLote;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.LoteProduccion;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.LoteProduccionRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.AjusteInventarioService;
import com.planta.acopio.service.ControlVencimientoService;
import com.planta.acopio.service.LoteProduccionService;
import com.planta.acopio.service.PrecioQuesoService;
import com.planta.acopio.service.ReglaVencimiento;
import com.planta.acopio.service.VentaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 3 (catalogo publico) contra PostgreSQL REAL, llamando por HTTP (MockMvc, con la seguridad real) SIN token.
 * Verifica las reglas del usuario: solo productos con control de vencimiento; solo precio PUBLICO vigente; la
 * disponibilidad sale del stock VENDIBLE (sin vencidos ni sin trazabilidad) y es un estado, no una cantidad; y
 * nada interno se filtra. La cache se desactiva (cache-segundos=0) para ver cada cambio de inmediato.
 * El catalogo es global, asi que cada prueba busca SUS productos por id.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class CatalogoPublicoIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("acopio_lechero_test")
            .withUsername("acopio")
            .withPassword("acopio");

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.vencimiento.activacion-habilitada", () -> "true");
        registry.add("app.catalogo.cache-segundos", () -> "0");
        registry.add("app.catalogo.umbral-pocas-unidades", () -> "5");
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private ControlVencimientoService controlService;
    @Autowired private LoteProduccionService loteProduccionService;
    @Autowired private AjusteInventarioService ajusteInventarioService;
    @Autowired private VentaService ventaService;
    @Autowired private PrecioQuesoService precioQuesoService;
    @Autowired private ReglaVencimiento regla;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private LoteProduccionRepository loteRepository;
    @Autowired private JdbcTemplate jdbc;

    private Usuario usuario;
    private LocalDate hoy;

    @BeforeEach
    void seed() {
        String s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("CATPUB_IT_" + s);
        rol = rolRepository.save(rol);
        Usuario u = new Usuario();
        u.setNombre("Catalogo Publico " + s);
        u.setEmail("catpub-" + s + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        usuario = usuarioRepository.save(u);
        autenticar();
        hoy = regla.hoy();
        // Fase 4: la disponibilidad se mide para la PRIMERA FECHA DE RETIRO POSIBLE. Para que estas pruebas no dependan del dia
        // de la semana ni de la hora, se atiende todos los dias hasta las 23:59 (asi esa fecha es siempre hoy).
        jdbc.update("UPDATE configuracion_sistema SET valor = '1,2,3,4,5,6,7' WHERE clave = 'RESERVA_DIAS_ATENCION'");
        jdbc.update("UPDATE configuracion_sistema SET valor = '23:59' WHERE clave = 'RESERVA_HORA_CIERRE'");
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
        jdbc.update("UPDATE configuracion_sistema SET valor = '1,2,3,4,5,6' WHERE clave = 'RESERVA_DIAS_ATENCION'");
        jdbc.update("UPDATE configuracion_sistema SET valor = '18:00' WHERE clave = 'RESERVA_HORA_CIERRE'");
    }

    // ------------------------------------------------------------------ utilidades

    private Producto nuevoProducto(boolean controla) {
        String s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Familia f = new Familia();
        f.setNombre("Familia Pub " + s);
        Producto p = new Producto();
        p.setNombre("Yogurt Pub " + s);
        p.setFamilia(familiaRepository.save(f));
        p = productoRepository.save(p);
        if (controla) controlService.activar(p.getId()); // sin existencias aun: nada queda sin trazabilidad
        return p;
    }

    private Presentacion nuevaPresentacion(Producto producto, String nombre) {
        Presentacion pr = new Presentacion();
        pr.setNombre(nombre);
        pr.setProducto(producto);
        pr.setUnidadConteo("botella");
        // Lo que hace PresentacionService.crear: una presentacion nueva de un producto YA controlado nace con saldo
        // inicial 0 (este helper guarda por repositorio y debe replicarlo).
        if (productoRepository.controlaVencimiento(producto.getId()).orElse(false)) pr.setSaldoSinTrazabilidadInicial(0);
        return presentacionRepository.save(pr);
    }

    private void precio(Presentacion pr, String modalidad, String valor, LocalDate desde) {
        precioQuesoService.crear(new PrecioQuesoRequest(pr.getId(), modalidad, new BigDecimal(valor), desde));
    }

    /** Produce `cantidad` unidades que vencen en `vence` (el lote se crea con fecha anterior para permitir vencimientos pasados). */
    private void producir(Producto producto, Presentacion pr, int cantidad, LocalDate vence) {
        LoteProduccion lote = new LoteProduccion();
        lote.setUuidCliente(UUID.randomUUID().toString());
        lote.setFecha(vence.isBefore(hoy.minusDays(1)) ? vence : hoy.minusDays(1));
        lote.setProducto(producto);
        lote.setLitrosUsados(new BigDecimal("100"));
        lote.setResponsable(usuario);
        lote.setEstado(EstadoLote.EN_PRODUCCION);
        lote.setIniciadoEn(LocalDateTime.now());
        UUID loteId = loteRepository.save(lote).getId();
        loteProduccionService.finalizar(loteId, new FinalizarLoteRequest(
                List.of(new DetalleSalidaRequest(pr.getId(), cantidad)), null,
                List.of(new VencimientoSalidaRequest(pr.getId(), vence))));
    }

    private void vender(Presentacion pr, int cantidad) {
        ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(UUID.randomUUID().toString(), hoy,
                "PUBLICO", List.of(new DetalleVentaRequest(pr.getId(), cantidad))));
    }

    private void autenticar() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(usuario.getEmail(), null));
    }

    /**
     * Una peticion ANONIMA de verdad: se vacia el contexto de seguridad del hilo de prueba (si no, MockMvc, que corre
     * en este mismo hilo, la trataria como autenticada) y, como el filtro de seguridad lo limpia al terminar cada
     * peticion, se vuelve a autenticar para que el resto de la prueba pueda seguir usando los servicios.
     */
    private <T> T anonimo(java.util.concurrent.Callable<T> peticion) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            return peticion.call();
        } finally {
            autenticar();
        }
    }

    private String cuerpoDelCatalogo() throws Exception {
        return anonimo(() -> mvc.perform(get("/api/catalogo/productos")).andExpect(status().isOk()) // SIN token
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode catalogo() throws Exception {
        return json.readTree(cuerpoDelCatalogo());
    }

    private JsonNode producto(JsonNode catalogo, Producto p) {
        for (JsonNode n : catalogo.get("productos")) if (n.get("id").asText().equals(p.getId().toString())) return n;
        return null;
    }

    private JsonNode presentacion(JsonNode catalogo, Producto p, Presentacion pr) {
        JsonNode prod = producto(catalogo, p);
        if (prod == null) return null;
        for (JsonNode n : prod.get("presentaciones")) if (n.get("id").asText().equals(pr.getId().toString())) return n;
        return null;
    }

    private String disponibilidad(Producto p, Presentacion pr) throws Exception {
        JsonNode n = presentacion(catalogo(), p, pr);
        assertThat(n).as("la presentacion debe estar listada").isNotNull();
        return n.get("disponibilidad").asText();
    }

    // ------------------------------------------------------------------ disponibilidad = stock VENDIBLE

    @Test
    void laDisponibilidadSigueAlStockVendibleNoAlFisico() throws Exception {
        Producto p = nuevoProducto(false);
        Presentacion pr = nuevaPresentacion(p, "Botella 1 L");
        precio(pr, "PUBLICO", "12.00", hoy.minusDays(10));
        ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), 104, "Existencias anteriores"));
        controlService.activar(p.getId()); // las 104 quedan SIN TRAZABILIDAD: fisico 104, vendible 0

        assertThat(disponibilidad(p, pr)).as("104 fisicas pero ninguna vendible").isEqualTo("AGOTADO");

        producir(p, pr, 50, hoy.minusDays(1)); // lote YA VENCIDO (vence ayer)
        assertThat(disponibilidad(p, pr)).as("lo vencido tampoco cuenta").isEqualTo("AGOTADO");

        producir(p, pr, 3, hoy.plusDays(10));
        assertThat(disponibilidad(p, pr)).isEqualTo("POCAS_UNIDADES");
        assertThat(presentacion(catalogo(), p, pr).get("unidadesDisponibles").asInt()).as("pocas: se informa cuantas quedan").isEqualTo(3);

        producir(p, pr, 2, hoy);  // vence HOY: ultimo dia valido, cuenta (3 + 2 = 5 = umbral)
        assertThat(disponibilidad(p, pr)).isEqualTo("POCAS_UNIDADES");

        producir(p, pr, 1, hoy.plusDays(20)); // 6 vendibles: por encima del umbral
        assertThat(disponibilidad(p, pr)).isEqualTo("DISPONIBLE");
        assertThat(presentacion(catalogo(), p, pr).get("unidadesDisponibles").isNull()).as("con mas stock no se publica la cantidad").isTrue();
    }

    @Test
    void unaVentaBajaLaDisponibilidadYUnAjusteSobranteNoLaSube() throws Exception {
        Producto p = nuevoProducto(true);
        Presentacion pr = nuevaPresentacion(p, "Botella 1 L");
        precio(pr, "PUBLICO", "12.00", hoy.minusDays(10));
        producir(p, pr, 20, hoy.plusDays(15));
        assertThat(disponibilidad(p, pr)).isEqualTo("DISPONIBLE");

        vender(pr, 16); // quedan 4
        assertThat(disponibilidad(p, pr)).isEqualTo("POCAS_UNIDADES");

        ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), 40, "Se hallaron botellas")); // entran sin trazabilidad
        assertThat(disponibilidad(p, pr)).as("lo hallado no tiene fecha: no se vende ni se publica").isEqualTo("POCAS_UNIDADES");

        vender(pr, 4);
        assertThat(disponibilidad(p, pr)).isEqualTo("AGOTADO");
    }

    @Test
    void existenciasInconsistentesSePublicanComoAgotadoSinAfectarAOtrosProductos() throws Exception {
        Producto roto = nuevoProducto(true);
        Presentacion pRoto = nuevaPresentacion(roto, "Botella 1 L");
        precio(pRoto, "PUBLICO", "12.00", hoy.minusDays(10));
        producir(roto, pRoto, 20, hoy.plusDays(15));
        Producto sano = nuevoProducto(true);
        Presentacion pSano = nuevaPresentacion(sano, "Botella 1 L");
        precio(pSano, "PUBLICO", "9.00", hoy.minusDays(10));
        producir(sano, pSano, 20, hoy.plusDays(15));
        // Se corrompe el reparto de UNA presentacion: un reparto "sin trazabilidad" de +5 que el libro no respalda.
        UUID movimientoId = jdbc.queryForObject("SELECT id FROM movimiento_inventario WHERE presentacion_id = ? LIMIT 1", UUID.class, pRoto.getId());
        jdbc.update("INSERT INTO movimiento_inventario_lote (movimiento_id, cantidad) VALUES (?, ?)", movimientoId, 5);

        JsonNode c = catalogo();

        assertThat(presentacion(c, roto, pRoto).get("disponibilidad").asText()).isEqualTo("AGOTADO");
        assertThat(presentacion(c, sano, pSano).get("disponibilidad").asText()).isEqualTo("DISPONIBLE");
    }

    // ------------------------------------------------------------------ que se lista

    @Test
    void soloSeListanProductosConControlDeVencimiento() throws Exception {
        Producto sinControl = nuevoProducto(false);
        Presentacion pr = nuevaPresentacion(sinControl, "Botella 1 L");
        precio(pr, "PUBLICO", "12.00", hoy.minusDays(10));
        ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), 30, "Stock"));
        Producto conControl = nuevoProducto(true);
        Presentacion pc = nuevaPresentacion(conControl, "Botella 1 L");
        precio(pc, "PUBLICO", "12.00", hoy.minusDays(10));

        JsonNode c = catalogo();

        assertThat(producto(c, sinControl)).as("sin control de vencimiento no se publica").isNull();
        assertThat(producto(c, conControl)).isNotNull();
    }

    @Test
    void laPresentacionSinPrecioPublicoVigenteSeListaComoPrecioAConfirmar() throws Exception {
        Producto p = nuevoProducto(true);
        Presentacion conPrecio = nuevaPresentacion(p, "Botella 1 L");
        Presentacion soloMayorista = nuevaPresentacion(p, "Botella 2 L");
        Presentacion sinPrecio = nuevaPresentacion(p, "Botella 500 ml");
        Presentacion precioFuturo = nuevaPresentacion(p, "Botella 250 ml");
        precio(conPrecio, "PUBLICO", "12.00", hoy.minusDays(10));
        precio(soloMayorista, "MAYORISTA", "10.00", hoy.minusDays(10));
        precio(precioFuturo, "PUBLICO", "5.00", hoy.plusDays(7)); // programado: todavia no rige

        JsonNode c = catalogo();

        assertThat(presentacion(c, p, conPrecio).get("precio").decimalValue()).isEqualByComparingTo("12.00");
        assertThat(presentacion(c, p, conPrecio).get("precioAConfirmar").asBoolean()).isFalse();
        // Decision B: sin precio PUBLICO vigente se lista igual, con "Precio a confirmar" y SIN inventar un precio.
        for (Presentacion sinPublico : List.of(soloMayorista, sinPrecio, precioFuturo)) {
            JsonNode n = presentacion(c, p, sinPublico);
            assertThat(n).as("se lista aunque no tenga precio publico vigente").isNotNull();
            assertThat(n.get("precio").isNull()).as("el precio de otra modalidad o a futuro no se publica").isTrue();
            assertThat(n.get("precioAConfirmar").asBoolean()).isTrue();
        }
    }

    @Test
    void siNingunaPresentacionTienePrecioPublicoElProductoApareceConTodasAConfirmar() throws Exception {
        Producto p = nuevoProducto(true);
        Presentacion pr = nuevaPresentacion(p, "Botella 1 L");
        precio(pr, "PROVEEDOR", "9.00", hoy.minusDays(10));

        JsonNode n = presentacion(catalogo(), p, pr);

        assertThat(n).isNotNull();
        assertThat(n.get("precio").isNull()).isTrue();
        assertThat(n.get("precioAConfirmar").asBoolean()).isTrue();
    }

    @Test
    void elPrecioPublicadoEsElPublicoVigenteMasRecienteNuncaUnoFuturoNiDeOtraModalidad() throws Exception {
        Producto p = nuevoProducto(true);
        Presentacion pr = nuevaPresentacion(p, "Botella 1 L");
        precio(pr, "PUBLICO", "10.00", hoy.minusDays(60));
        precio(pr, "PUBLICO", "12.00", hoy.minusDays(1));
        precio(pr, "PUBLICO", "15.00", hoy.plusDays(5));
        precio(pr, "MAYORISTA", "7.00", hoy.minusDays(1));

        assertThat(presentacion(catalogo(), p, pr).get("precio").decimalValue()).isEqualByComparingTo("12.00");
    }

    @Test
    void productosYPresentacionesInactivosNoSeListan() throws Exception {
        Producto inactivo = nuevoProducto(true);
        Presentacion pi = nuevaPresentacion(inactivo, "Botella 1 L");
        precio(pi, "PUBLICO", "12.00", hoy.minusDays(10));
        inactivo.setActivo(false);
        productoRepository.save(inactivo);

        Producto p = nuevoProducto(true);
        Presentacion activa = nuevaPresentacion(p, "Botella 1 L");
        Presentacion desactivada = nuevaPresentacion(p, "Botella 2 L");
        precio(activa, "PUBLICO", "12.00", hoy.minusDays(10));
        precio(desactivada, "PUBLICO", "20.00", hoy.minusDays(10));
        desactivada.setActivo(false);
        presentacionRepository.save(desactivada);

        JsonNode c = catalogo();

        assertThat(producto(c, inactivo)).isNull();
        assertThat(presentacion(c, p, activa)).isNotNull();
        assertThat(presentacion(c, p, desactivada)).isNull();
    }

    // ------------------------------------------------------------------ acceso y datos expuestos

    @Test
    void nadaInternoSeFiltraEnLaRespuestaPublica() throws Exception {
        Producto p = nuevoProducto(true);
        Presentacion pr = nuevaPresentacion(p, "Botella 1 L");
        precio(pr, "PUBLICO", "12.00", hoy.minusDays(10));
        producir(p, pr, 20, hoy.plusDays(15));

        String cuerpo = cuerpoDelCatalogo();

        for (String prohibido : List.of("stock", "lote", "vencimiento", "saldo", "costo", "trazabilidad", "existencia", "usuario", "email")) {
            assertThat(cuerpo.toLowerCase()).as("campo interno: " + prohibido).doesNotContain(prohibido);
        }
        JsonNode n = presentacion(json.readTree(cuerpo), p, pr);
        assertThat(n.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "nombre", "contenidoCantidad", "contenidoUnidad", "unidadConteo", "fotoUrl", "precio", "precioAConfirmar", "disponibilidad",
                "unidadesDisponibles", "incluyeIgv");
        assertThat(n.get("unidadesDisponibles").isNull()).as("con 20 vendibles no se publica la cantidad").isTrue();
        // Fase G: con el calculo de IGV apagado (por defecto) ningun precio dice "Incluye IGV"
        assertThat(n.get("incluyeIgv").asBoolean()).isFalse();
    }

    @Test
    void elCatalogoEsDeSoloLecturaPublicaOtrosMetodosExigenToken() throws Exception {
        anonimo(() -> mvc.perform(post("/api/catalogo/productos")).andExpect(status().isUnauthorized()));
        anonimo(() -> mvc.perform(get("/api/catalogo/otra-cosa")).andExpect(status().isUnauthorized()));
    }
}
