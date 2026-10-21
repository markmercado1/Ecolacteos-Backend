package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PrediccionZonaResponse(
    UUID zonaId,
    LocalDate semanaInicio,
    BigDecimal litrosEstimados,
    BigDecimal litrosReales,
    String recomendacionMezcla,
    BigDecimal flujoCajaProyectado,
    boolean alertaCapacidad
) {}
