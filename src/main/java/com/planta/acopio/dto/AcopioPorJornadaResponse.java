package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Paginacion pendiente: fila de la vista "Por jornada" de la web, agrupada en
// la BD (GET /api/registros-acopio/por-jornada). Si esJornada es false la
// fila es un registro sin jornada activa y clave == primerAcopioId.
public record AcopioPorJornadaResponse(
    UUID clave,
    boolean esJornada,
    String acopiadorNombre,
    String placa,
    LocalDateTime fecha,
    long cantidad,
    BigDecimal totalLitros,
    UUID primerAcopioId
) {}
