package com.planta.acopio.integration;

import com.planta.acopio.dto.IngresoDocumentoRequest;
import com.planta.acopio.dto.InsumoRequest;
import com.planta.acopio.dto.ProveedorCompraRequest;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.TipoDocumentoIngreso;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.IngresoInsumoService;
import com.planta.acopio.service.InsumoService;
import com.planta.acopio.service.ProveedorCompraService;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exportacion a Excel del kardex de un insumo (con lote, proveedor, vencimiento y documento; respeta los filtros de fecha y lote) y de la
 * lista de lotes, por HTTP con la cadena real de seguridad y PostgreSQL real.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExportacionInsumosIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    @Autowired private MockMvc mvc;
    @Autowired private InsumoService insumoService;
    @Autowired private IngresoInsumoService ingresoService;
    @Autowired private ProveedorCompraService proveedorService;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private JdbcTemplate jdbc;

    private String s;
    private String email;
    private UUID azucar;
    private UUID loteA;

    @BeforeEach
    void seed() {
        s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("EXP_INS_" + s);
        rol = rolRepository.save(rol);
        Usuario u = new Usuario();
        u.setNombre("Exp Insumos " + s);
        u.setEmail("exp-ins-" + s + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        usuarioRepository.save(u);
        email = u.getEmail();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(email, null));
        azucar = insumoService.crear(new InsumoRequest("Azucar export " + s, "kg", new BigDecimal("10"))).id();
        UUID proveedor = proveedorService.crear(new ProveedorCompraRequest("Distribuidora Export " + s, rucLibre())).id();
        ingresoService.registrar(new IngresoDocumentoRequest(null, proveedor, TipoDocumentoIngreso.FACTURA, "F500-" + s, LocalDate.now(), null,
                List.of(new IngresoDocumentoRequest.Linea(azucar, new BigDecimal("100"), "LOTE-X-" + s, "PV-1", LocalDate.now().plusDays(7)))));
        loteA = jdbc.queryForObject("SELECT id FROM lote_insumo WHERE insumo_id = ? AND codigo = ?", UUID.class, azucar, "LOTE-X-" + s);
        SecurityContextHolder.clearContext();
    }

    private String rucLibre() {
        for (String r : new String[]{"20100070970", "20600000013", "20500000024", "20700000037", "20800000042"}) {
            if (jdbc.queryForObject("SELECT count(*) FROM proveedor_compra WHERE ruc = ?", Integer.class, r) == 0) return r;
        }
        return jdbc.queryForObject("SELECT ruc FROM proveedor_compra LIMIT 1", String.class);
    }

    private List<List<String>> filas(String url, String rol, String... params) throws Exception {
        var req = get(url).with(user(email).roles(rol));
        for (int i = 0; i < params.length; i += 2) req.param(params[i], params[i + 1]);
        byte[] bytes = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Sheet hoja = new XSSFWorkbook(new ByteArrayInputStream(bytes)).getSheetAt(0);
        DataFormatter f = new DataFormatter();
        List<List<String>> r = new ArrayList<>();
        for (Row fila : hoja) {
            List<String> celdas = new ArrayList<>();
            for (int c = 0; c < fila.getLastCellNum(); c++) celdas.add(f.formatCellValue(fila.getCell(c)));
            r.add(celdas);
        }
        return r;
    }

    @Test
    void elKardexExportadoTraeLoteProveedorVencimientoYDocumentoYRespetaElFiltroDeLote() throws Exception {
        List<List<String>> todo = filas("/api/exportaciones/kardex-insumo/" + azucar, "PRODUCCION");
        assertThat(todo.get(0)).containsSubsequence("Fecha", "Movimiento", "Documento", "Entrada", "Salida", "Saldo", "Usuario", "Motivo", "Lote", "Proveedor", "Vencimiento",
                "Tipo de documento", "Número de documento");
        assertThat(todo).hasSize(3);                                   // encabezado + saldo inicial + ingreso
        List<String> ingreso = todo.get(2);
        assertThat(ingreso).contains("Ingreso", "LOTE-X-" + s, "Factura", "F500-" + s);
        assertThat(ingreso.stream().anyMatch(c -> c.startsWith("Distribuidora Export"))).isTrue();
        assertThat(todo.get(1)).contains("Saldo inicial", "INICIAL");

        List<List<String>> soloLote = filas("/api/exportaciones/kardex-insumo/" + azucar, "ADMIN", "loteId", loteA.toString());
        assertThat(soloLote).hasSize(2);                               // encabezado + el ingreso del lote
        assertThat(soloLote.get(1)).contains("LOTE-X-" + s);
        assertThat(soloLote.get(1)).contains("100.00");                 // saldo del lote, no del insumo (110)

        List<List<String>> fueraDeRango = filas("/api/exportaciones/kardex-insumo/" + azucar, "ADMIN", "desde", LocalDate.now().plusDays(2).toString());
        assertThat(fueraDeRango).hasSize(1);                           // solo encabezados
    }

    @Test
    void laListaDeLotesExportaCodigoInsumoProveedorRucFechasCantidadesYEstado() throws Exception {
        List<List<String>> lotes = filas("/api/exportaciones/lotes-insumo", "PRODUCCION", "insumoId", azucar.toString());
        assertThat(lotes.get(0)).containsExactly("Código de lote", "Insumo", "Proveedor", "RUC", "Lote del proveedor", "Fecha de ingreso", "Vencimiento",
                "Cantidad inicial", "Disponible", "Unidad", "Estado", "Documento de ingreso");
        assertThat(lotes).hasSize(3);                                  // INICIAL + el lote del ingreso
        List<String> fila = lotes.stream().filter(l -> l.get(0).equals("LOTE-X-" + s)).findFirst().orElseThrow();
        assertThat(fila.get(1)).isEqualTo("Azucar export " + s);
        assertThat(fila.get(2)).startsWith("Distribuidora Export");
        assertThat(fila.get(4)).isEqualTo("PV-1");
        assertThat(fila.get(7)).isEqualTo("100.00");
        assertThat(fila.get(9)).isEqualTo("kg");
        assertThat(fila.get(10)).isEqualTo("Por vencer");
        assertThat(fila.get(11)).isEqualTo("Factura F500-" + s);

        assertThat(filas("/api/exportaciones/lotes-insumo", "ADMIN", "insumoId", azucar.toString(), "estado", "POR_VENCER")).hasSize(2);
        assertThat(filas("/api/exportaciones/lotes-insumo", "ADMIN", "insumoId", azucar.toString(), "busqueda", "pv-1")).hasSize(2);
    }

    @Test
    void soloPRODUCCIONYADMINExportan() throws Exception {
        mvc.perform(get("/api/exportaciones/lotes-insumo").with(user(email).roles("VENTAS"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/exportaciones/kardex-insumo/" + azucar).with(user(email).roles("CALIDAD"))).andExpect(status().isForbidden());
    }
}
