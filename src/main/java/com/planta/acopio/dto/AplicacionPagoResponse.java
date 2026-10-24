package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record AplicacionPagoResponse(
    UUID id,
    UUID pagoId,
    LocalDate semanaInicio,
    LocalDate semanaFin,
    BigDecimal monto,
    String usuarioNombre,
    LocalDateTime creadoEn
) {}
