package com.planta.acopio.dto;

import com.planta.acopio.entity.EstadoJornada;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase 2 (docs/ESPEC_JORNADA_TRAZABILIDAD.md): una fila de la bandeja de RECEPCION -- jornadas
// REGRESANDO_A_PLANTA disponibles para recibir. litrosEsperados es SUM(RegistroAcopio.litros) de la
// jornada, autoritativo (nunca editable desde el movil).
public record JornadaBandejaResponse(
    UUID id,
    String uuidCliente,
    EstadoJornada estado,
    UUID unidadId,
    String unidadPlaca,
    UUID acopiadorId,
    String acopiadorNombre,
    LocalDateTime horaInicio,
    LocalDateTime horaFin,
    int cantidadAcopios,
    int cantidadProveedores,
    BigDecimal litrosEsperados
) {}
