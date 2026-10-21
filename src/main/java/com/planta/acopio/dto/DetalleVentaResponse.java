package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record DetalleVentaResponse(
    UUID presentacionId,
    String presentacionNombre,
    String productoNombre,
    Integer cantidad,
    BigDecimal precioUnitario,
    BigDecimal subtotal
) {}
