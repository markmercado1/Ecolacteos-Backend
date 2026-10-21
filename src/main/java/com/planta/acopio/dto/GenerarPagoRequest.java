package com.planta.acopio.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

public record GenerarPagoRequest(
    @NotNull UUID proveedorId,
    @NotNull LocalDate semanaInicio,
    @NotNull LocalDate semanaFin
) {}
