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
    BigDecimal igv
) {
    public DetalleVentaResponse(UUID presentacionId, String presentacionNombre, String productoNombre, Integer cantidad,
                                BigDecimal precioUnitario, BigDecimal subtotal) {
        this(presentacionId, presentacionNombre, productoNombre, cantidad, precioUnitario, subtotal, null, null, null, null);
    }
}
