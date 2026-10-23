package com.planta.acopio.excel;

import java.util.function.Function;

/**
 * Una columna de una exportacion: titulo, como sacar el valor de cada fila y como se muestra en Excel.
 *
 * El valor se escribe segun su tipo real (texto, numero, fecha, Si/No); un texto SIEMPRE se escribe como texto y se
 * neutraliza si pudiera leerse como formula (ver {@link EscritorExcel#neutralizarFormula}).
 */
public record ColumnaExcel<T>(String titulo, Function<T, Object> valor, Tipo tipo, int decimales, int ancho) {

    public enum Tipo { TEXTO, ENTERO, DECIMAL, FECHA, FECHA_HORA, SI_NO }

    public static <T> ColumnaExcel<T> texto(String titulo, Function<T, Object> valor) {
        return new ColumnaExcel<>(titulo, valor, Tipo.TEXTO, 0, 24);
    }

    public static <T> ColumnaExcel<T> texto(String titulo, int ancho, Function<T, Object> valor) {
        return new ColumnaExcel<>(titulo, valor, Tipo.TEXTO, 0, ancho);
    }

    public static <T> ColumnaExcel<T> entero(String titulo, Function<T, Object> valor) {
        return new ColumnaExcel<>(titulo, valor, Tipo.ENTERO, 0, 14);
    }

    /** Numero con dos decimales (litros, importes). */
    public static <T> ColumnaExcel<T> decimal(String titulo, Function<T, Object> valor) {
        return new ColumnaExcel<>(titulo, valor, Tipo.DECIMAL, 2, 16);
    }

    public static <T> ColumnaExcel<T> decimal(String titulo, int decimales, Function<T, Object> valor) {
        return new ColumnaExcel<>(titulo, valor, Tipo.DECIMAL, decimales, 16);
    }

    public static <T> ColumnaExcel<T> fecha(String titulo, Function<T, Object> valor) {
        return new ColumnaExcel<>(titulo, valor, Tipo.FECHA, 0, 14);
    }

    public static <T> ColumnaExcel<T> fechaHora(String titulo, Function<T, Object> valor) {
        return new ColumnaExcel<>(titulo, valor, Tipo.FECHA_HORA, 0, 20);
    }

    /** Booleano mostrado como "Si" / "No". */
    public static <T> ColumnaExcel<T> siNo(String titulo, Function<T, Object> valor) {
        return new ColumnaExcel<>(titulo, valor, Tipo.SI_NO, 0, 12);
    }
}
