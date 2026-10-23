package com.planta.acopio.importacion;

import com.planta.acopio.excel.LimitesExcel;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.service.BusquedaTexto;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lee la hoja «Datos» de una plantilla oficial ya abierta con {@link com.planta.acopio.excel.LectorExcelSeguro}.
 *
 * Comprueba, antes de devolver una sola fila: que sea la plantilla de ESTE tipo, que su version sea la vigente y que los
 * encabezados coincidan con los esperados (en cualquier orden, sin distinguir mayusculas ni acentos). Las columnas se
 * identifican por encabezado, no por posicion. Nunca evalua formulas.
 */
@Component
public class LectorPlantilla {

    /** Resultado de leer la hoja: las filas con contenido y cuantas filas existian pero estaban en blanco. */
    public record Lectura(List<FilaCruda> filas, int filasEnBlanco) {
    }

    private static final int MAX_COLUMNAS = 60;
    private static final int MAX_TEXTO_CELDA = 2_000;

    private final LimitesExcel limites;

    public LectorPlantilla(LimitesExcel limites) {
        this.limites = limites;
    }

    public Lectura leer(XSSFWorkbook libro, TipoImportacion<?> tipo) {
        verificarIdentidad(libro, tipo);
        Sheet hoja = libro.getSheet(PlantillaExcel.HOJA_DATOS);
        if (hoja == null) {
            throw new ValidacionNegocioException("Al archivo le falta la hoja «" + PlantillaExcel.HOJA_DATOS
                    + "». Descarga la plantilla oficial y llénala sin cambiar sus hojas.");
        }
        Map<String, Integer> posiciones = leerEncabezados(hoja, tipo);

        if (hoja.getLastRowNum() > limites.importacionMaxFilasFisicas()) {
            throw new ValidacionNegocioException("La hoja «Datos» tiene demasiadas filas (más de " + limites.importacionMaxFilasFisicas()
                    + "). Borra las filas sobrantes o divide la carga en varios archivos.");
        }
        List<FilaCruda> filas = new ArrayList<>();
        int enBlanco = 0;
        for (int r = 1; r <= hoja.getLastRowNum(); r++) {
            Row fila = hoja.getRow(r);
            if (fila == null) {
                continue;
            }
            Map<String, CeldaLeida> celdas = new LinkedHashMap<>();
            for (ColumnaImportacion c : tipo.columnas()) {
                celdas.put(c.clave(), leerCelda(fila.getCell(posiciones.get(c.clave()))));
            }
            FilaCruda cruda = new FilaCruda(r + 1, celdas);
            if (cruda.vacia()) {
                enBlanco++;
                continue;
            }
            if (filas.size() >= limites.importacionMaxFilas()) {
                throw new ValidacionNegocioException("El archivo tiene más de " + limites.importacionMaxFilas()
                        + " filas con datos, que es el máximo por importación. Divide la carga en varios archivos.");
            }
            filas.add(cruda);
        }
        return new Lectura(filas, enBlanco);
    }

    private void verificarIdentidad(XSSFWorkbook libro, TipoImportacion<?> tipo) {
        Sheet meta = libro.getSheet(PlantillaExcel.HOJA_META);
        String marca = textoDe(meta, 0);
        if (meta == null || !PlantillaExcel.MARCA.equals(marca)) {
            throw new ValidacionNegocioException("Este archivo no es una plantilla oficial de EcoLácteos. Descarga la plantilla de «"
                    + tipo.titulo() + "» desde el panel y llénala sin crear un archivo nuevo.");
        }
        String codigo = textoDe(meta, 1);
        if (!tipo.codigo().equals(codigo)) {
            throw new ValidacionNegocioException("Esta es la plantilla de «" + (codigo == null ? "otro tipo" : codigo)
                    + "», no la de «" + tipo.titulo() + "». Descarga la plantilla correcta para esta pantalla.");
        }
        int version;
        try {
            version = new BigDecimal(textoDe(meta, 2) == null ? "x" : textoDe(meta, 2)).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new ValidacionNegocioException("No se pudo identificar la versión de la plantilla. Descarga la plantilla actual.");
        }
        if (version < tipo.version()) {
            throw new ValidacionNegocioException("La plantilla es de una versión anterior (" + version + ") y ya no se acepta. "
                    + "Descarga la versión actual (" + tipo.version() + ") y pasa tus datos a ella.");
        }
        if (version > tipo.version()) {
            throw new ValidacionNegocioException("La plantilla es de una versión más nueva (" + version + ") que la que reconoce este sistema ("
                    + tipo.version() + "). Descarga la plantilla desde este mismo panel.");
        }
    }

