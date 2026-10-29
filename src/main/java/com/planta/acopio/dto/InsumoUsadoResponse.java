package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record InsumoUsadoResponse(
    UUID insumoId,
    String insumoNombre,
    String unidadMedida,
    BigDecimal cantidad,
    /** Lotes de insumo de los que salio (FEFO); vacio si el lote de produccion aun no consumio (planificado) o es anterior a V43. */
    List<LoteUsado> lotes
) {
    public InsumoUsadoResponse(UUID insumoId, String insumoNombre, String unidadMedida, BigDecimal cantidad) {
        this(insumoId, insumoNombre, unidadMedida, cantidad, List.of());
    }

    public record LoteUsado(UUID loteId, String codigo, String codigoProveedor, String proveedorNombre, java.time.LocalDate fechaVencimiento,
                            BigDecimal cantidad) {}
}
