package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;

public record MotivoObservacionRequest(@NotBlank String descripcion) {}
