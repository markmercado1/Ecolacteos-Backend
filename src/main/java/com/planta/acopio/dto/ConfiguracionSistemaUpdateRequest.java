package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfiguracionSistemaUpdateRequest(
    @NotBlank String valor
) {}
