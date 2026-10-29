package com.planta.acopio.integration;

import com.planta.acopio.dto.AlertasLotesResponse;
import com.planta.acopio.dto.IngresoDocumentoRequest;
import com.planta.acopio.dto.InsumoRequest;
import com.planta.acopio.dto.KardexResponse;
import com.planta.acopio.dto.MovimientoKardexResponse;
import com.planta.acopio.dto.ProveedorCompraRequest;
import com.planta.acopio.dto.SalidaInventarioRequest;
import com.planta.acopio.entity.MotivoSalida;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.TipoDocumentoIngreso;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.IngresoInsumoService;
import com.planta.acopio.service.InsumoService;
import com.planta.acopio.service.KardexService;
import com.planta.acopio.service.LoteInsumoConsultaService;
import com.planta.acopio.service.ProveedorCompraService;
import com.planta.acopio.service.SalidaInventarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kardex de insumos con lotes (V43/V44) contra PostgreSQL real: cada fila trae su lote, proveedor, vencimiento y documento de ingreso;
 * el filtro por lote da el saldo y los totales DEL LOTE; y las alertas listan los lotes con existencia por vencer o vencidos.
 */
@SpringBootTest
class KardexLotesIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    @Autowired private InsumoService insumoService;
    @Autowired private IngresoInsumoService ingresoService;
    @Autowired private SalidaInventarioService salidaService;
    @Autowired private ProveedorCompraService proveedorService;
    @Autowired private KardexService kardexService;
    @Autowired private LoteInsumoConsultaService loteConsulta;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private JdbcTemplate jdbc;

    private String s;
    private UUID azucar;
    private final PageRequest todo = PageRequest.of(0, 200);

    @BeforeEach
    void seed() {
        s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("KARDEX_LOTE_" + s);
        rol = rolRepository.save(rol);
        Usuario u = new Usuario();
        u.setNombre("Kardex Lote " + s);
        u.setEmail("kardex-lote-" + s + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        usuarioRepository.save(u);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u.getEmail(), null));
        azucar = insumoService.crear(new InsumoRequest("Azucar kardex lote " + s, "kg", new BigDecimal("10"))).id();
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private UUID loteDe(String codigo) {
        return jdbc.queryForObject("SELECT id FROM lote_insumo WHERE insumo_id = ? AND codigo = ?", UUID.class, azucar, codigo);
    }

    @Test
    void cadaFilaDelKardexTraeSuLoteProveedorVencimientoYDocumento() {
        UUID proveedor = proveedorService.crear(new ProveedorCompraRequest("Distribuidora Kardex " + s, rucLibre())).id();
        LocalDate vence = LocalDate.now().plusDays(90);
        ingresoService.registrar(new IngresoDocumentoRequest(null, proveedor, TipoDocumentoIngreso.FACTURA, "F010-" + s, LocalDate.now(), null,
                List.of(new IngresoDocumentoRequest.Linea(azucar, new BigDecimal("100"), "LOTE-K1", "PV-9", vence))));
        salidaService.registrar(azucar, true, new SalidaInventarioRequest(UUID.randomUUID(), new BigDecimal("4"), MotivoSalida.MERMA, "humedad", "INSUMO", loteDe("LOTE-K1")));

        KardexResponse k = kardexService.insumo(azucar, null, null, todo);
        List<MovimientoKardexResponse> filas = k.movimientos().contenido();

        assertThat(filas).hasSize(3);   // saldo inicial (INICIAL), ingreso (LOTE-K1), salida (LOTE-K1)
        assertThat(filas.get(0).loteCodigo()).isEqualTo("INICIAL");
        assertThat(filas.get(0).proveedorNombre()).isNull();
        MovimientoKardexResponse ingreso = filas.get(1);
        assertThat(ingreso.loteCodigo()).isEqualTo("LOTE-K1");
        assertThat(ingreso.proveedorNombre()).startsWith("Distribuidora Kardex");
        assertThat(ingreso.fechaVencimiento()).isEqualTo(vence);
        assertThat(ingreso.ingresoDocumentoTipo()).isEqualTo("FACTURA");
        assertThat(ingreso.ingresoDocumentoNumero()).isEqualTo("F010-" + s);
        assertThat(ingreso.referencia()).isEqualTo("Factura F010-" + s);
        assertThat(filas.get(2).loteCodigo()).isEqualTo("LOTE-K1");   // la salida tambien dice de que lote
        assertThat(filas.get(2).saldo()).isEqualByComparingTo("106");
    }

    @Test
    void elFiltroPorLoteDaElSaldoYLosTotalesDelLote() {
        ingresoService.registrar(new IngresoDocumentoRequest(null, null, null, null, null, null,
                List.of(new IngresoDocumentoRequest.Linea(azucar, new BigDecimal("50"), "LOTE-A", null, null))));
        ingresoService.registrar(new IngresoDocumentoRequest(null, null, null, null, null, null,
                List.of(new IngresoDocumentoRequest.Linea(azucar, new BigDecimal("30"), "LOTE-B", null, null))));
        salidaService.registrar(azucar, true, new SalidaInventarioRequest(UUID.randomUUID(), new BigDecimal("5"), MotivoSalida.OTRA, "x", "INSUMO", loteDe("LOTE-A")));

        KardexResponse a = kardexService.insumo(azucar, null, null, loteDe("LOTE-A"), todo);
        assertThat(a.movimientos().contenido()).extracting(MovimientoKardexResponse::loteCodigo).containsOnly("LOTE-A");
        assertThat(a.movimientos().contenido()).extracting(MovimientoKardexResponse::saldo).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("50"), new BigDecimal("45"));          // saldo del lote, no del insumo
        assertThat(a.totalEntradas()).isEqualByComparingTo("50");
        assertThat(a.totalSalidas()).isEqualByComparingTo("5");
        assertThat(a.saldoFinal()).isEqualByComparingTo("45");

        KardexResponse todoElInsumo = kardexService.insumo(azucar, null, null, null, todo);
        assertThat(todoElInsumo.saldoFinal()).isEqualByComparingTo("85");              // 10 + 50 + 30 - 5
        assertThat(todoElInsumo.movimientos().contenido()).hasSize(4);
    }

    @Test
    void lasAlertasListanLosLotesPorVencerYVencidosConExistenciaYNadaMas() {
        LocalDate hoy = LocalDate.now();
        for (Object[] l : new Object[][]{{"POR-VENCER", 5L}, {"VIGENTE-LEJOS", 60L}, {"VENCIDO", -3L}, {"AGOTADO-PRONTO", 2L}}) {
            ingresoService.registrar(new IngresoDocumentoRequest(null, null, null, null, null, null,
                    List.of(new IngresoDocumentoRequest.Linea(azucar, new BigDecimal("2"), (String) l[0] + "-" + s, null, hoy.plusDays(Math.max(1, (Long) l[1]))))));
        }
        jdbc.update("UPDATE lote_insumo SET fecha_vencimiento = ? WHERE insumo_id = ? AND codigo = ?", hoy.minusDays(3), azucar, "VENCIDO-" + s);
        salidaService.registrar(azucar, true, new SalidaInventarioRequest(UUID.randomUUID(), new BigDecimal("2"), MotivoSalida.OTRA, "x", "INSUMO",
                jdbc.queryForObject("SELECT id FROM lote_insumo WHERE insumo_id = ? AND codigo = ?", UUID.class, azucar, "AGOTADO-PRONTO-" + s)));

        AlertasLotesResponse r = loteConsulta.alertas();
        List<String> codigos = r.lotes().stream().filter(l -> l.insumoId().equals(azucar)).map(l -> l.codigo()).toList();

        assertThat(r.diasAlerta()).isEqualTo(15);
        assertThat(codigos).containsExactly("VENCIDO-" + s, "POR-VENCER-" + s);   // vencidos primero; el lejano y el agotado no avisan
        assertThat(r.vencidos()).isGreaterThanOrEqualTo(1);
        assertThat(r.porVencer()).isGreaterThanOrEqualTo(1);
    }

    private String rucLibre() {
        for (String r : new String[]{"20100070970", "20600000013", "20500000024", "20700000037", "20800000042"}) {
            if (jdbc.queryForObject("SELECT count(*) FROM proveedor_compra WHERE ruc = ?", Integer.class, r) == 0) return r;
        }
        throw new IllegalStateException("sin RUC de prueba libre");
    }
}
