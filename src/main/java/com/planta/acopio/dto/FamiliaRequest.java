package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;

public record FamiliaRequest(
    @NotBlank String nombre
) {}
