package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ScoreConfianzaResponse(
    UUID proveedorId,
    LocalDate periodo,
    BigDecimal score,
    BigDecimal componenteCalidad,
    BigDecimal componenteRegularidad,
    BigDecimal componenteAnomalias
) {}
