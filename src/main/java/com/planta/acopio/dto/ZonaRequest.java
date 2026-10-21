package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;

public record ZonaRequest(@NotBlank String nombre) {}
