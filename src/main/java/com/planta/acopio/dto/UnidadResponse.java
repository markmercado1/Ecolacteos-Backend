package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record UnidadResponse(
    UUID id,
    String placa,
    BigDecimal capacidadTon,
    UUID zonaId,
    UUID responsableId,
    String responsableNombre
) {}
