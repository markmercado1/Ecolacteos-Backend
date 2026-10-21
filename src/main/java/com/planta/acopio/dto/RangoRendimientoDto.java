package com.planta.acopio.dto;

import com.planta.acopio.entity.RangoRendimiento;
import com.planta.acopio.entity.UnidadRendimiento;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record RangoRendimientoDto(
        @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 4) BigDecimal minimo,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 4) BigDecimal maximo,
        @NotNull UnidadRendimiento unidad) {

    @AssertTrue(message = "El rendimiento mínimo no puede superar al máximo")
    @JsonIgnore
    public boolean isOrdenValido() {
        return minimo == null || maximo == null || minimo.compareTo(maximo) <= 0;
    }

    public RangoRendimiento toEntity() {
        return new RangoRendimiento(minimo, maximo, unidad);
    }

    public static RangoRendimientoDto from(RangoRendimiento rango) {
        return rango == null ? null : new RangoRendimientoDto(rango.getMinimo(), rango.getMaximo(), rango.getUnidad());
    }
}
