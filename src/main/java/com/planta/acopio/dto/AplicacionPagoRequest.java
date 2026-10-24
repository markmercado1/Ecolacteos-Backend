package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

// Pagos efectivos: cuanto de un desembolso se aplica a UNA liquidacion.
public record AplicacionPagoRequest(
    @NotNull UUID pagoId,
    @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal monto
) {}
