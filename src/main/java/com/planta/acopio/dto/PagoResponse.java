package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// precioLitro/total son los del pago ORIGINAL (inmutables). precioLitroEfectivo/totalAPagar ya incluyen el ajuste de
// precio mas reciente de ESTE pago (V24); sin ajustes son iguales a precioLitro/total. Campos aditivos al final: la
// app movil ignora los que no conoce.
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
    List<UUID> registroAcopioIds,   // mejora #4: trazabilidad exacta
    BigDecimal precioLitroEfectivo,
    BigDecimal totalAPagar
) {}
