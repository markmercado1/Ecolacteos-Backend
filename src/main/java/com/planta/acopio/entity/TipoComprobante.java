package com.planta.acopio.entity;

import com.planta.acopio.exception.ValidacionNegocioException;

import java.util.Locale;

// Tipo de comprobante de una venta. Cada tipo tiene su propia serie y su propio correlativo (ver V48). Mientras no exista la
// integracion con SUNAT ambos son comprobantes internos; el tipo y la serie ya quedan guardados para cuando exista.
public enum TipoComprobante {

    NOTA_VENTA("NV01", "Nota de venta"),
    BOLETA("B001", "Boleta de venta electrónica");

    private final String serie;
    private final String etiqueta;

    TipoComprobante(String serie, String etiqueta) {
        this.serie = serie;
        this.etiqueta = etiqueta;
    }

    public String serie() { return serie; }
    public String etiqueta() { return etiqueta; }

    /** null o en blanco = nota de venta (lo que hacian todas las ventas antes de existir el tipo). Un valor desconocido es un 400. */
    public static TipoComprobante deTexto(String texto) {
        if (texto == null || texto.isBlank()) return NOTA_VENTA;
        try {
            return valueOf(texto.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidacionNegocioException("Tipo de comprobante no válido: " + texto + ". Usa NOTA_VENTA o BOLETA.");
        }
    }

    /** "NV01-00000041": la serie y el numero a ocho cifras. */
    public static String formatear(String serie, Long numero) {
        return serie == null || numero == null ? null : serie + "-" + String.format("%08d", numero);
    }
}
