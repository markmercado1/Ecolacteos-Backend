package com.planta.acopio.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record VencimientoLoteResponse(
    UUID detalleId,
    UUID loteId,
    UUID presentacionId,
    LocalDate fechaVencimiento,
    // La fecha registrada originalmente (solo si hubo al menos una correccion).
    LocalDate fechaVencimientoOriginal,
    LocalDateTime corregidoEn,
    String motivoCorreccion
) {}
