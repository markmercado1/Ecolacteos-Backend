package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AlertaAnomaliaResponse(
    UUID id,
    UUID registroAcopioId,
    UUID proveedorId,
    String proveedorNombre,
    String tipo,
    BigDecimal zScore,
    String severidad,
    LocalDateTime creadoEn
) {}
