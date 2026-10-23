package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase C: un precio de calidad aplicado a una entrega de un pago. Trazabilidad: entrega -> analisis -> precio normal -> precio
// aplicado -> efecto en el total del pago.
public record AjusteCalidadEntregaResponse(
    UUID id,
    UUID pagoId,
    UUID registroAcopioId,
    // Analisis de calidad que lo sustenta (siempre de esta misma entrega).
    UUID analisisCalidadId,
    String analisisFolio,
    String analisisResultado,
    // Litros liquidados de la entrega en este pago.
    BigDecimal litrosLiquidados,
    // Precio normal con el que se genero el pago.
    BigDecimal precioNormal,
    // Precio que tenia la entrega justo antes de este ajuste y el nuevo.
    BigDecimal precioAnterior,
    BigDecimal precioLitroNuevo,
    // litros liquidados x (precio nuevo - precio normal): negativo = se paga menos por esta entrega. Es informativo (redondeado a
    // centimos); el total del pago se redondea una sola vez.
    BigDecimal efectoEnElTotal,
    String motivo,
    String usuarioNombre,
    LocalDateTime creadoEn
) {}
