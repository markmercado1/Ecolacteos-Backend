package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase B: una entrega liquidada en un pago, con sus cantidades y su precio. No incluye datos personales del proveedor (RNF-12):
// RECEPCION puede consultarla igual que el resto de la lectura de pagos.
public record LineaPagoResponse(
    UUID registroAcopioId,
    LocalDateTime fechaHora,
    // Lo que se recibio (inmutable).
    BigDecimal litrosOriginales,
    // Lo efectivamente liquidado al generar el pago (original + ultima correccion valida de ese momento).
    BigDecimal litrosLiquidados,
    // Liquidados mas lo que sumaron los ajustes de litros posteriores = lo que el pago tiene en cuenta hoy.
    BigDecimal litrosContabilizados,
    // Original o ultima correccion valida de hoy (puede diferir de contabilizados solo si hay una correccion sin ajuste).
    BigDecimal litrosEfectivosActuales,
    // Precio con el que se genero el pago.
    BigDecimal precioBase,
    // Precio efectivamente aplicado hoy a ESTA entrega: su ajuste de calidad propio o, si no tiene, el del pago (con sus ajustes).
    BigDecimal precioAplicado,
    UUID correccionAplicadaId,
    // Fase C: true si la entrega tiene precio de calidad propio (que manda sobre el ajuste del pago completo), con su analisis y motivo.
    boolean precioPropioDeCalidad,
    UUID analisisCalidadId,
    String motivoAjusteCalidad
) {}
