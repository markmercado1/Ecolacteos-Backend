package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Una fila del historial de cambios de precio de un pago. totalAnterior/totalNuevo = litros del pago x precio.
public record AjustePrecioPagoResponse(
    UUID id,
    UUID pagoId,
    BigDecimal precioLitroAnterior,
    BigDecimal precioLitroNuevo,
    BigDecimal totalAnterior,
    BigDecimal totalNuevo,
    String motivo,
    String usuarioNombre,
    LocalDateTime creadoEn
) {}
