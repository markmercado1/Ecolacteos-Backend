package com.planta.acopio.importacion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Lectura y validacion comun de celdas para los tipos de importacion (texto, numeros, formulas y errores de Excel). */
public final class ReglasCelda {

    private static final Pattern ESPACIOS = Pattern.compile("\\s+");
    // Caracteres de control salvo tabulador y saltos de linea (esos se reducen a un espacio con ESPACIOS).
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\t\\n\\r]]");

    private ReglasCelda() {
    }

    /** Texto sin espacios de mas (los internos repetidos se reducen a uno) y sin caracteres de control. */
    public static String limpiar(String texto) {
        if (texto == null) {
            return "";
        }
        return ESPACIOS.matcher(CONTROL.matcher(texto).replaceAll("")).replaceAll(" ").trim();
    }

    /**
     * El texto de una celda ya limpio; si la celda trae una formula o un error de Excel, agrega el motivo y devuelve null. Una
     * celda vacia devuelve "" (quien llama decide si es obligatoria).
     */
    public static String texto(FilaCruda fila, ColumnaImportacion columna, List<String> errores) {
        CeldaLeida celda = fila.celda(columna.clave());
        if (celda.formula()) {
            errores.add(columna.encabezado() + ": la celda contiene una fórmula; escribe el valor directamente.");
            return null;
        }
        if (celda.error()) {
            errores.add(columna.encabezado() + ": la celda muestra un error de Excel; corrígela.");
            return null;
        }
        return limpiar(celda.texto());
    }

    /** Texto obligatorio con largo maximo; null (y un error) si falta o es muy largo. */
    public static String textoObligatorio(FilaCruda fila, ColumnaImportacion columna, int maximo, List<String> errores) {
        String valor = texto(fila, columna, errores);
        if (valor == null) {
            return null;
        }
        if (valor.isEmpty()) {
            errores.add(columna.encabezado() + ": es obligatorio.");
            return null;
        }
        if (valor.length() > maximo) {
            errores.add(columna.encabezado() + ": tiene " + valor.length() + " caracteres y el máximo es " + maximo + ".");
            return null;
        }
        return valor;
    }

    /** Valores de la fila tal como llegaron (para mostrar una fila invalida): las formulas se muestran como «(fórmula)». */
    public static Map<String, String> crudos(FilaCruda fila, List<ColumnaImportacion> columnas) {
        Map<String, String> valores = new LinkedHashMap<>();
        for (ColumnaImportacion c : columnas) {
            CeldaLeida celda = fila.celda(c.clave());
            String texto = celda.formula() ? "(fórmula)" : celda.error() ? "(error de Excel)" : limpiar(celda.texto());
            valores.put(c.clave(), texto);
        }
        return valores;
    }
}
