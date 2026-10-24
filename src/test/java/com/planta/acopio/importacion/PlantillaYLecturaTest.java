package com.planta.acopio.importacion;

import com.planta.acopio.entity.Zona;
import com.planta.acopio.excel.ArchivoExcel;
import com.planta.acopio.excel.LectorExcelSeguro;
import com.planta.acopio.excel.LimitesExcel;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.ZonaRepository;
import com.planta.acopio.service.InsumoService;
import com.planta.acopio.service.ProveedorService;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// La plantilla oficial lleva su identidad (tipo y version) en una hoja muy oculta, y el lector solo acepta ESA plantilla,
// con sus encabezados, sin formulas y dentro de los limites de filas.
class PlantillaYLecturaTest {

    private final LimitesExcel limites = LimitesExcel.porDefecto();
    private PlantillaExcel plantilla;
    private LectorExcelSeguro seguro;
    private LectorPlantilla lector;
    private ImportacionProveedores proveedores;
    private ImportacionInsumos insumos;

    @BeforeEach
    void preparar() {
        plantilla = new PlantillaExcel(limites);
        seguro = new LectorExcelSeguro(limites);
        lector = new LectorPlantilla(limites);
        ZonaRepository zonas = mock(ZonaRepository.class);
        Zona norte = new Zona();
        norte.setId(UUID.randomUUID());
        norte.setNombre("Zona Norte");
        Zona sur = new Zona();
        sur.setId(UUID.randomUUID());
        sur.setNombre("Sur Ñuñoa");
        when(zonas.findByActivaTrue()).thenReturn(List.of(sur, norte));
        proveedores = new ImportacionProveedores(mock(ProveedorService.class), mock(ProveedorRepository.class), zonas);
        insumos = new ImportacionInsumos(mock(InsumoService.class), mock(InsumoRepository.class));
    }

    private LectorPlantilla.Lectura leer(TipoImportacion<?> tipo, byte[] archivo) {
        try (var libro = seguro.abrir(new MockMultipartFile("archivo", "datos.xlsx", "application/octet-stream", archivo))) {
            return lector.leer(libro.libro(), tipo);
        }
    }

    private byte[] plantillaProveedores() {
        return ExcelDePrueba.bytes(plantilla.generar(proveedores));
    }

    // Fase G (plantilla v2 de proveedores): las filas de estas pruebas se escriben como (nombre, dni, telefono, zona) y se ubican en
    // las columnas actuales (tipo, nombre, dni, ruc, razon social, telefono, zona) con tipo, RUC y razon social vacios.
    private byte[] conFilasV2(List<List<Object>> filas) {
        List<List<Object>> v2 = new java.util.ArrayList<>();
        for (List<Object> f : filas) {
            List<Object> fila = new java.util.ArrayList<>(java.util.Arrays.asList(null, null, null, null, null, null, null));
            int[] destino = {1, 2, 5, 6};
            for (int i = 0; i < f.size() && i < destino.length; i++) fila.set(destino[i], f.get(i));
            v2.add(fila);
        }
        return ExcelDePrueba.conFilas(plantillaProveedores(), v2);
    }

    // ----------------------------------------------------------------------------------------- la plantilla

