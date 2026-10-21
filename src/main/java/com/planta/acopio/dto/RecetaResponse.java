package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record RecetaResponse(
    UUID id,
    String nombre,
    UUID productoId,
    String productoNombre,
    UUID presentacionId,
    String presentacionNombre,
    // Como se cuenta la salida ("pieza", "botella"...), para mostrar "10 piezas esperadas".
    String unidadConteo,
    BigDecimal litrosBase,
    BigDecimal rendimientoBase,
    Boolean activo,
    List<Insumo> insumos
) {
    public record Insumo(
        UUID insumoId,
        String insumoNombre,
        String unidadMedida,
        BigDecimal cantidad,
        Boolean insumoActivo
    ) {}
}
