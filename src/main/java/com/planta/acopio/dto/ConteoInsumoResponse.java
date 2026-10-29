package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ConteoInsumoResponse(
    UUID insumoId,
    String insumoNombre,
    String unidadMedida,
    BigDecimal saldoAnterior,
    BigDecimal saldoResultante,
    List<Diferencia> diferencias
) {
    /** Lo que cambio en un lote: lo que decia el sistema, lo contado y el movimiento AJUSTE que lo dejo asentado. */
    public record Diferencia(UUID loteId, String loteCodigo, BigDecimal cantidadSistema, BigDecimal cantidadContada,
                             BigDecimal variacion, UUID movimientoId) {}
}
