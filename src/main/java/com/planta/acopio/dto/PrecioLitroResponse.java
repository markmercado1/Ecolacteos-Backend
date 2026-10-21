package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PrecioLitroResponse(
    UUID id,
    BigDecimal precio,
    LocalDate vigenteDesde
) {}
