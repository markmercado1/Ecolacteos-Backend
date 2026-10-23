package com.planta.acopio.excel;

import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.importacion.ExcelDePrueba;
import com.planta.acopio.importacion.ImportacionInsumos;
import com.planta.acopio.importacion.PlantillaExcel;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.service.InsumoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

// Un archivo subido lo controla un tercero: se rechaza por nombre, tamano, firma, macros, componentes peligrosos,
// descompresion anomala y contenido que no sea un .xlsx real, ANTES de que POI lo interprete.
class LectorExcelSeguroTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private LectorExcelSeguro lector;
    private byte[] valido;

    @BeforeEach
    void preparar() {
        LimitesExcel limites = LimitesExcel.porDefecto();
        lector = new LectorExcelSeguro(limites);
        var tipo = new ImportacionInsumos(mock(InsumoService.class), mock(InsumoRepository.class));
        valido = ExcelDePrueba.conFilas(new PlantillaExcel(limites).generar(tipo), List.of(List.of("Cuajo", "ml", "10")));
    }

    private MockMultipartFile archivo(String nombre, byte[] bytes) {
        return new MockMultipartFile("archivo", nombre, XLSX, bytes);
    }

    @Test
    void aceptaUnXlsxValidoYCalculaSuHuella() {
        try (var libro = lector.abrir(archivo("insumos.xlsx", valido))) {
            assertThat(libro.libro().getSheet(PlantillaExcel.HOJA_DATOS)).isNotNull();
            assertThat(libro.sha256()).hasSize(64).matches("[0-9a-f]+");
            assertThat(libro.nombre()).isEqualTo("insumos.xlsx");
        }
    }

    @Test
    void laHuellaCambiaSiCambiaElContenido() {
        byte[] otro = ExcelDePrueba.modificar(valido, l -> l.getSheet(PlantillaExcel.HOJA_DATOS).getRow(1).getCell(0).setCellValue("Sal"));
        try (var a = lector.abrir(archivo("a.xlsx", valido)); var b = lector.abrir(archivo("b.xlsx", otro))) {
            assertThat(a.sha256()).isNotEqualTo(b.sha256());
        }
    }

    @Test
    void rechazaLosFormatosQueNoSonXlsxPorElNombre() {
        for (String nombre : List.of("datos.xls", "datos.xlsm", "datos.csv", "datos.xlsx.exe", "datos", "datos.xlsb", "datos.ods")) {
            assertThatThrownBy(() -> lector.abrir(archivo(nombre, valido)))
                    .as(nombre).isInstanceOf(ValidacionNegocioException.class).hasMessageContaining(".xlsx");
        }
    }

    @Test
    void laExtensionSeComparaSinDistinguirMayusculas() {
        try (var libro = lector.abrir(archivo("DATOS.XLSX", valido))) {
            assertThat(libro.nombre()).isEqualTo("DATOS.XLSX");
        }
    }

    @Test
    void rechazaUnArchivoVacioOAusente() {
        assertThatThrownBy(() -> lector.abrir(archivo("a.xlsx", new byte[0]))).isInstanceOf(ValidacionNegocioException.class);
        assertThatThrownBy(() -> lector.abrir((org.springframework.web.multipart.MultipartFile) null)).isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void rechazaUnArchivoMasGrandeQueElMaximo() {
        LectorExcelSeguro pequeno = new LectorExcelSeguro(new LimitesExcel(50_000, 500, 1_000, 2_000, 10_000, 100, 26_214_400, 30));
        assertThatThrownBy(() -> pequeno.abrir(archivo("a.xlsx", valido)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("máximo");
    }

    @Test
    void rechazaTextoDisfrazadoDeXlsxPorSuFirma() {
        byte[] texto = "Nombre;DNI\nRosa;12345678".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> lector.abrir(archivo("disfrazado.xlsx", texto)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("no es un .xlsx válido");
    }

    @Test
    void rechazaUnZipQueNoEsUnLibroDeExcel() {
        byte[] zipSimple = ExcelDePrueba.zipBomba(10); // zip valido, sin la estructura OOXML
        assertThatThrownBy(() -> lector.abrir(archivo("a.xlsx", zipSimple)))
                .isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void rechazaMacrosVba() {
        byte[] conMacro = ExcelDePrueba.conEntradaExtra(valido, "xl/vbaProject.bin", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> lector.abrir(archivo("a.xlsx", conMacro)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("macros");
    }

    @Test
    void rechazaUnLibroMarcadoComoConMacrosAunqueSeLlameXlsx() {
        byte[] xlsm = ExcelDePrueba.reemplazandoEntrada(valido, "[Content_Types].xml",
                xml -> xml.replace("spreadsheetml.sheet.main+xml", "ms-excel.sheet.macroEnabled.main+xml"));
        assertThatThrownBy(() -> lector.abrir(archivo("a.xlsx", xlsm)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("macros");
    }

    @Test
    void rechazaComponentesPeligrososControlesObjetosYVinculosExternos() {
        for (String entrada : List.of("xl/activeX/activeX1.xml", "xl/embeddings/oleObject1.bin", "xl/externalLinks/externalLink1.xml",
                "customUI/customUI.xml", "xl/macrosheets/sheet1.xml", "xl/ctrlProps/ctrlProp1.xml")) {
            byte[] peligroso = ExcelDePrueba.conEntradaExtra(valido, entrada, new byte[]{1});
            assertThatThrownBy(() -> lector.abrir(archivo("a.xlsx", peligroso)))
                    .as(entrada).isInstanceOf(ValidacionNegocioException.class);
        }
    }

    @Test
    void rechazaRutasInternasConSalidaDelDirectorio() {
        byte[] traversal = ExcelDePrueba.conEntradaExtra(valido, "../../evil.txt", new byte[]{1});
        assertThatThrownBy(() -> lector.abrir(archivo("a.xlsx", traversal))).isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void rechazaUnZipBombaPorLosBytesQueOcuparaDescomprimido() {
        // ~30 MB de ceros comprimen a unos KB; el limite descomprimido es 25 MB.
        byte[] bomba = ExcelDePrueba.zipBomba(30L * 1024 * 1024);
        assertThat(bomba.length).isLessThan(200_000);
        assertThatThrownBy(() -> lector.abrir(archivo("a.xlsx", bomba)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("tamaño anormal");
    }

    @Test
    void rechazaUnZipConDemasiadosComponentes() {
        byte[] conMuchos = valido;
        for (int i = 0; i < 4; i++) {
            conMuchos = ExcelDePrueba.conEntradaExtra(conMuchos, "xl/extra" + i + ".xml", new byte[]{1});
        }
        LectorExcelSeguro estricto = new LectorExcelSeguro(new LimitesExcel(50_000, 500, 2_097_152, 2_000, 10_000, 10, 26_214_400, 30));
        byte[] archivoAnomalo = conMuchos;
        assertThatThrownBy(() -> estricto.abrir(archivo("a.xlsx", archivoAnomalo)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("demasiados componentes");
    }

    @Test
    void unXlsxDanadoSeInformaSinDetallesInternos() {
        byte[] danado = ExcelDePrueba.reemplazandoEntrada(valido, "xl/workbook.xml", xml -> "<<<esto no es xml");
        assertThatThrownBy(() -> lector.abrir(archivo("a.xlsx", danado)))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("no es un .xlsx válido")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("org.apache").doesNotContain("Exception"));
    }

    @Test
    void elNombreDelArchivoSeLimpiaDeRutas() {
        assertThat(LectorExcelSeguro.limpiarNombre("C:\\Users\\yo\\Desktop\\prov.xlsx")).isEqualTo("prov.xlsx");
        assertThat(LectorExcelSeguro.limpiarNombre("../../etc/passwd.xlsx")).isEqualTo("passwd.xlsx");
        assertThat(LectorExcelSeguro.limpiarNombre(null)).isEqualTo("archivo.xlsx");
        assertThat(LectorExcelSeguro.limpiarNombre("x".repeat(400) + ".xlsx")).hasSizeLessThanOrEqualTo(150);
    }
}
