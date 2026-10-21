package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Fase 4: motivo obligatorio para rechazar o cancelar una reserva.
public record MotivoReservaRequest(
    @NotBlank @Size(max = 300, message = "El motivo no puede superar los 300 caracteres") String motivo
) {}
