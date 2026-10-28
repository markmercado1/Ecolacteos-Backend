package com.planta.acopio.integration;

import com.planta.acopio.dto.ProveedorRequest;
import com.planta.acopio.importacion.ExcelDePrueba;
import com.planta.acopio.importacion.FilaImportacionResponse;
import com.planta.acopio.importacion.ImportacionService;
import com.planta.acopio.importacion.ResultadoImportacionResponse;
import com.planta.acopio.importacion.VistaPreviaImportacionResponse;
import com.planta.acopio.service.ProveedorService;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refactor clientes: importacion del padron de clientes contra PostgreSQL real con la infraestructura comun (plantilla, vista previa,
 * confirmacion transaccional). Duplicados por las reglas de identidad (DNI/RUC de otra ficha o de un proveedor, telefono ya usado,
 * repetidos en el archivo), sin fusionar nada; filas invalidas; la constancia de autorizaciones queda igual que en el registro.
 */
@SpringBootTest
class ImportacionClientesIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    private static final AtomicInteger PRUEBAS = new AtomicInteger(500);

    @Autowired private ImportacionService importacionService;
    @Autowired private ProveedorService proveedorService;
    @Autowired private JdbcTemplate jdbc;

    private int prueba;
    private String sufijo;
    private byte[] plantilla;

    @BeforeEach
    void preparar() {
        prueba = PRUEBAS.incrementAndGet();
        sufijo = String.valueOf(prueba);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null, List.of()));
        plantilla = ExcelDePrueba.bytes(importacionService.plantilla("clientes"));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    // columnas: tipo, nombre, telefono, correo, dni, ruc, razon social, tipo de cliente, motivo, canal, forma, promociones, otra persona
    private List<Object> fila(String nombre, String telefono, String dni, String tipoCliente, String motivo, String canal, String forma, String promos,
                              String otraPersona) {
        return new ArrayList<>(Arrays.asList("", nombre, telefono, "", dni, "", "", tipoCliente, motivo, canal, forma, promos, otraPersona));
    }

    private String telefono(int i) {
        return String.format("9%04d%04d", prueba % 10000, i);
    }

    private String dni(int i) {
        return String.format("7%05d%02d", prueba, i);
    }

    private VistaPreviaImportacionResponse previsualizar(List<List<Object>> filas) {
        return importacionService.previsualizar("clientes", new MockMultipartFile("archivo", "clientes.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ExcelDePrueba.conFilas(plantilla, filas)));
    }

    private static FilaImportacionResponse filaN(VistaPreviaImportacionResponse vista, int indice) {
        return vista.filas().get(indice);
    }

    private long contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void importaFichasValidasConSuConstanciaTipoYCanalSinTocarNadaMas() {
        String de = "De palabra, en persona";
        VistaPreviaImportacionResponse vista = previsualizar(List.of(
                fila("Importada Uno " + sufijo, telefono(1), dni(1), "", "", "", de, "", ""),
                fila("Importada Dos " + sufijo, "+51 " + telefono(2), "", "Mayorista", "Compra por mayor", "Mayorista", "Por escrito", "WhatsApp, correo", "")));
        assertThat(vista.validas()).isEqualTo(2);
        assertThat(vista.puedeConfirmar()).isTrue();
        // la vista previa no escribe
        assertThat(contar("SELECT count(*) FROM cliente WHERE nombre LIKE ?", "Importada % " + sufijo)).isZero();

        ResultadoImportacionResponse r = importacionService.confirmar(vista.id());
        assertThat(r.creadas()).isEqualTo(2);

        UUID uno = jdbc.queryForObject("SELECT id FROM cliente WHERE nombre = ?", UUID.class, "Importada Uno " + sufijo);
        UUID dos = jdbc.queryForObject("SELECT id FROM cliente WHERE nombre = ?", UUID.class, "Importada Dos " + sufijo);
        assertThat(jdbc.queryForObject("SELECT dni FROM cliente WHERE id = ?", String.class, uno)).isEqualTo(dni(1));
        assertThat(jdbc.queryForObject("SELECT canal_habitual FROM cliente WHERE id = ?", String.class, uno)).isEqualTo("PUBLICO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cliente_tipo_precio WHERE cliente_id = ? AND tipo_codigo = 'MAYORISTA'", Integer.class, dos)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT canal_habitual FROM cliente WHERE id = ?", String.class, dos)).isEqualTo("MAYORISTA");
        assertThat(jdbc.queryForObject("SELECT telefono FROM cliente_telefono WHERE cliente_id = ?", String.class, dos)).isEqualTo("+51" + telefono(2));
        // constancia: registro para ambas; promociones SOLO para quien las autorizo, con sus medios y la forma declarada
        assertThat(contar("SELECT count(*) FROM cliente_consentimiento WHERE cliente_id = ? AND finalidad = 'REGISTRO_CLIENTE' AND forma = 'VERBAL_PRESENCIAL'", uno)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM cliente_consentimiento WHERE cliente_id = ? AND finalidad = 'COMUNICACIONES_COMERCIALES'", uno)).isZero();
        assertThat(jdbc.queryForObject("SELECT medios FROM cliente_consentimiento WHERE cliente_id = ? AND finalidad = 'COMUNICACIONES_COMERCIALES'",
                String.class, dos)).isEqualTo("CORREO,WHATSAPP");
        assertThat(contar("SELECT count(*) FROM cliente_consentimiento WHERE cliente_id = ? AND forma = 'ESCRITO' AND registrado_por IS NOT NULL", dos)).isEqualTo(2);
    }

    @Test
    void losDuplicadosSeOmitenSinFusionarYLasFilasInvalidasSeInforman() {
        String de = "De palabra, en persona";
        // ya existentes: una ficha (por la importacion anterior) y un proveedor con DNI
        importacionService.confirmar(previsualizar(List.<List<Object>>of(
                fila("Existente " + sufijo, telefono(10), dni(10), "", "", "", de, "", ""))).id());
        proveedorService.crear(new ProveedorRequest("Proveedor Cliente " + sufijo, dni(11), null, null, "NATURAL", null, null));
        long antes = contar("SELECT count(*) FROM cliente");

        VistaPreviaImportacionResponse vista = previsualizar(List.of(
                fila("Mismo DNI " + sufijo, telefono(12), dni(10), "", "", "", de, "", ""),           // 0 DNI de otra ficha
                fila("DNI de proveedor " + sufijo, telefono(13), dni(11), "", "", "", de, "", ""),    // 1 DNI de un proveedor
                fila("Mismo tel " + sufijo, telefono(10), "", "", "", "", de, "", ""),                // 2 telefono ya usado
                fila("Otra persona " + sufijo, telefono(10), "", "", "", "", de, "", "Sí"),           // 3 confirmada: se importa
                fila("Repetida " + sufijo, telefono(14), dni(14), "", "", "", de, "", ""),            // 4
                fila("Repetida bis " + sufijo, telefono(15), dni(14), "", "", "", de, "", ""),        // 5 repetida en el archivo
                fila("Sin autorizacion " + sufijo, telefono(16), "", "", "", "", "", "", ""),         // 6 falta como autorizo
                fila("Mayorista sin motivo " + sufijo, telefono(17), "", "Mayorista", "", "", de, "", ""), // 7
                fila("Canal invalido " + sufijo, telefono(18), "", "", "", "Mayorista", de, "", ""),  // 8 canal mayorista siendo regular
                fila("Medio invalido " + sufijo, telefono(19), "", "", "", "", de, "Paloma", ""),     // 9
                fila("Telefono malo " + sufijo, "12345", "", "", "", "", de, "", "")));               // 10
        assertThat(filaN(vista, 0).estado()).isEqualTo("DUPLICADA");
        assertThat(filaN(vista, 0).mensajes().get(0)).contains("DNI");
        assertThat(filaN(vista, 1).estado()).isEqualTo("DUPLICADA");
        assertThat(filaN(vista, 1).mensajes().get(0)).contains("proveedor");
        assertThat(filaN(vista, 2).estado()).isEqualTo("DUPLICADA");
        assertThat(filaN(vista, 2).mensajes().get(0)).contains("posible duplicado");
        assertThat(filaN(vista, 3).estado()).isEqualTo("VALIDA");
        assertThat(filaN(vista, 4).estado()).isEqualTo("VALIDA");
        assertThat(filaN(vista, 5).estado()).isEqualTo("DUPLICADA");
        for (int i = 6; i <= 10; i++) assertThat(filaN(vista, i).estado()).as("fila %d", i).isEqualTo("INVALIDA");
        assertThat(filaN(vista, 6).mensajes().toString()).contains("Cómo autorizó el registro");
        assertThat(filaN(vista, 7).mensajes().toString()).contains("Motivo mayorista");
        assertThat(filaN(vista, 8).mensajes().toString()).contains("MAYORISTA");
        assertThat(filaN(vista, 9).mensajes().toString()).contains("Paloma");

        ResultadoImportacionResponse r = importacionService.confirmar(vista.id());
        assertThat(r.creadas()).isEqualTo(2);
        assertThat(r.invalidas()).isEqualTo(5);
        assertThat(r.filasNoCreadas()).hasSize(9);
        assertThat(contar("SELECT count(*) FROM cliente")).isEqualTo(antes + 2);
        // nada se fusiono ni se modifico: la ficha existente sigue con un solo telefono y sin cambios
        assertThat(contar("SELECT count(*) FROM cliente WHERE nombre = ? AND fusionado_en_id IS NULL", "Existente " + sufijo)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM cliente_fusion f JOIN cliente c ON c.id = f.origen_id WHERE c.nombre LIKE ?", "% " + sufijo)).isZero();
    }

    @Test
    void unaFichaRegistradaDespuesDeLaVistaPreviaSeOmiteAlConfirmar() {
        String de = "De palabra, en persona";
        VistaPreviaImportacionResponse vista = previsualizar(List.<List<Object>>of(fila("Carrera " + sufijo, telefono(30), dni(30), "", "", "", de, "", "")));
        importacionService.confirmar(previsualizar(List.<List<Object>>of(fila("Carrera antes " + sufijo, telefono(31), dni(30), "", "", "", de, "", ""))).id());
        ResultadoImportacionResponse r = importacionService.confirmar(vista.id());
        assertThat(r.creadas()).isZero();
        assertThat(r.omitidas()).isEqualTo(1);
        assertThat(r.filasNoCreadas().get(0).mensajes().get(0)).contains("después de la vista previa");
    }
}
