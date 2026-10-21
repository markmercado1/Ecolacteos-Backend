package com.planta.acopio.dto;

import com.planta.acopio.entity.EstadoJornada;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record JornadaResponse(
    UUID id,
    String uuidCliente,
    UUID unidadId,
    EstadoJornada estado,
    LocalDateTime horaInicio,
    BigDecimal gpsLatInicio,
    BigDecimal gpsLngInicio,
    LocalDateTime horaFin,
    BigDecimal gpsLatFin,
    BigDecimal gpsLngFin,
    String motivoCancelacion
) {}