    private Map<String, Integer> leerEncabezados(Sheet hoja, TipoImportacion<?> tipo) {
        Row cabecera = hoja.getRow(0);
        if (cabecera == null) {
            throw encabezadosIncorrectos(tipo);
        }
        Map<String, Integer> porTexto = new HashMap<>();
        int ultima = Math.min(cabecera.getLastCellNum(), MAX_COLUMNAS);
        for (int i = 0; i < ultima; i++) {
            String texto = normalizarEncabezado(textoDe(cabecera.getCell(i)));
            if (texto.isEmpty()) {
                continue;
            }
            if (porTexto.putIfAbsent(texto, i) != null) {
                throw new ValidacionNegocioException("La columna «" + texto + "» aparece más de una vez en los encabezados. Usa la plantilla oficial sin duplicar columnas.");
            }
        }
        Map<String, Integer> posiciones = new HashMap<>();
        for (ColumnaImportacion c : tipo.columnas()) {
            Integer posicion = porTexto.remove(normalizarEncabezado(c.encabezado()));
            if (posicion == null) {
                throw encabezadosIncorrectos(tipo);
            }
            posiciones.put(c.clave(), posicion);
        }
        if (!porTexto.isEmpty()) {
            throw new ValidacionNegocioException("El archivo tiene columnas que la plantilla no reconoce: " + String.join(", ", porTexto.keySet())
                    + ". Usa la plantilla oficial sin agregar ni renombrar columnas.");
        }
        return posiciones;
    }

    private static ValidacionNegocioException encabezadosIncorrectos(TipoImportacion<?> tipo) {
        List<String> esperados = tipo.columnas().stream().map(ColumnaImportacion::encabezadoEnPlantilla).toList();
        return new ValidacionNegocioException("Los encabezados de la hoja «Datos» no coinciden con la plantilla oficial. Se esperaban: "
                + String.join(", ", esperados) + ".");
    }

    /** Sin asterisco de obligatoria, sin espacios de mas, minusculas y sin acentos. */
    static String normalizarEncabezado(String texto) {
        if (texto == null) {
            return "";
        }
        String sinAsterisco = texto.trim().replaceAll("\\s*\\*+\\s*$", "").trim();
        return BusquedaTexto.plegar(sinAsterisco).replaceAll("\\s+", " ");
    }

    private static String textoDe(Sheet hoja, int fila) {
        if (hoja == null || hoja.getRow(fila) == null) {
            return null;
        }
        return textoDe(hoja.getRow(fila).getCell(0));
    }

    private static String textoDe(Cell celda) {
        if (celda == null) {
            return null;
        }
        return switch (celda.getCellType()) {
            case STRING -> celda.getStringCellValue().trim();
            case NUMERIC -> BigDecimal.valueOf(celda.getNumericCellValue()).stripTrailingZeros().toPlainString();
            default -> null;
        };
    }

    static CeldaLeida leerCelda(Cell celda) {
        if (celda == null) {
            return CeldaLeida.VACIA;
        }
        CellType tipo = celda.getCellType();
        return switch (tipo) {
            case BLANK, _NONE -> CeldaLeida.VACIA;
            case FORMULA -> new CeldaLeida("", false, true, false);
            case ERROR -> new CeldaLeida("", false, false, true);
            case BOOLEAN -> new CeldaLeida(celda.getBooleanCellValue() ? "VERDADERO" : "FALSO", false, false, false);
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(celda)) {
                    yield new CeldaLeida(celda.getLocalDateTimeCellValue().toLocalDate().toString(), true, false, false);
                }
                double valor = celda.getNumericCellValue();
                if (Double.isNaN(valor) || Double.isInfinite(valor)) {
                    yield new CeldaLeida("", false, false, true);
                }
                yield new CeldaLeida(BigDecimal.valueOf(valor).stripTrailingZeros().toPlainString(), true, false, false);
            }
            case STRING -> {
                String texto = celda.getStringCellValue().trim();
                yield new CeldaLeida(texto.length() > MAX_TEXTO_CELDA ? texto.substring(0, MAX_TEXTO_CELDA) : texto, false, false, false);
            }
        };
    }
}
