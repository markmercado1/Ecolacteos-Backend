package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Ultimo resultado de calidad para una entrega, en la fecha de esa entrega. */
public record AnalisisBandejaResponse(
    UUID registroAcopioId,
    UUID proveedorId,
    String proveedorNombre,
    LocalDateTime fechaHora,
    BigDecimal litros,
    String resultado
) {}
