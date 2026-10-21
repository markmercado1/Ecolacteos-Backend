package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// Kardex por cantidades (V19) de un item: leche (itemId null), un insumo o una presentacion de producto
// terminado. Los totales cubren el rango COMPLETO [desde, hasta] (no solo la pagina): saldoFinal =
// saldoInicial + totalEntradas - totalSalidas. Sin valorizacion monetaria por decision funcional.
public record KardexResponse(
    // LECHE | INSUMO | PRESENTACION
    String tipoItem,
    UUID itemId,
    String itemNombre,
    String unidadMedida,
    LocalDate desde,
    LocalDate hasta,
    BigDecimal saldoInicial,
    BigDecimal totalEntradas,
    BigDecimal totalSalidas,
    BigDecimal saldoFinal,
    PaginaResponse<MovimientoKardexResponse> movimientos
) {}
