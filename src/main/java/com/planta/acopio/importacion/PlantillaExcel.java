package com.planta.acopio.importacion;

import com.planta.acopio.excel.ArchivoExcel;
import com.planta.acopio.excel.LimitesExcel;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Genera la plantilla oficial .xlsx de un tipo de importacion: instrucciones, encabezados, ejemplos y listas desplegables.
 *
 * La plantilla lleva su identidad en una hoja «_plantilla» MUY oculta (marca, tipo y version) que {@link LectorPlantilla}
 * comprueba al importar: asi no se puede cargar la plantilla de insumos en la pantalla de proveedores ni una plantilla
 * vieja cuando las columnas cambiaron.
 */
@Component
public class PlantillaExcel {

    public static final String HOJA_INSTRUCCIONES = "Instrucciones";
    public static final String HOJA_DATOS = "Datos";
    public static final String HOJA_LISTAS = "Listas";
    public static final String HOJA_META = "_plantilla";
    public static final String MARCA = "ECOLACTEOS-PLANTILLA";
    /** Filas de la hoja Datos que ya vienen con formato y listas desplegables. */
    private static final int FILAS_FORMATEADAS = 2000;

    private final LimitesExcel limites;

    public PlantillaExcel(LimitesExcel limites) {
        this.limites = limites;
    }

    public ArchivoExcel generar(TipoImportacion<?> tipo) {
        Path temporal = null;
        try (XSSFWorkbook libro = new XSSFWorkbook()) {
            Estilos estilos = new Estilos(libro);
            XSSFSheet instrucciones = libro.createSheet(HOJA_INSTRUCCIONES);
            XSSFSheet datos = libro.createSheet(HOJA_DATOS);
            XSSFSheet listas = libro.createSheet(HOJA_LISTAS);
            XSSFSheet meta = libro.createSheet(HOJA_META);

            escribirDatos(datos, tipo, estilos);
            escribirListas(datos, listas, tipo);
            escribirInstrucciones(instrucciones, tipo, estilos);
            escribirMeta(meta, tipo);

            libro.setSheetVisibility(libro.getSheetIndex(HOJA_LISTAS), SheetVisibility.HIDDEN);
            libro.setSheetVisibility(libro.getSheetIndex(HOJA_META), SheetVisibility.VERY_HIDDEN);
            libro.setActiveSheet(libro.getSheetIndex(HOJA_DATOS));

            temporal = Files.createTempFile("ecolacteos-xlsx-", ".xlsx");
            try (OutputStream salida = Files.newOutputStream(temporal)) {
                libro.write(salida);
            }
            return new ArchivoExcel(temporal, "plantilla-" + tipo.codigo() + "-v" + tipo.version() + ".xlsx", 0);
        } catch (IOException e) {
            if (temporal != null) {
                new ArchivoExcel(temporal, "", 0).descartar();
            }
            throw new UncheckedIOException("No se pudo generar la plantilla", e);
        }
    }

    private void escribirDatos(XSSFSheet hoja, TipoImportacion<?> tipo, Estilos estilos) {
        List<ColumnaImportacion> columnas = tipo.columnas();
        Row encabezado = hoja.createRow(0);
        encabezado.setHeightInPoints(30);
        for (int i = 0; i < columnas.size(); i++) {
            ColumnaImportacion c = columnas.get(i);
            Cell celda = encabezado.createCell(i);
            celda.setCellValue(c.encabezadoEnPlantilla());
            celda.setCellStyle(c.obligatoria() ? estilos.encabezadoObligatorio : estilos.encabezado);
            hoja.setColumnWidth(i, Math.min(255, Math.max(12, c.ancho())) * 256);
            // Las columnas de texto (DNI, telefono) se formatean como TEXTO: Excel no les quita los ceros iniciales.
            hoja.setDefaultColumnStyle(i, c.texto() ? estilos.texto : estilos.general);
        }
        hoja.createFreezePane(0, 1);
    }

    private void escribirListas(XSSFSheet datos, XSSFSheet listas, TipoImportacion<?> tipo) {
        Map<String, List<String>> desplegables = tipo.listasDesplegables();
        List<ColumnaImportacion> columnas = tipo.columnas();
        DataValidationHelper ayudante = datos.getDataValidationHelper();
        int columnaLista = 0;
        for (int i = 0; i < columnas.size(); i++) {
            List<String> valores = desplegables.get(columnas.get(i).clave());
            if (valores == null || valores.isEmpty()) {
                continue;
            }
            for (int f = 0; f < valores.size(); f++) {
                Row fila = listas.getRow(f) != null ? listas.getRow(f) : listas.createRow(f);
                fila.createCell(columnaLista).setCellValue(valores.get(f));
            }
            String letra = org.apache.poi.ss.util.CellReference.convertNumToColString(columnaLista);
            DataValidationConstraint restriccion = ayudante.createFormulaListConstraint(
                    HOJA_LISTAS + "!$" + letra + "$1:$" + letra + "$" + valores.size());
            CellRangeAddressList rango = new CellRangeAddressList(1, FILAS_FORMATEADAS, i, i);
            DataValidation validacion = ayudante.createValidation(restriccion, rango);
            validacion.setShowErrorBox(true);
            // En XSSF, setSuppressDropDownArrow(true) es lo que MUESTRA la flecha (la API esta al reves).
            validacion.setSuppressDropDownArrow(true);
            boolean abierta = tipo.listasAbiertas().contains(columnas.get(i).clave());
            validacion.setErrorStyle(abierta ? DataValidation.ErrorStyle.WARNING : DataValidation.ErrorStyle.STOP);
            validacion.createErrorBox("Valor no esperado", abierta
                    ? "Este valor no está en la lista sugerida. Se aceptará si es válido."
                    : "Elige un valor de la lista.");
            datos.addValidationData(validacion);
            columnaLista++;
        }
    }

