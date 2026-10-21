package com.planta.acopio.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import java.util.UUID;

public record RutaItemRequest(
    @NotNull UUID proveedorId,
    @NotNull @Min(1) Integer orden,
    LocalTime horaEstimada
) {}
