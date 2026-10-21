package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

// mejora #3: reemplaza el antiguo campo de texto libre zonasDestino
public record CrearComunicadoRequest(
    @NotBlank String mensaje,
    @NotEmpty List<UUID> zonaIds
) {}