    private void escribirInstrucciones(XSSFSheet hoja, TipoImportacion<?> tipo, Estilos estilos) {
        hoja.setColumnWidth(0, 28 * 256);
        hoja.setColumnWidth(1, 16 * 256);
        hoja.setColumnWidth(2, 60 * 256);
        hoja.setColumnWidth(3, 30 * 256);
        int fila = 0;
        fila = linea(hoja, fila, "Plantilla de importación: " + tipo.titulo() + " (versión " + tipo.version() + ")", estilos.titulo);
        fila++;
        List<String> generales = List.of(
                "1. Llena la hoja «Datos»: una fila por registro, empezando en la fila 2. No cambies los encabezados ni el nombre de las hojas.",
                "2. Las columnas con * son obligatorias. Escribe valores, nunca fórmulas.",
                "3. Guarda como «Libro de Excel (.xlsx)» (sin macros) y súbelo en el panel.",
                "4. El sistema te mostrará una vista previa con las filas válidas, inválidas y duplicadas. NO se guarda nada hasta que confirmes.",
                "5. Esta importación solo CREA registros nuevos. Si un registro ya existe, esa fila se omite y se informa (no se actualiza).",
                "6. Las filas con errores se omiten y puedes descargar un reporte con el número de fila y el motivo.",
                "7. Máximo " + limites.importacionMaxFilas() + " filas y " + (limites.importacionMaxBytes() / 1024 / 1024) + " MB por archivo."
        );
        for (String texto : generales) {
            fila = linea(hoja, fila, texto, estilos.normal);
        }
        fila++;
        for (String texto : tipo.instrucciones()) {
            fila = linea(hoja, fila, texto, estilos.normal);
        }
        fila++;
        Row cabecera = hoja.createRow(fila++);
        String[] titulos = {"Columna", "Obligatoria", "Formato", "Ejemplo"};
        for (int i = 0; i < titulos.length; i++) {
            Cell c = cabecera.createCell(i);
            c.setCellValue(titulos[i]);
            c.setCellStyle(estilos.encabezado);
        }
        for (ColumnaImportacion c : tipo.columnas()) {
            Row r = hoja.createRow(fila++);
            r.createCell(0).setCellValue(c.encabezado());
            r.createCell(1).setCellValue(c.obligatoria() ? "Sí" : "No");
            r.createCell(2).setCellValue(c.ayuda());
            r.createCell(3).setCellValue(c.ejemplo());
            for (int i = 0; i < 4; i++) {
                r.getCell(i).setCellStyle(estilos.celdaTabla);
            }
        }
    }

    private static int linea(XSSFSheet hoja, int fila, String texto, CellStyle estilo) {
        Row r = hoja.createRow(fila);
        Cell c = r.createCell(0);
        c.setCellValue(texto);
        c.setCellStyle(estilo);
        return fila + 1;
    }

    private static void escribirMeta(XSSFSheet meta, TipoImportacion<?> tipo) {
        meta.createRow(0).createCell(0).setCellValue(MARCA);
        meta.createRow(1).createCell(0).setCellValue(tipo.codigo());
        meta.createRow(2).createCell(0).setCellValue(tipo.version());
    }

    private static final class Estilos {
        final CellStyle titulo;
        final CellStyle normal;
        final CellStyle encabezado;
        final CellStyle encabezadoObligatorio;
        final CellStyle texto;
        final CellStyle general;
        final CellStyle celdaTabla;

        Estilos(XSSFWorkbook libro) {
            DataFormat formato = libro.createDataFormat();
            Font negrita = libro.createFont();
            negrita.setBold(true);
            Font grande = libro.createFont();
            grande.setBold(true);
            grande.setFontHeightInPoints((short) 14);

            titulo = libro.createCellStyle();
            titulo.setFont(grande);
            normal = libro.createCellStyle();
            normal.setWrapText(false);

            encabezado = libro.createCellStyle();
            encabezado.setFont(negrita);
            encabezado.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            encabezado.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            encabezado.setBorderBottom(BorderStyle.THIN);
            encabezado.setVerticalAlignment(VerticalAlignment.CENTER);
            encabezado.setWrapText(true);

            encabezadoObligatorio = libro.createCellStyle();
            encabezadoObligatorio.cloneStyleFrom(encabezado);
            encabezadoObligatorio.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());

            texto = libro.createCellStyle();
            texto.setDataFormat(formato.getFormat("@"));
            general = libro.createCellStyle();
            celdaTabla = libro.createCellStyle();
            celdaTabla.setWrapText(true);
            celdaTabla.setVerticalAlignment(VerticalAlignment.TOP);
        }
    }

    /** Para pruebas: encabezados tal como quedaron en la hoja Datos. */
    static List<String> encabezadosDe(TipoImportacion<?> tipo) {
        List<String> lista = new ArrayList<>();
        for (ColumnaImportacion c : tipo.columnas()) {
            lista.add(c.encabezadoEnPlantilla());
        }
        return lista;
    }
}
