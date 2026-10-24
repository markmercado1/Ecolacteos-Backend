package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase D: una entrega del proveedor con su zona de ESE momento (Fase A), su calidad y su liquidacion. `calidad` solo para ADMIN y CALIDAD;
// `liquidacion` solo para ADMIN y RECEPCION (y nula si la entrega aun no esta liquidada). Sin DNI ni telefono.
public record HistorialEntregaResponse(
    UUID id,
    LocalDateTime fechaHora,
    BigDecimal litrosOriginales,
    BigDecimal litrosEfectivos,
    boolean corregida,
    // Zona que tenia el proveedor cuando entrego; nula si el sistema no pudo determinarla (ver la pantalla de revision de zonas).
    String zonaNombre,
    CalidadEntrega calidad,
    LiquidacionEntrega liquidacion
) {

    /** Ultimo analisis de la entrega (resultado nulo si no tiene), cuantos analisis tiene, alertas de anomalia y si tiene precio de calidad propio. */
    public record CalidadEntrega(
        String resultado,
        String folio,
        LocalDateTime analizadoEn,
        int analisis,
        int alertas,
        boolean precioPropio
    ) {}

    /**
     * Pago que liquido la entrega. importe = litrosLiquidados x precioAplicado SIN redondear (el total del pago se redondea una sola vez al
     * final: la suma de los importes de un pago, redondeada, es su totalAPagar).
     */
    public record LiquidacionEntrega(
        UUID pagoId,
        LocalDate semanaInicio,
        LocalDate semanaFin,
        BigDecimal litrosLiquidados,
        BigDecimal precioAplicado,
        BigDecimal importe
    ) {}
}
