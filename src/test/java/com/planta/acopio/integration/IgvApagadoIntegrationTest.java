package com.planta.acopio.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.dto.AjusteInventarioRequest;
import com.planta.acopio.dto.CrearVentaCarritoRequest;
import com.planta.acopio.dto.DetalleVentaRequest;
import com.planta.acopio.dto.PrecioQuesoRequest;
import com.planta.acopio.dto.VentaRequest;
import com.planta.acopio.dto.VentaResponse;
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
 * IGV con la configuracion POR DEFECTO (calculo apagado, decision del usuario hasta que confirme el contador): aunque haya una tasa
 * y una presentacion clasificada como gravada, la venta se registra exactamente como antes (sin foto tributaria, mismo total), el
 * contrato movil no cambia y el estado de la configuracion es PENDIENTE.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IgvApagadoIntegrationTest {

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

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void conElCalculoApagadoLaConfiguracionPreparadaNoCambiaNingunaVenta() throws Exception {
        String admin = token();
        LocalDate hoy = LocalDate.now(java.time.ZoneId.of("America/Lima"));
        Presentacion p = presentacionConStock("23.60", 10);

        // se PREPARA la configuracion: tasa y presentacion gravada
        mvc.perform(post("/api/admin/tributario/tasas-igv").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tasa\":18,\"vigenteDesde\":\"" + hoy.minusDays(5) + "\",\"motivo\":\"Preparada, sin confirmar\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/admin/tributario/presentaciones/" + p.getId() + "/afectaciones").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"afectacion\":\"GRAVADO\",\"vigenteDesde\":\"" + hoy.minusDays(5) + "\",\"motivo\":\"Preparada, sin confirmar\"}"))
                .andExpect(status().isCreated());

        MvcResult estado = mvc.perform(get("/api/admin/tributario/estado").header("Authorization", admin)).andExpect(status().isOk()).andReturn();
        JsonNode e = json.readTree(estado.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(e.get("estado").asText()).isEqualTo("PENDIENTE");
        assertThat(e.get("calculoHabilitado").asBoolean()).isFalse();
        assertThat(e.get("tasaVigente").get("tasa").decimalValue()).isEqualByComparingTo("18");

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null));
        VentaResumenResponse web = ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(UUID.randomUUID().toString(), hoy,
                "PUBLICO", List.of(new DetalleVentaRequest(p.getId(), 2))));
        assertThat(web.total()).isEqualByComparingTo("47.20");
        assertThat(web.detalles().get(0).afectacionIgv()).isNull();
        assertThat(web.detalles().get(0).igv()).isNull();
        assertThat(web.igv().estado()).isEqualTo("SIN_DETERMINAR");
        assertThat(web.igv().incluyeIgv()).isFalse();

        // contrato movil (una linea): misma forma y mismo total
        VentaResponse movil = ventaService.registrarOIgnorarSiDuplicado(new VentaRequest(UUID.randomUUID().toString(), hoy, "PUBLICO",
                p.getId(), 1, new BigDecimal("23.60")));
        assertThat(movil.total()).isEqualByComparingTo("23.60");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venta_detalle d JOIN venta v ON v.id = d.venta_id WHERE d.presentacion_id = ? "
                + "AND (d.afectacion_igv IS NOT NULL OR d.igv IS NOT NULL OR d.valor_venta IS NOT NULL OR d.tasa_igv IS NOT NULL)", Integer.class, p.getId()))
                .isZero();
        String cuerpoMovil = json.writeValueAsString(movil);
        assertThat(cuerpoMovil).doesNotContain("igv").doesNotContain("afectacion");
    }

    private Presentacion presentacionConStock(String precio, int stock) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null));
        String s = String.valueOf(System.nanoTime());
        Familia f = new Familia();
        f.setNombre("Familia IGV off " + s);
        Producto p = new Producto();
        p.setNombre("Producto IGV off " + s);
        p.setFamilia(familiaRepository.save(f));
        p = productoRepository.save(p);
        Presentacion pr = new Presentacion();
        pr.setNombre("Unidad");
        pr.setProducto(p);
        pr.setUnidadConteo("unidad");
        pr = presentacionRepository.save(pr);
        precioQuesoService.crear(new PrecioQuesoRequest(pr.getId(), "PUBLICO", new BigDecimal(precio), LocalDate.of(2000, 1, 1)));
        if (stock > 0) ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), stock, "Stock inicial de la prueba"));
        SecurityContextHolder.clearContext();
        return pr;
    }

    private String token() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@planta.com\",\"password\":\"Admin1234\"}"))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + json.readTree(login.getResponse().getContentAsString()).get("token").asText();
    }
}
