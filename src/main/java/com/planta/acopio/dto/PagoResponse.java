package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PagoResponse(
    UUID id,
    UUID proveedorId,
    String proveedorNombre,
    LocalDate semanaInicio,
    LocalDate semanaFin,
    BigDecimal litrosTotales,
    BigDecimal precioLitro,
    BigDecimal total,
    Boolean comprobanteGenerado,
    List<UUID> registroAcopioIds   // mejora #4: trazabilidad exacta
) {}
