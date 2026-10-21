package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record InformeProduccionDiarioResponse(
    UUID id,
    String uuidCliente,
    LocalDate fecha,
    Integer quesosDiarios,
    Integer quesosReparados,
    Integer quesosDeRecorte,
    // Derivado (quesosDiarios + quesosReparados + quesosDeRecorte), no una columna propia: evita que quede
    // desactualizado si se editan las partes y se olvida recalcular el total (igual criterio que
    // RegistroAcopioResponse.disponibleParaLote, calculado siempre al construir la respuesta).
    Integer totalQuesos,
    BigDecimal recorteEnKilos,
    String responsableNombre,
    LocalDateTime creadoEn,
    String actualizadoPorNombre,
    LocalDateTime actualizadoEn,
    List<InsumoUsadoResponse> insumos
) {}
