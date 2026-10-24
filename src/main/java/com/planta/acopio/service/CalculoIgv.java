package com.planta.acopio.service;

import com.planta.acopio.entity.AfectacionIgv;

import java.math.BigDecimal;
import java.math.RoundingMode;

// Fase G: desglose del IGV CONTENIDO en un importe final (los precios ya incluyen el impuesto; nunca se suma).
// Por linea: valor de venta = subtotal / (1 + tasa/100) redondeado a 2 decimales (HALF_UP); IGV = subtotal - valor, de modo
// que valor + IGV es exactamente el subtotal cobrado. El redondeo por linea (y no sobre el total) es una decision tecnica
// que el contador debe confirmar junto con la configuracion.
public final class CalculoIgv {

    private static final BigDecimal CIEN = new BigDecimal("100");

    private CalculoIgv() {}

    /** Foto tributaria de una linea. tasa solo para GRAVADO. */
    public record Desglose(AfectacionIgv afectacion, BigDecimal tasa, BigDecimal valorVenta, BigDecimal igv) {}

    public static Desglose gravado(BigDecimal subtotal, BigDecimal tasa) {
        BigDecimal total = subtotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal valor = total.multiply(CIEN).divide(CIEN.add(tasa), 2, RoundingMode.HALF_UP);
        return new Desglose(AfectacionIgv.GRAVADO, tasa, valor, total.subtract(valor));
    }

    /** Exonerado o inafecto: sin IGV, el valor de venta es el subtotal. */
    public static Desglose sinIgv(AfectacionIgv afectacion, BigDecimal subtotal) {
        BigDecimal total = subtotal.setScale(2, RoundingMode.HALF_UP);
        return new Desglose(afectacion, null, total, BigDecimal.ZERO.setScale(2));
    }
}
