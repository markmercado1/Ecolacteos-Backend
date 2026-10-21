package com.planta.acopio.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ConfirmarComunicadoRequest(
    @NotNull UUID proveedorId
) {}
