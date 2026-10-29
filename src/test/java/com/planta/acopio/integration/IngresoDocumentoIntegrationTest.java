package com.planta.acopio.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.dto.DocumentoIngresoResponse;
import com.planta.acopio.dto.IngresoDocumentoRequest;
import com.planta.acopio.dto.InsumoRequest;
import com.planta.acopio.dto.ProveedorCompraRequest;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.TipoDocumentoIngreso;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.IngresoInsumoService;
import com.planta.acopio.service.InsumoService;
import com.planta.acopio.service.ProveedorCompraService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ingreso de insumos con documento (V44) contra PostgreSQL real: una factura con varios insumos crea un lote por linea (con su
 * proveedor, documento, vencimiento y codigo propio o autogenerado), todo o nada, idempotente por uuidCliente, sin cargar dos
 * veces el mismo documento del mismo proveedor, y con la invariante stock = suma de lotes = suma de movimientos.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IngresoDocumentoIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    private static final String RUC_A = "20100070970";
    private static final String RUC_B = "20600000013";

    @Autowired private IngresoInsumoService ingresoService;
    @Autowired private InsumoService insumoService;
    @Autowired private ProveedorCompraService proveedorService;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private String s;
    private UUID azucar;
    private UUID sal;
    private UUID proveedor;

    @BeforeEach
    void seed() {
        s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("INGRESO_IT_" + s);
        rol = rolRepository.save(rol);
        Usuario u = new Usuario();
        u.setNombre("Ingreso Test " + s);
        u.setEmail("ingreso-" + s + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        usuarioRepository.save(u);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u.getEmail(), null));
        azucar = insumoService.crear(new InsumoRequest("Azucar ingreso " + s, "kg", new BigDecimal("10"))).id();
        sal = insumoService.crear(new InsumoRequest("Sal ingreso " + s, "kg", BigDecimal.ZERO)).id();
        // RUC distinto por prueba (el RUC es unico): 20100070970 y 20600000013 se alternan segun la corrida de la clase.
        proveedor = proveedorNuevo();
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private UUID proveedorNuevo() {
        String ruc = jdbc.queryForObject("SELECT count(*) FROM proveedor_compra WHERE ruc = ?", Integer.class, RUC_A) == 0 ? RUC_A : RUC_B;
        if (jdbc.queryForObject("SELECT count(*) FROM proveedor_compra WHERE ruc = ?", Integer.class, ruc) > 0) {
            return jdbc.queryForObject("SELECT id FROM proveedor_compra WHERE ruc = ?", UUID.class, ruc);
        }
        return proveedorService.crear(new ProveedorCompraRequest("Distribuidora " + s, ruc)).id();
    }

    private IngresoDocumentoRequest factura(String numero, UUID uuid, IngresoDocumentoRequest.Linea... lineas) {
        return new IngresoDocumentoRequest(uuid, proveedor, TipoDocumentoIngreso.FACTURA, numero, LocalDate.now(), "Compra semanal", List.of(lineas));
    }

    private IngresoDocumentoRequest.Linea linea(UUID insumo, String cantidad, String codigo, String loteProv, LocalDate vence) {
        return new IngresoDocumentoRequest.Linea(insumo, new BigDecimal(cantidad), codigo, loteProv, vence);
    }

    private void invariantes(UUID insumoId) {
        BigDecimal stock = jdbc.queryForObject("SELECT stock FROM insumo WHERE id = ?", BigDecimal.class, insumoId);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(cantidad_disponible), 0) FROM lote_insumo WHERE insumo_id = ?", BigDecimal.class, insumoId)).isEqualByComparingTo(stock);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_insumo WHERE insumo_id = ?", BigDecimal.class, insumoId)).isEqualByComparingTo(stock);
    }

    @Test
    void unaFacturaConVariosInsumosCreaUnLotePorLineaConSuProveedorDocumentoYVencimiento() {
        LocalDate vence = LocalDate.now().plusDays(90);
        DocumentoIngresoResponse r = ingresoService.registrar(factura("F001-0234", null,
                linea(azucar, "100", "LOTE-A1", "PROV-77", vence),
                linea(sal, "40", null, null, null),
                linea(azucar, "50", null, null, vence.plusDays(30))));

        assertThat(r.lineas()).hasSize(3);
        assertThat(r.proveedorNombre()).startsWith("Distribuidora");
        assertThat(r.numeroDocumento()).isEqualTo("F001-0234");
        assertThat(r.lineas().get(0).loteCodigo()).isEqualTo("LOTE-A1");
        assertThat(r.lineas().get(0).loteProveedor()).isEqualTo("PROV-77");
        assertThat(r.lineas().get(0).fechaVencimiento()).isEqualTo(vence);
        assertThat(r.lineas().get(0).saldoAnterior()).isEqualByComparingTo("10");
        assertThat(r.lineas().get(0).saldoResultante()).isEqualByComparingTo("110");
        assertThat(r.lineas().get(1).loteCodigo()).startsWith("SAL-");   // autogenerado
        assertThat(r.lineas().get(2).saldoResultante()).isEqualByComparingTo("160");   // el mismo insumo dos veces: acumula

        // Cada lote sabe su proveedor y su documento; el kardex tiene un INGRESO por lote con el documento como referencia.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lote_insumo WHERE documento_ingreso_id = ? AND proveedor_id = ?", Integer.class, r.id(), proveedor)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT m.referencia FROM movimiento_insumo m JOIN lote_insumo l ON l.id = m.lote_insumo_id WHERE l.documento_ingreso_id = ?", String.class, r.id()))
                .containsOnly("Factura F001-0234");
        assertThat(jdbc.queryForObject("SELECT stock FROM insumo WHERE id = ?", BigDecimal.class, azucar)).isEqualByComparingTo("160");
        invariantes(azucar);
        invariantes(sal);
        // Se puede consultar despues.
        assertThat(ingresoService.obtener(r.id()).lineas()).hasSize(3);
    }

    @Test
    void reenviarElMismoUuidDevuelveElMismoDocumentoSinDuplicarNada() {
        UUID uuid = UUID.randomUUID();
        DocumentoIngresoResponse a = ingresoService.registrar(factura("F002-1", uuid, linea(azucar, "20", null, null, null)));
        DocumentoIngresoResponse b = ingresoService.registrar(factura("F002-1", uuid, linea(azucar, "20", null, null, null)));

        assertThat(b.id()).isEqualTo(a.id());
        assertThat(jdbc.queryForObject("SELECT stock FROM insumo WHERE id = ?", BigDecimal.class, azucar)).isEqualByComparingTo("30");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lote_insumo WHERE documento_ingreso_id = ?", Integer.class, a.id())).isEqualTo(1);
    }

    @Test
    void elMismoDocumentoDelMismoProveedorNoSeCargaDosVecesPeroOtroTipoOProveedorSi() {
        ingresoService.registrar(factura("F003-9", null, linea(azucar, "5", null, null, null)));

        assertThatThrownBy(() -> ingresoService.registrar(factura("f003-9", null, linea(azucar, "5", null, null, null))))
                .isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("ya fue cargado");
        assertThat(jdbc.queryForObject("SELECT stock FROM insumo WHERE id = ?", BigDecimal.class, azucar)).isEqualByComparingTo("15");   // el rechazo no dejo nada

        // Una guia con el mismo numero es otro documento.
        ingresoService.registrar(new IngresoDocumentoRequest(null, proveedor, TipoDocumentoIngreso.GUIA, "F003-9", null, null,
                List.of(linea(azucar, "5", null, null, null))));
        invariantes(azucar);
    }

    @Test
    void todoONadaUnaLineaInvalidaRevierteElDocumentoCompleto() {
        assertThatThrownBy(() -> ingresoService.registrar(factura("F004-1", null,
                linea(azucar, "10", "REPETIDO", null, null),
                linea(sal, "5", "REPETIDO", null, null),
                linea(azucar, "3", "REPETIDO", null, null))))   // mismo insumo y mismo codigo de lote: conflicto
                .isInstanceOf(ConflictoNegocioException.class);

        assertThat(jdbc.queryForObject("SELECT stock FROM insumo WHERE id = ?", BigDecimal.class, azucar)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT stock FROM insumo WHERE id = ?", BigDecimal.class, sal)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documento_ingreso WHERE numero = 'F004-1'", Integer.class)).isZero();
        invariantes(azucar);
    }

    @Test
    void validaNumeroFechasVencimientoYProveedor() {
        assertThatThrownBy(() -> ingresoService.registrar(new IngresoDocumentoRequest(null, proveedor, TipoDocumentoIngreso.FACTURA, "  ", null, null,
                List.of(linea(azucar, "1", null, null, null))))).isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("número");
        assertThatThrownBy(() -> ingresoService.registrar(new IngresoDocumentoRequest(null, null, TipoDocumentoIngreso.GUIA, "G-1", LocalDate.now().plusDays(3), null,
                List.of(linea(azucar, "1", null, null, null))))).isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("futura");
        assertThatThrownBy(() -> ingresoService.registrar(factura("F005-1", null, linea(azucar, "1", null, null, LocalDate.now().minusDays(1)))))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("vencido");
        proveedorService.desactivar(proveedor);
        assertThatThrownBy(() -> ingresoService.registrar(factura("F005-2", null, linea(azucar, "1", null, null, null))))
                .hasMessageContaining("desactivado");
        proveedorService.reactivar(proveedor);

        // Sin documento: no pide numero y no choca con nada; sin proveedor tambien vale.
        DocumentoIngresoResponse sinDoc = ingresoService.registrar(new IngresoDocumentoRequest(null, null, null, null, null, null, List.of(linea(azucar, "2", null, null, null))));
        assertThat(sinDoc.tipoDocumento()).isEqualTo("SIN_DOCUMENTO");
        assertThat(sinDoc.numeroDocumento()).isNull();
        invariantes(azucar);
    }

    @Test
    void porHttpPRODUCCIONRegistraUnaFacturaYUnRolAjenoNo() throws Exception {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        String cuerpo = "{\"proveedorId\":\"" + proveedor + "\",\"tipoDocumento\":\"FACTURA\",\"numeroDocumento\":\"F900-" + s + "\","
                + "\"lineas\":[{\"insumoId\":\"" + azucar + "\",\"cantidad\":12.5,\"fechaVencimiento\":\"" + LocalDate.now().plusDays(60) + "\"}]}";
        mvc.perform(post("/api/inventario/ingresos").with(user(email).roles("VENTAS"))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andExpect(status().isForbidden());
        var res = mvc.perform(post("/api/inventario/ingresos").with(user(email).roles("PRODUCCION"))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andExpect(status().isCreated()).andReturn();
        JsonNode doc = json.readTree(res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(doc.get("lineas").get(0).get("loteCodigo").asText()).startsWith("AZU-");
        // Sin lineas: validacion 400.
        mvc.perform(post("/api/inventario/ingresos").with(user("x").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lineas\":[]}")).andExpect(status().isBadRequest());
    }
}
