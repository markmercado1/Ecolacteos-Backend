package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

// Una fila de insumo de la receta: cantidad POR litrosBase, en la unidad de medida del propio insumo.
public record RecetaInsumoRequest(
    @NotNull UUID insumoId,
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3) BigDecimal cantidad
) {}
