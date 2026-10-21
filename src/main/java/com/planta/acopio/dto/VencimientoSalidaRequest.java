package com.planta.acopio.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

// Fase 1: vencimiento de UNA salida de un lote (identificada por su presentacion), registrado por una persona.
// Solo web (FinalizarLoteRequest): CrearLoteRequest, el contrato compartido, no cambia.
public record VencimientoSalidaRequest(
    @NotNull UUID presentacionId,
    @NotNull LocalDate fechaVencimiento
) {}
