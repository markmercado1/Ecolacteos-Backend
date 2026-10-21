package com.planta.acopio.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

// Fase 2 (solo web, ADMIN): reclasifica `cantidad` unidades del saldo "sin trazabilidad" de una presentacion a
// un lote con la fecha de vencimiento de la etiqueta fisica. El motivo es obligatorio. La fecha la lee una
// persona de la etiqueta (nunca la inventa el sistema). Una fecha pasada es valida: deja esas unidades como
// vencidas, listas para darse de baja con un ajuste.
public record RegularizacionRequest(
    @NotNull @Min(value = 1, message = "La cantidad debe ser al menos 1") Integer cantidad,
    @NotNull LocalDate fechaVencimiento,
    @NotBlank @Size(max = 300, message = "El motivo no puede superar los 300 caracteres") String motivo
) {}
