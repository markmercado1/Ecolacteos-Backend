package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record DetalleVentaResponse(
    UUID presentacionId,
    String presentacionNombre,
    String productoNombre,
    Integer cantidad,
    BigDecimal precioUnitario,
    BigDecimal subtotal,
    // Fase G: foto tributaria de la linea (todo null = sin determinar). valorVenta + igv = subtotal: el precio ya incluye el IGV.
    String afectacionIgv,
    BigDecimal tasaIgv,
    BigDecimal valorVenta,
    BigDecimal igv,
    // V48: codigo de la presentacion (PRD-001...), para el comprobante.
    String codigo
) {
    public DetalleVentaResponse(UUID presentacionId, String presentacionNombre, String productoNombre, Integer cantidad,
                                BigDecimal precioUnitario, BigDecimal subtotal) {
        this(presentacionId, presentacionNombre, productoNombre, cantidad, precioUnitario, subtotal, null, null, null, null, null);
    }

    public DetalleVentaResponse(UUID presentacionId, String presentacionNombre, String productoNombre, Integer cantidad,
                                BigDecimal precioUnitario, BigDecimal subtotal, String afectacionIgv, BigDecimal tasaIgv,
                                BigDecimal valorVenta, BigDecimal igv) {
        this(presentacionId, presentacionNombre, productoNombre, cantidad, precioUnitario, subtotal, afectacionIgv, tasaIgv, valorVenta, igv, null);
    }
}
