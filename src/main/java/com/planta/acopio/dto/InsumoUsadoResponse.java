package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record InsumoUsadoResponse(
    UUID insumoId,
    String insumoNombre,
    String unidadMedida,
    BigDecimal cantidad
) {}
