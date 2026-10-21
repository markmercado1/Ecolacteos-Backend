package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record InsumoResponse(
    UUID id,
    String nombre,
    String unidadMedida,
    Boolean activo,
    BigDecimal stock
) {}
