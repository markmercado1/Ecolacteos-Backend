package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

// Nuevo precio por litro SOLO para el pago indicado en la URL. El motivo es obligatorio (auditoria de disputas).
public record AjustePrecioPagoRequest(
    @NotNull @DecimalMin("0.001") @Digits(integer = 3, fraction = 3) BigDecimal precioLitroNuevo,
    @NotBlank @Size(max = 500) String motivo
) {}
