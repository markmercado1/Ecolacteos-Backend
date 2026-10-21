package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record InsumoRequest(
    @NotBlank String nombre,
    @NotBlank String unidadMedida,
    // V17: stock disponible. Al crear: stock inicial (null = 0), primer movimiento del kardex (V19). Al editar:
    // null o el mismo valor actual; un valor distinto se rechaza (el stock cambia por ingresos/ajustes).
    @DecimalMin(value = "0.0") @Digits(integer = 10, fraction = 2) BigDecimal stock
) {
    public InsumoRequest(String nombre, String unidadMedida) {
        this(nombre, unidadMedida, null);
    }
}
