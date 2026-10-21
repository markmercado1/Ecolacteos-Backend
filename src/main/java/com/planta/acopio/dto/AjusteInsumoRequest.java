package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

// Ajuste por conteo fisico de un insumo (mismo criterio que AjusteInventarioRequest de producto terminado):
// cantidadFisica es el conteo real, nunca un delta; el backend calcula la variacion contra el stock del
// sistema bajo bloqueo. motivo obligatorio.
public record AjusteInsumoRequest(
    @NotNull @DecimalMin("0.0") @Digits(integer = 10, fraction = 2) BigDecimal cantidadFisica,
    @NotBlank @Size(max = 500) String motivo
) {}
