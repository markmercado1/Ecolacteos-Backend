package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record CorreccionRegistroResponse(
    UUID id,
    UUID registroAcopioId,
    BigDecimal litrosAnterior,
    BigDecimal litrosCorregido,
    String motivo,
    String usuarioNombre,
    LocalDateTime creadoEn
) {}
