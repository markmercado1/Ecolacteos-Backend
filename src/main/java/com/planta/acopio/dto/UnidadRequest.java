package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record UnidadRequest(
    @NotBlank String placa,
    BigDecimal capacidadTon,
    UUID zonaId,
    @NotNull UUID responsableId    // mejora #2: obligatorio
) {}
