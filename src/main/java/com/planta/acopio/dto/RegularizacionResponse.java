package com.planta.acopio.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record RegularizacionResponse(
    UUID id,
    UUID presentacionId,
    int cantidad,
    LocalDate fechaVencimiento,
    // La fecha ya paso: estas unidades quedan como vencidas (no vendibles).
    boolean vencido,
    String motivo,
    LocalDateTime creadoEn,
    // Lo que sigue sin trazabilidad en la presentacion despues de esta regularizacion.
    int sinTrazabilidadRestante
) {}
