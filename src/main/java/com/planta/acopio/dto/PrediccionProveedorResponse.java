package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PrediccionProveedorResponse(
    UUID proveedorId,
    LocalDate fechaPrevista,
    BigDecimal litrosEstimadosMin,
    BigDecimal litrosEstimadosMax
) {}
