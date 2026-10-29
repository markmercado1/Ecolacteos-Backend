package com.planta.acopio.integration;

import com.planta.acopio.dto.InsumoRequest;
import com.planta.acopio.dto.IngresoInsumoRequest;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.importacion.EstadoFila;
import com.planta.acopio.importacion.ExcelDePrueba;
import com.planta.acopio.importacion.FilaImportacionResponse;
import com.planta.acopio.importacion.ImportacionService;
import com.planta.acopio.importacion.ResultadoImportacionResponse;
import com.planta.acopio.importacion.VistaPreviaImportacionResponse;
import com.planta.acopio.service.InsumoService;
import com.planta.acopio.service.InventarioInsumoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Importacion de ingresos de insumos con lotes y de saldos iniciales contra PostgreSQL real, con el MISMO flujo del resto de importaciones
 * (plantilla, vista previa, confirmacion, reporte): grupos por documento, nada a medias sin confirmacion expresa, idempotencia por archivo y
 * por documento, el proveedor que no existe se crea, y el saldo inicial se reemplaza por lotes reales sin cambiar el stock.
 */
@SpringBootTest
class ImportacionIngresosYSaldosIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Autowired private ImportacionService importacionService;
    @Autowired private InsumoService insumoService;
    @Autowired private InventarioInsumoService inventarioInsumoService;
    @Autowired private JdbcTemplate jdbc;

    private String s;
    private UUID azucar;
    private UUID sal;
    private byte[] plantillaIngresos;
    private byte[] plantillaSaldos;

    @BeforeEach
    void preparar() {
        s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null, List.of()));
        azucar = insumoService.crear(new InsumoRequest("Azúcar imp " + s, "kg", new BigDecimal("112"))).id();
        sal = insumoService.crear(new InsumoRequest("Sal imp " + s, "kg", new BigDecimal("10"))).id();
        plantillaIngresos = ExcelDePrueba.bytes(importacionService.plantilla("ingresos-insumo"));
        plantillaSaldos = ExcelDePrueba.bytes(importacionService.plantilla("saldos-iniciales"));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private String rucNuevo() {
        // RUC valido y libre en esta base (se generan a partir de un contador con el verificador correcto).
        int[] pesos = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
        for (long base = 2_000_000_000L + Long.parseLong(s) % 90_000_000L; ; base++) {
            String d = String.valueOf(base);
            int suma = 0;
            for (int i = 0; i < 10; i++) suma += (d.charAt(i) - '0') * pesos[i];
            int v = 11 - suma % 11;
            if (v == 10) v = 0;
            if (v == 11) v = 1;
            String ruc = d + v;
            if (jdbc.queryForObject("SELECT count(*) FROM proveedor_compra WHERE ruc = ?", Integer.class, ruc) == 0) return ruc;
        }
    }

    private VistaPreviaImportacionResponse previsualizarIngresos(List<List<Object>> filas) {
        return importacionService.previsualizar("ingresos-insumo", new MockMultipartFile("archivo", "ingresos.xlsx", "application/octet-stream",
                ExcelDePrueba.conFilas(plantillaIngresos, filas)));
    }

    private VistaPreviaImportacionResponse previsualizarSaldos(List<List<Object>> filas) {
        return importacionService.previsualizar("saldos-iniciales", new MockMultipartFile("archivo", "saldos.xlsx", "application/octet-stream",
                ExcelDePrueba.conFilas(plantillaSaldos, filas)));
    }

    /** insumo, cantidad, unidad, lote, lote del proveedor, vencimiento, proveedor, RUC, tipo, numero, fecha, observacion */
    private static List<Object> ing(Object insumo, Object cantidad, Object unidad, Object lote, Object loteProv, Object vence, Object proveedor, Object ruc,
                                    Object tipo, Object numero, Object fecha, Object obs) {
        return java.util.Arrays.asList(insumo, cantidad, unidad, lote, loteProv, vence, proveedor, ruc, tipo, numero, fecha, obs);
    }

    private BigDecimal stock(UUID id) {
        return jdbc.queryForObject("SELECT stock FROM insumo WHERE id = ?", BigDecimal.class, id);
    }

    private void invariantes(UUID id) {
        BigDecimal stock = stock(id);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(cantidad_disponible), 0) FROM lote_insumo WHERE insumo_id = ?", BigDecimal.class, id)).isEqualByComparingTo(stock);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_insumo WHERE insumo_id = ?", BigDecimal.class, id)).isEqualByComparingTo(stock);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lote_insumo l WHERE l.insumo_id = ? AND l.cantidad_disponible <> "
                + "(SELECT COALESCE(SUM(m.cantidad), 0) FROM movimiento_insumo m WHERE m.lote_insumo_id = l.id)", Integer.class, id)).isZero();
    }

    private long contar(VistaPreviaImportacionResponse v, EstadoFila e) {
        return v.filas().stream().filter(f -> f.estado().equals(e.name())).count();
    }

    // ----------------------------------------------------------------------------------------------------- ingresos

    @Test
    void unaFacturaConVariasFilasSeAgrupaEnUnDocumentoYElProveedorNuevoSeCreaYSeAvisa() {
        String ruc = rucNuevo();
        String vence = LocalDate.now().plusDays(120).format(DMY);
        VistaPreviaImportacionResponse v = previsualizarIngresos(List.of(
                ing("azúcar imp " + s, "100", "kg", "LOTE-AZ-" + s, "PV-1", vence, "Distribuidora Importada " + s, ruc, "Factura", "F-" + s, LocalDate.now().format(DMY), "Compra semanal"),
                ing("SAL IMP " + s, "40,5", "kg", null, null, null, "Distribuidora Importada " + s, ruc, "factura", "f-" + s, LocalDate.now().format(DMY), null),
                ing("Azucar imp " + s, "5", "kg", null, null, null, null, null, null, null, null, "sin documento")));

        assertThat(v.validas()).isEqualTo(3);
        assertThat(v.invalidas()).isZero();
        assertThat(v.exigeConfirmarParcial()).isFalse();
        assertThat(v.filas().get(0).mensajes()).anySatisfy(m -> assertThat(m).contains("Se creará el proveedor"));   // aviso en la vista previa
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proveedor_compra WHERE ruc = ?", Integer.class, ruc)).isZero();   // la vista previa no crea nada
        assertThat(stock(azucar)).isEqualByComparingTo("112");

        ResultadoImportacionResponse r = importacionService.confirmar(v.id());

        assertThat(r.creadas()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documento_ingreso WHERE numero IN (?, ?)", Integer.class, "F-" + s, "f-" + s)).isEqualTo(1);   // 2 filas = 1 documento
        UUID doc = jdbc.queryForObject("SELECT id FROM documento_ingreso WHERE upper(numero) = ?", UUID.class, ("F-" + s).toUpperCase());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lote_insumo WHERE documento_ingreso_id = ?", Integer.class, doc)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proveedor_compra_categoria pc JOIN categoria_proveedor c ON c.id = pc.categoria_id JOIN proveedor_compra p ON p.id = pc.proveedor_id "
                + "WHERE p.ruc = ? AND c.codigo = 'OTROS_INSUMOS'", Integer.class, ruc)).isEqualTo(1);
        assertThat(stock(azucar)).isEqualByComparingTo("217");          // 112 + 100 + 5
        assertThat(stock(sal)).isEqualByComparingTo("50.50");
        // Cada fila es un movimiento INGRESO del kardex (nunca se toco el stock directamente).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_insumo WHERE tipo_movimiento = 'INGRESO' AND insumo_id = ?", Integer.class, azucar)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT codigo_proveedor FROM lote_insumo WHERE insumo_id = ? AND codigo = ?", String.class, azucar, "LOTE-AZ-" + s)).isEqualTo("PV-1");
        invariantes(azucar);
        invariantes(sal);
    }

    @Test
    void conErroresNoSeImportaANadaSinConfirmacionExpresaYUnDocumentoConUnaFilaInvalidaQuedaFueraCompleto() {
        String ruc = rucNuevo();
        List<List<Object>> filas = List.of(
                ing("Azúcar imp " + s, "10", "kg", null, null, null, "Prov " + s, ruc, "Factura", "OK-" + s, null, null),                       // fila 2: documento bueno
                ing("Azúcar imp " + s, "10", "kg", null, null, null, "Prov " + s, ruc, "Factura", "MALO-" + s, null, null),                     // fila 3: documento con una fila mala...
                ing("Insumo que no existe", "10", "kg", null, null, null, "Prov " + s, ruc, "Factura", "MALO-" + s, null, null),                // fila 4: insumo inexistente
                ing("Sal imp " + s, "0", "kg", null, null, null, null, null, null, null, null, null),                                           // fila 5: cantidad 0
                ing("Sal imp " + s, "5", "litros", null, null, null, null, null, null, null, null, null),                                       // fila 6: unidad distinta
                ing("Sal imp " + s, "5", "kg", null, null, null, "X", "123", null, null, null, null),                                           // fila 7: RUC invalido
                ing("Sal imp " + s, "5", "kg", null, null, "31/02/2027", null, null, null, null, null, null),                                   // fila 8: fecha invalida
                ing("Sal imp " + s, "5", "kg", null, null, LocalDate.now().minusDays(1).format(DMY), null, null, null, null, null, null),       // fila 9: vencimiento pasado
                ing("Sal imp " + s, "5", "kg", "DUP-" + s, null, null, null, null, null, null, null, null),                                     // fila 10: lote repetido (la primera vale)
                ing("Sal imp " + s, "5", "kg", "DUP-" + s, null, null, null, null, null, null, null, null));                                    // fila 11: lote repetido

        VistaPreviaImportacionResponse v = previsualizarIngresos(filas);

        assertThat(v.exigeConfirmarParcial()).isTrue();
        assertThat(v.filas().get(2).estado()).isEqualTo("INVALIDA");                 // insumo inexistente
        assertThat(v.filas().get(2).mensajes()).anySatisfy(m -> assertThat(m).contains("no existe").contains("no crea insumos"));
        FilaImportacionResponse hermana = v.filas().get(1);                                   // la fila buena del documento malo
        assertThat(hermana.estado()).isEqualTo("INVALIDA");
        assertThat(hermana.mensajes()).anySatisfy(m -> assertThat(m).contains("Otra fila del mismo documento"));
        assertThat(v.filas().get(0).estado()).isEqualTo("VALIDA");                   // el otro documento esta bien
        assertThat(v.filas().subList(3, 8)).allSatisfy(f -> assertThat(f.estado()).isEqualTo("INVALIDA"));
        assertThat(contar(v, EstadoFila.DUPLICADA)).isEqualTo(1);                             // el lote repetido en el archivo

        // Sin confirmacion expresa no se importa nada, ni siquiera lo valido.
        assertThatThrownBy(() -> importacionService.confirmar(v.id())).isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("solo las filas válidas");
        assertThat(stock(azucar)).isEqualByComparingTo("112");

        // Con la confirmacion expresa se importa solo lo valido: el documento bueno y la primera fila del lote repetido.
        ResultadoImportacionResponse r = importacionService.confirmar(v.id(), true);
        assertThat(r.creadas()).isEqualTo(2);
        assertThat(stock(azucar)).isEqualByComparingTo("122");
        assertThat(stock(sal)).isEqualByComparingTo("15");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documento_ingreso WHERE numero = ?", Integer.class, "MALO-" + s)).isZero();
        invariantes(azucar);
        invariantes(sal);
    }

    @Test
    void repetirElArchivoNoDuplicaYUnDocumentoYaCargadoSeInforma() {
        String ruc = rucNuevo();
        List<List<Object>> filas = List.of(ing("Azúcar imp " + s, "10", "kg", null, null, null, "Prov " + s, ruc, "Boleta", "B-" + s, null, null));
        VistaPreviaImportacionResponse v = previsualizarIngresos(filas);
        importacionService.confirmar(v.id());
        assertThat(importacionService.confirmar(v.id()).yaConfirmada()).isTrue();      // repetir la confirmacion devuelve lo guardado, sin crear nada
        assertThat(stock(azucar)).isEqualByComparingTo("122");

        // El mismo archivo, subido otra vez: se rechaza.
        assertThatThrownBy(() -> previsualizarIngresos(filas)).isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("ya se importó");

        // Otro archivo con el mismo documento: el documento ya cargado se informa (duplicada), no se carga de nuevo.
        VistaPreviaImportacionResponse otra = previsualizarIngresos(List.of(
                ing("Azúcar imp " + s, "10", "kg", null, null, null, "Prov " + s, ruc, "Boleta", "B-" + s, null, "otra observación")));
        assertThat(otra.filas().get(0).estado()).isEqualTo("DUPLICADA");
        assertThat(otra.filas().get(0).mensajes()).anySatisfy(m -> assertThat(m).contains("ya fue cargado"));
        assertThat(stock(azucar)).isEqualByComparingTo("122");
        invariantes(azucar);
    }

    // ------------------------------------------------------------------------------------------------ saldos iniciales

    /** insumo, unidad, cantidad, lote, lote del proveedor, vencimiento, proveedor, RUC, observacion */
    private static List<Object> sal(Object insumo, Object unidad, Object cantidad, Object lote, Object loteProv, Object vence, Object prov, Object ruc, Object obs) {
        return java.util.Arrays.asList(insumo, unidad, cantidad, lote, loteProv, vence, prov, ruc, obs);
    }

    @Test
    void elSaldoInicialSeReemplazaPorLotesRealesSinCambiarElStockNiElKardex() {
        String ayer = LocalDate.now().minusDays(1).format(DMY);
        String lejos = LocalDate.now().plusDays(200).format(DMY);
        VistaPreviaImportacionResponse v = previsualizarSaldos(List.of(
                sal("Azúcar imp " + s, "kg", "70", "AZ-A-" + s, "PV-9", lejos, null, null, "Conteo de almacén"),
                sal("azucar imp " + s, "kg", "42", "AZ-B-" + s, null, ayer, null, null, null)));       // un lote con vencimiento YA pasado: se acepta

        assertThat(v.validas()).isEqualTo(2);
        assertThat(v.exigeConfirmarParcial()).isFalse();
        BigDecimal saldoAntes = jdbc.queryForObject("SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_insumo WHERE insumo_id = ?", BigDecimal.class, azucar);

        ResultadoImportacionResponse r = importacionService.confirmar(v.id());

        assertThat(r.creadas()).isEqualTo(2);
        assertThat(stock(azucar)).isEqualByComparingTo("112");                                         // el stock total NO cambia
        assertThat(jdbc.queryForObject("SELECT cantidad_disponible FROM lote_insumo WHERE insumo_id = ? AND codigo = 'INICIAL'", BigDecimal.class, azucar)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT cantidad_disponible FROM lote_insumo WHERE insumo_id = ? AND codigo = ?", BigDecimal.class, azucar, "AZ-A-" + s)).isEqualByComparingTo("70");
        assertThat(jdbc.queryForObject("SELECT fecha_vencimiento::text FROM lote_insumo WHERE insumo_id = ? AND codigo = ?", String.class, azucar, "AZ-B-" + s))
                .isEqualTo(LocalDate.now().minusDays(1).toString());
        // Cuatro movimientos de transferencia (dos por lote), de suma cero; el kardex total no cambia.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_insumo WHERE insumo_id = ? AND tipo_movimiento = 'TRANSFERENCIA_LOTE'", Integer.class, azucar)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_insumo WHERE insumo_id = ? AND tipo_movimiento = 'TRANSFERENCIA_LOTE'", BigDecimal.class, azucar)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_insumo WHERE insumo_id = ?", BigDecimal.class, azucar)).isEqualByComparingTo(saldoAntes);
        invariantes(azucar);
        // El lote vencido ya no cuenta como consumible, pero sigue en el stock hasta darlo de baja con una salida por vencimiento.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lote_insumo WHERE insumo_id = ? AND cantidad_disponible > 0 AND fecha_vencimiento < CURRENT_DATE", Integer.class, azucar)).isEqualTo(1);
    }

    @Test
    void laSumaDebeSerIgualAlStockYUnInsumoConLotesDeIngresoNoSeRegulariza() {
        // 100 no es 112.
        VistaPreviaImportacionResponse suma = previsualizarSaldos(List.of(sal("Azúcar imp " + s, "kg", "100", "X-" + s, null, null, null, null, null)));
        assertThat(suma.invalidas()).isEqualTo(1);
        assertThat(suma.filas().get(0).mensajes()).anySatisfy(m -> assertThat(m).contains("suman 100").contains("112").contains("conteo físico"));
        assertThatThrownBy(() -> importacionService.confirmar(suma.id())).isInstanceOf(ConflictoNegocioException.class);
        assertThat(stock(azucar)).isEqualByComparingTo("112");

        // Con un lote de ingreso con existencia, el saldo inicial ya no aplica.
        inventarioInsumoService.registrarIngreso(sal, new IngresoInsumoRequest(new BigDecimal("5"), null, null));
        VistaPreviaImportacionResponse conIngreso = previsualizarSaldos(List.of(sal("Sal imp " + s, "kg", "15", "S-" + s, null, null, null, null, null)));
        assertThat(conIngreso.invalidas()).isEqualTo(1);
        assertThat(conIngreso.filas().get(0).mensajes()).anySatisfy(m -> assertThat(m).contains("lotes de ingreso con existencia"));

        // Lote INICIAL no se puede usar como codigo y un insumo se importa completo o no se importa.
        VistaPreviaImportacionResponse inicial = previsualizarSaldos(List.of(
                sal("Azúcar imp " + s, "kg", "100", "INICIAL", null, null, null, null, null),
                sal("Azúcar imp " + s, "kg", "12", "OK-" + s, null, null, null, null, null)));
        assertThat(inicial.filas().get(0).estado()).isEqualTo("INVALIDA");
        assertThat(inicial.filas().get(1).estado()).isEqualTo("INVALIDA");       // la otra fila del mismo insumo cae con el grupo
        assertThat(inicial.filas().get(1).mensajes()).anySatisfy(m -> assertThat(m).contains("Otra fila"));
    }
}
