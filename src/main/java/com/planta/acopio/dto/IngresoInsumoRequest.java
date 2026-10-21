package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

// Ingreso (reposicion) de un insumo, en su propia unidad de medida. Solo cantidad: sin costo (kardex no
// valorizado). referencia: documento de respaldo opcional (guia, factura, proveedor).
public record IngresoInsumoRequest(
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal cantidad,
    @Size(max = 100) String referencia,
    @Size(max = 500) String observacion
) {}
