package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CorreccionRegistroRequest(
    @NotNull @DecimalMin("0.0") BigDecimal litrosCorregido,
    // Fase B: una correccion de litros solo es valida con una justificacion registrada (texto libre).
    @NotBlank String motivo
) {}
