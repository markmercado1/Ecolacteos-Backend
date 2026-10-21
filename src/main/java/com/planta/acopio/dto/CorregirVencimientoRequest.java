package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

// Fase 1: correccion de una fecha de vencimiento digitada incorrectamente, solo mientras el lote no se haya
// usado. El motivo es obligatorio.
public record CorregirVencimientoRequest(
    @NotNull LocalDate fechaVencimiento,
    @NotBlank @Size(max = 300, message = "El motivo no puede superar los 300 caracteres") String motivo
) {}
