package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// Fase A: casos de zona que una persona debe revisar. Solo lectura: no corrige nada por si solo.
public record RevisionZonasResponse(
    List<PeriodoParaRevisar> periodos,
    long totalEntregasSinZona,
    // Primeras N entregas sin zona (N = limiteEntregas); totalEntregasSinZona es el total real.
    int limiteEntregas,
    List<EntregaSinZona> entregasSinZona
) {

    // Periodo de historial_zona con hasta anterior a desde (reasignaciones el mismo dia con el codigo anterior).
    public record PeriodoParaRevisar(
        UUID id,
        UUID proveedorId,
        String proveedorNombre,
        String zonaNombre,
        LocalDate desde,
        LocalDate hasta,
        String nota
    ) {}

    // motivo: DIA_DE_CAMBIO (la entrega cae en un dia en que el proveedor cambio de zona y no se registro la hora),
    // SIN_PERIODO (ningun periodo valido cubre la fecha) u OTRO.
    public record EntregaSinZona(
        UUID registroId,
        UUID proveedorId,
        String proveedorNombre,
        LocalDateTime fechaHora,
        BigDecimal litros,
        String motivo
    ) {}
}
