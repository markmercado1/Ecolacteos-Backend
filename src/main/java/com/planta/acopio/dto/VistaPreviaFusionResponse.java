package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Lo que haria fusionar `origen` dentro de `destino`, sin cambiar nada. Si hay impedimentos, la fusion no se permite. */
public record VistaPreviaFusionResponse(
    UUID origenId,
    String origenNombre,
    UUID destinoId,
    String destinoNombre,
    String unidadMedida,
    BigDecimal stockOrigen,
    BigDecimal stockDestino,
    BigDecimal stockResultante,
    long movimientos,
    long retiros,
    long recetas,
    long lotesProduccion,
    long informes,
    List<String> impedimentos,
    boolean fusionable
) {}
