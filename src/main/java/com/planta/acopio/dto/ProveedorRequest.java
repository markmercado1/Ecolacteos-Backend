package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record ProveedorRequest(
    @NotBlank String nombre,
    @NotBlank String dni,
    String telefono,
    UUID zonaActualId
) {}
