package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CorreccionRegistroRequest(
    @NotNull @DecimalMin("0.0") BigDecimal litrosCorregido,
    String motivo
) {}