    @Test
    void laPlantillaTraeInstruccionesDatosListasYLaIdentidadOculta() throws Exception {
        ArchivoExcel archivo = plantilla.generar(proveedores);
        assertThat(archivo.nombre()).isEqualTo("plantilla-proveedores-v2.xlsx");
        try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(ExcelDePrueba.bytes(archivo)))) {
            assertThat(libro.getSheetName(0)).isEqualTo("Instrucciones");
            assertThat(libro.getSheetName(1)).isEqualTo("Datos");
            assertThat(libro.getSheetVisibility(libro.getSheetIndex("Listas"))).isEqualTo(SheetVisibility.HIDDEN);
            assertThat(libro.getSheetVisibility(libro.getSheetIndex("_plantilla"))).isEqualTo(SheetVisibility.VERY_HIDDEN);

            Sheet meta = libro.getSheet("_plantilla");
            assertThat(meta.getRow(0).getCell(0).getStringCellValue()).isEqualTo("ECOLACTEOS-PLANTILLA");
            assertThat(meta.getRow(1).getCell(0).getStringCellValue()).isEqualTo("proveedores");
            assertThat((int) meta.getRow(2).getCell(0).getNumericCellValue()).isEqualTo(2);

            Row encabezado = libro.getSheet("Datos").getRow(0);
            assertThat(encabezado.getCell(0).getStringCellValue()).isEqualTo("Tipo de persona");
            assertThat(encabezado.getCell(1).getStringCellValue()).isEqualTo("Nombre completo");
            assertThat(encabezado.getCell(2).getStringCellValue()).isEqualTo("DNI");
            assertThat(encabezado.getCell(3).getStringCellValue()).isEqualTo("RUC");
            assertThat(encabezado.getCell(4).getStringCellValue()).isEqualTo("Razón social");
            assertThat(encabezado.getCell(5).getStringCellValue()).isEqualTo("Teléfono");
            assertThat(encabezado.getCell(6).getStringCellValue()).isEqualTo("Zona");
            assertThat(libro.getSheet("Datos").getLastRowNum()).isZero(); // sin filas de ejemplo que se puedan importar por descuido

            Sheet instrucciones = libro.getSheet("Instrucciones");
            StringBuilder todo = new StringBuilder();
            instrucciones.forEach(f -> f.forEach(c -> todo.append(c.toString()).append('\n')));
            assertThat(todo.toString()).contains("NO se guarda nada hasta que confirmes").contains("04567890").contains("Rosa Quispe Huamán");
        }
    }

    @Test
    void losDniYTelefonosVienenFormateadosComoTextoParaNoPerderCeros() throws Exception {
        try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(plantillaProveedores()))) {
            Sheet datos = libro.getSheet("Datos");
            assertThat(datos.getColumnStyle(2).getDataFormatString()).isEqualTo("@"); // DNI
            assertThat(datos.getColumnStyle(3).getDataFormatString()).isEqualTo("@"); // RUC
            assertThat(datos.getColumnStyle(5).getDataFormatString()).isEqualTo("@"); // telefono
            assertThat(datos.getColumnStyle(1).getDataFormatString()).isNotEqualTo("@");
        }
    }

    @Test
    void laZonaTieneListaDesplegableConLasZonasActivasYSoloAceptaEsos() throws Exception {
        try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(plantillaProveedores()))) {
            Sheet listas = libro.getSheet("Listas");
            // Orden alfabetico sin distinguir acentos: Sur Ñuñoa va despues de Zona Norte? "sur" < "zona".
            // Fase G: la primera lista es el tipo de persona; la segunda, las zonas.
            assertThat(listas.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Persona natural");
            assertThat(listas.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Empresa");
            assertThat(listas.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Sur Ñuñoa");
            assertThat(listas.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Zona Norte");

            List<? extends DataValidation> validaciones = libro.getSheet("Datos").getDataValidations();
            assertThat(validaciones).hasSize(2);
            DataValidation zona = validaciones.stream().filter(v -> v.getRegions().getCellRangeAddress(0).getFirstColumn() == 6).findFirst().orElseThrow();
            assertThat(zona.getValidationConstraint().getFormula1()).isEqualTo("Listas!$B$1:$B$2");
            assertThat(zona.getErrorStyle()).isEqualTo(DataValidation.ErrorStyle.STOP);
            assertThat(zona.getShowErrorBox()).isTrue();
        }
    }

    @Test
    void laListaDeUnidadesDeInsumosEsSoloUnaSugerencia() throws Exception {
        try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(ExcelDePrueba.bytes(plantilla.generar(insumos))))) {
            DataValidation unidad = libro.getSheet("Datos").getDataValidations().get(0);
            assertThat(unidad.getErrorStyle()).isEqualTo(DataValidation.ErrorStyle.WARNING);
            assertThat(libro.getSheet("_plantilla").getRow(1).getCell(0).getStringCellValue()).isEqualTo("insumos");
        }
    }

    // ----------------------------------------------------------------------------------------- la lectura

    @Test
    void leeLaPlantillaLlenadaYLasColumnasSeIdentificanPorEncabezadoNoPorPosicion() {
        byte[] llena = conFilasV2(List.of(
                List.of("Rosa Quispe", "04567890", "987654321", "Zona Norte"),
                List.of("Luis Mamani", "12345678")));

        var lectura = leer(proveedores, llena);

        assertThat(lectura.filas()).hasSize(2);
        assertThat(lectura.filas().get(0).numero()).isEqualTo(2); // la fila 1 es el encabezado
        assertThat(lectura.filas().get(0).celda("dni").texto()).isEqualTo("04567890");
        assertThat(lectura.filas().get(1).celda("zona").texto()).isEmpty();

        // Se reordenan las columnas: sigue leyendo cada dato en su columna.
        byte[] reordenada = ExcelDePrueba.modificar(llena, libro -> {
            Sheet datos = libro.getSheet("Datos");
            for (int r = 0; r <= 2; r++) {
                Row fila = datos.getRow(r);
                var a = fila.getCell(1);
                var b = fila.getCell(2);
                String textoA = a.getStringCellValue();
                String textoB = b.getStringCellValue();
                a.setCellValue(textoB);
                b.setCellValue(textoA);
            }
        });
        var otra = leer(proveedores, reordenada);
        assertThat(otra.filas().get(0).celda("nombre").texto()).isEqualTo("Rosa Quispe");
        assertThat(otra.filas().get(0).celda("dni").texto()).isEqualTo("04567890");
    }

    @Test
    void unNumeroSeLeeComoNumeroSinNotacionCientifica() {
        byte[] llena = conFilasV2(List.of(
                List.of("Ana", 4567890, 987654321, "Zona Norte")));
        var fila = leer(proveedores, llena).filas().get(0);

        assertThat(fila.celda("dni").numerica()).isTrue();
        assertThat(fila.celda("dni").texto()).isEqualTo("4567890"); // los ceros ya se perdieron en Excel: lo resuelve la validacion
        assertThat(fila.celda("telefono").texto()).isEqualTo("987654321");
    }

    @Test
    void unaFormulaSeMarcaNoSeEvaluaNiSeToma() {
        byte[] llena = conFilasV2(List.of(
                List.of(new ExcelDePrueba.Formula("\"Ro\"&\"sa\""), "12345678", "", "")));
        var celda = leer(proveedores, llena).filas().get(0).celda("nombre");

        assertThat(celda.formula()).isTrue();
        assertThat(celda.texto()).isEmpty();
        assertThat(celda.problematica()).isTrue();
    }

    @Test
    void cuentaLasFilasEnBlancoSinLlevarlasComoDatos() {
        byte[] llena = conFilasV2(List.of(
                List.of("Rosa", "11111111"),
                List.of("", "", "", ""),
                java.util.Arrays.asList(null, null),
                List.of("Luis", "22222222")));
        var lectura = leer(proveedores, llena);

        assertThat(lectura.filas()).extracting(FilaCruda::numero).containsExactly(2, 5);
        assertThat(lectura.filasEnBlanco()).isEqualTo(2);
    }

    @Test
    void rechazaUnArchivoQueNoEsLaPlantillaOficial() {
        byte[] sinMarca = ExcelDePrueba.modificar(plantillaProveedores(), l -> l.removeSheetAt(l.getSheetIndex("_plantilla")));
        assertThatThrownBy(() -> leer(proveedores, sinMarca))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("no es una plantilla oficial");

        byte[] marcaFalsa = ExcelDePrueba.modificar(plantillaProveedores(), l -> l.getSheet("_plantilla").getRow(0).getCell(0).setCellValue("OTRA-COSA"));
        assertThatThrownBy(() -> leer(proveedores, marcaFalsa))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("no es una plantilla oficial");
    }

    @Test
    void rechazaLaPlantillaDeOtroTipo() {
        byte[] deInsumos = ExcelDePrueba.bytes(plantilla.generar(insumos));
        assertThatThrownBy(() -> leer(proveedores, deInsumos))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("plantilla de «insumos»").hasMessageContaining("Proveedores");
    }

    @Test
    void rechazaLasVersionesViejaYNuevaConMensajesDistintos() {
        byte[] vieja = ExcelDePrueba.modificar(plantillaProveedores(), l -> l.getSheet("_plantilla").getRow(2).getCell(0).setCellValue(0));
        assertThatThrownBy(() -> leer(proveedores, vieja))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("anterior").hasMessageContaining("versión actual");

        byte[] nueva = ExcelDePrueba.modificar(plantillaProveedores(), l -> l.getSheet("_plantilla").getRow(2).getCell(0).setCellValue(3));
        assertThatThrownBy(() -> leer(proveedores, nueva))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("más nueva");

        byte[] ilegible = ExcelDePrueba.modificar(plantillaProveedores(), l -> l.getSheet("_plantilla").getRow(2).getCell(0).setCellValue("uno"));
        assertThatThrownBy(() -> leer(proveedores, ilegible))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("versión");
    }

    @Test
    void rechazaEncabezadosAlteradosFaltantesExtraOrepetidos() {
        byte[] renombrado = ExcelDePrueba.modificar(plantillaProveedores(), l -> l.getSheet("Datos").getRow(0).getCell(2).setCellValue("Documento"));
        assertThatThrownBy(() -> leer(proveedores, renombrado))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("encabezados").hasMessageContaining("DNI");

        byte[] columnaExtra = ExcelDePrueba.modificar(plantillaProveedores(), l -> l.getSheet("Datos").getRow(0).createCell(7).setCellValue("Observaciones"));
        assertThatThrownBy(() -> leer(proveedores, columnaExtra))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("no reconoce").hasMessageContaining("observaciones");

        byte[] repetida = ExcelDePrueba.modificar(plantillaProveedores(), l -> l.getSheet("Datos").getRow(0).createCell(7).setCellValue("DNI"));
        assertThatThrownBy(() -> leer(proveedores, repetida))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("más de una vez");
    }

    @Test
    void elEncabezadoToleraMayusculasAcentosYElAsterisco() {
        byte[] otroEstilo = ExcelDePrueba.modificar(plantillaProveedores(), l -> {
            Row cab = l.getSheet("Datos").getRow(0);
            cab.getCell(1).setCellValue("NOMBRE COMPLETO");
            cab.getCell(5).setCellValue("telefono");
        });
        assertThat(leer(proveedores, otroEstilo).filas()).isEmpty();
    }

    @Test
    void rechazaUnArchivoSinLaHojaDeDatos() {
        byte[] sinDatos = ExcelDePrueba.modificar(plantillaProveedores(), l -> l.removeSheetAt(l.getSheetIndex("Datos")));
        assertThatThrownBy(() -> leer(proveedores, sinDatos))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("hoja «Datos»");
    }

    @Test
    void respetaElMaximoDeFilasConDatos() {
        LimitesExcel justo = new LimitesExcel(50_000, 500, 2_097_152, 3, 10_000, 100, 26_214_400, 30);
        LectorPlantilla estricto = new LectorPlantilla(justo);
        byte[] cuatroFilas = conFilasV2(List.of(
                List.of("A", "11111111"), List.of("B", "22222222"), List.of("C", "33333333"), List.of("D", "44444444")));
        try (var libro = seguro.abrir(new MockMultipartFile("archivo", "d.xlsx", "x", cuatroFilas))) {
            assertThatThrownBy(() -> estricto.leer(libro.libro(), proveedores))
                    .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("más de 3 filas");
        }
        byte[] tresFilas = conFilasV2(List.of(
                List.of("A", "11111111"), List.of("B", "22222222"), List.of("C", "33333333")));
        try (var libro = seguro.abrir(new MockMultipartFile("archivo", "d.xlsx", "x", tresFilas))) {
            assertThat(estricto.leer(libro.libro(), proveedores).filas()).hasSize(3);
        }
    }

    @Test
    void rechazaUnaHojaConFormatoHastaFilasLejanas() {
        LimitesExcel limitado = new LimitesExcel(50_000, 500, 2_097_152, 2_000, 50, 100, 26_214_400, 30);
        byte[] lejana = ExcelDePrueba.modificar(plantillaProveedores(), l -> l.getSheet("Datos").createRow(5_000).createCell(0).setCellValue(""));
        try (var libro = seguro.abrir(new MockMultipartFile("archivo", "d.xlsx", "x", lejana))) {
            assertThatThrownBy(() -> new LectorPlantilla(limitado).leer(libro.libro(), proveedores))
                    .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("demasiadas filas");
        }
    }

    @Test
    void unaCeldaConErrorDeExcelSeMarca() {
        byte[] conError = ExcelDePrueba.modificar(plantillaProveedores(), l -> {
            Row fila = l.getSheet("Datos").createRow(1);
            fila.createCell(1).setCellValue("Rosa");
            fila.createCell(2).setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.REF.getCode());
        });
        var celda = leer(proveedores, conError).filas().get(0).celda("dni");
        assertThat(celda.error()).isTrue();
        assertThat(celda.problematica()).isTrue();
    }
}
