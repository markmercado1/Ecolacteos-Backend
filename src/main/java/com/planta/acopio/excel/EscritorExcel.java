package com.planta.acopio.excel;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.WorkbookUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Escritura de celdas comun a exportaciones y plantillas: estilos, tipos y, sobre todo, la neutralizacion de
 * inyeccion de formulas.
 *
 * Inyeccion de formulas: un texto que empieza con =, +, - o @ (o con tabulador / retorno de carro) puede ser
 * interpretado como formula al abrir el archivo o al pasarlo a CSV (=HYPERLINK(...), =cmd|...). Defensa en dos capas:
 * (1) todo texto se escribe con tipo STRING -- nunca se llama a setCellFormula --, y (2) el texto riesgoso se
 * antepone con una comilla simple, que Excel muestra como texto literal. Solo se deja pasar tal cual un numero o un
 * telefono con signo (+51 987 654 321, -12.5), que no contiene letras ni operadores.
 */
public final class EscritorExcel {

    /** Primer caracter que Excel/CSV interpretaria como inicio de formula. */
    private static final String DISPARADORES = "=+-@\t\r";
    /** Un numero o telefono con signo inicial: solo digitos y separadores usuales, nada ejecutable. */
    private static final Pattern NUMERO_O_TELEFONO = Pattern.compile("^[+-]?\\d[\\d\\s().,-]*$");
    /** Limite de caracteres de una celda de Excel. */
    private static final int MAX_CELDA = 32_767;

    private final Workbook libro;
    private final Map<String, CellStyle> estilos = new HashMap<>();

    public EscritorExcel(Workbook libro) {
        this.libro = libro;
    }

    /** Antepone una comilla simple a un texto que Excel podria leer como formula. Devuelve el mismo texto si es seguro. */
    public static String neutralizarFormula(String texto) {
        if (texto == null || texto.isEmpty()) {
            return texto;
        }
        String recortado = texto.stripLeading();
        if (recortado.isEmpty()) {
            return texto;
        }
        char primero = recortado.charAt(0);
        boolean disparador = DISPARADORES.indexOf(primero) >= 0 || DISPARADORES.indexOf(texto.charAt(0)) >= 0;
        if (!disparador) {
            return texto;
        }
        if ((primero == '+' || primero == '-') && NUMERO_O_TELEFONO.matcher(recortado).matches()) {
            return texto;
        }
        return "'" + texto;
    }

    /** Nombre de hoja valido para Excel: sin [ ] : * ? / \ y de hasta 31 caracteres. */
    public static String nombreHoja(String solicitado) {
        String base = solicitado == null || solicitado.isBlank() ? "Datos" : solicitado;
        String limpio = WorkbookUtil.createSafeSheetName(base);
        return limpio.isBlank() ? "Datos" : limpio;
    }

    public CellStyle estiloEncabezado() {
        return estilos.computeIfAbsent("encabezado", k -> {
            CellStyle estilo = libro.createCellStyle();
            Font fuente = libro.createFont();
            fuente.setBold(true);
            estilo.setFont(fuente);
            estilo.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            estilo.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            estilo.setBorderBottom(BorderStyle.THIN);
            estilo.setVerticalAlignment(VerticalAlignment.CENTER);
            estilo.setWrapText(true);
            return estilo;
        });
    }

    /** Estilo de datos: formato numerico/fecha segun la columna. */
    public CellStyle estiloDatos(ColumnaExcel.Tipo tipo, int decimales) {
        String clave = "datos:" + tipo + ":" + decimales;
        return estilos.computeIfAbsent(clave, k -> {
            CellStyle estilo = libro.createCellStyle();
            DataFormat formato = libro.createDataFormat();
            switch (tipo) {
                case ENTERO -> estilo.setDataFormat(formato.getFormat("0"));
                case DECIMAL -> estilo.setDataFormat(formato.getFormat(
                        decimales <= 0 ? "#,##0" : "#,##0." + "0".repeat(decimales)));
                case FECHA -> {
                    estilo.setDataFormat(formato.getFormat("dd/mm/yyyy"));
                    estilo.setAlignment(HorizontalAlignment.CENTER);
                }
                case FECHA_HORA -> {
                    estilo.setDataFormat(formato.getFormat("dd/mm/yyyy hh:mm"));
                    estilo.setAlignment(HorizontalAlignment.CENTER);
                }
                case SI_NO -> estilo.setAlignment(HorizontalAlignment.CENTER);
                case TEXTO -> estilo.setDataFormat(formato.getFormat("@"));
            }
            return estilo;
        });
    }

    /** Escribe una fila de titulos. */
    public void escribirEncabezado(Row fila, String[] titulos) {
        for (int i = 0; i < titulos.length; i++) {
            Cell celda = fila.createCell(i, CellType.STRING);
            celda.setCellValue(titulos[i]);
            celda.setCellStyle(estiloEncabezado());
        }
    }

    /** Escribe un valor en la celda segun su tipo real; null deja la celda vacia. */
    public void escribirValor(Cell celda, Object valor, ColumnaExcel.Tipo tipo, int decimales) {
        celda.setCellStyle(estiloDatos(tipo, decimales));
        if (valor == null) {
            celda.setBlank();
            return;
        }
        switch (tipo) {
            case ENTERO, DECIMAL -> escribirNumero(celda, valor);
            case FECHA -> {
                if (valor instanceof LocalDateTime fechaHora) {
                    celda.setCellValue(fechaHora.toLocalDate());
                } else if (valor instanceof LocalDate fecha) {
                    celda.setCellValue(fecha);
                } else {
                    escribirTexto(celda, valor.toString());
                }
            }
            case FECHA_HORA -> {
                if (valor instanceof LocalDateTime fechaHora) {
                    celda.setCellValue(fechaHora);
                } else if (valor instanceof LocalDate fecha) {
                    celda.setCellValue(fecha);
                } else {
                    escribirTexto(celda, valor.toString());
                }
            }
            case SI_NO -> {
                if (valor instanceof Boolean b) {
                    escribirTexto(celda, b ? "Sí" : "No");
                } else {
                    escribirTexto(celda, valor.toString());
                }
            }
            case TEXTO -> escribirTexto(celda, valor instanceof Enum<?> e ? e.name() : valor.toString());
        }
    }

    private void escribirNumero(Cell celda, Object valor) {
        if (valor instanceof BigDecimal bd) {
            celda.setCellValue(bd.doubleValue());
        } else if (valor instanceof Number n) {
            celda.setCellValue(n.doubleValue());
        } else {
            escribirTexto(celda, valor.toString());
        }
    }

    private void escribirTexto(Cell celda, String texto) {
        String seguro = neutralizarFormula(texto);
        if (seguro.length() > MAX_CELDA) {
            seguro = seguro.substring(0, MAX_CELDA);
        }
        celda.setCellValue(seguro);
    }
}
