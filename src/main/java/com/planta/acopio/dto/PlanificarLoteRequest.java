package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// Planificar una produccion desde una receta (V18). No consume stock ni registra producto terminado: solo
// congela el plan (litros, insumos escalados y cantidad esperada). Idempotente por uuidCliente, mismo
// criterio que CrearLoteRequest.
public record PlanificarLoteRequest(
    @NotBlank String uuidCliente,
    @NotNull LocalDate fecha,
    @NotNull UUID recetaId,
    @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 7, fraction = 2) BigDecimal litros
) {}
