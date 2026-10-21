package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AnalisisCalidadResponse(
    UUID id,
    UUID registroAcopioId,
    String folioMuestra,
    BigDecimal agua,
    BigDecimal proteina,
    BigDecimal lactosa,
    BigDecimal densidad,
    BigDecimal temperatura,
    BigDecimal ph,
    Boolean aguaAnadida,
    String resultado,
    LocalDateTime creadoEn
) {}
