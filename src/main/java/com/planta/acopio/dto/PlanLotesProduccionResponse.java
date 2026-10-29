package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Que lotes de insumo usaria al iniciar una produccion planificada (FEFO) y cuales hay para cambiarlos. Informativo: no toca nada. */
public record PlanLotesProduccionResponse(UUID loteProduccionId, List<Insumo> insumos) {

    public record Insumo(UUID insumoId, String insumoNombre, String unidadMedida, BigDecimal requerido,
                         /** El reparto FEFO propuesto (lo que pasaria sin elegir a mano). */
                         List<Parte> propuesta,
                         /** Lotes consumibles (con existencia y no vencidos) entre los que se puede elegir. */
                         List<Disponible> disponibles) {}

    public record Parte(UUID loteId, String codigo, LocalDate fechaVencimiento, BigDecimal cantidad) {}

    public record Disponible(UUID loteId, String codigo, String proveedorNombre, LocalDate fechaVencimiento, BigDecimal disponible) {}
}
