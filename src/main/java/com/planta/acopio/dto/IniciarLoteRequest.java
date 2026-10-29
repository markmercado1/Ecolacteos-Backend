package com.planta.acopio.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Iniciar una produccion eligiendo a mano de que lotes de insumo sale cada insumo. Opcional: sin cuerpo (o sin indicar un insumo), el
 * consumo se reparte solo en FEFO. Para un insumo indicado, la suma de sus partes debe ser EXACTAMENTE lo que pide el plan, cada lote debe
 * ser de ese insumo, NO estar vencido y tener esa cantidad disponible.
 */
public record IniciarLoteRequest(@Valid List<InsumoLotes> insumos) {

    public record InsumoLotes(@NotNull UUID insumoId, @Valid List<Parte> lotes) {}

    public record Parte(@NotNull UUID loteId, @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal cantidad) {}
}
