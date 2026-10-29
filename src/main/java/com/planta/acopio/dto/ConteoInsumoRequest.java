package com.planta.acopio.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Conteo fisico POR LOTE (solo ADMIN): la cantidad realmente contada de cada lote. El sistema calcula la diferencia de cada lote
 * y deja un movimiento AJUSTE (positivo o negativo) en ese lote, con el motivo obligatorio. Los lotes que no se listan no cambian.
 */
public record ConteoInsumoRequest(
    @NotBlank @Size(max = 500) String motivo,
    @NotEmpty @Valid List<Linea> lotes
) {
    public record Linea(
        @NotNull UUID loteId,
        @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal cantidadContada
    ) {}
}
