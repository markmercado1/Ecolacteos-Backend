package com.planta.acopio.dto;

import com.planta.acopio.entity.EstadoJornada;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// Fase 2 (docs/ESPEC_JORNADA_TRAZABILIDAD.md): detalle completo de una jornada para RECEPCION -- vehiculo,
// placa, acopiador, hora de inicio, hora de fin de recoleccion, proveedores visitados, acopios individuales
// y total esperado (todo lo que pide la ESPEC §14 antes de registrar la recepcion).
public record JornadaDetalleResponse(
    UUID id,
    String uuidCliente,
    EstadoJornada estado,
    UUID unidadId,
    String unidadPlaca,
    UUID acopiadorId,
    String acopiadorNombre,
    LocalDateTime horaInicio,
    LocalDateTime horaFin,
    BigDecimal litrosEsperados,
    List<String> proveedoresVisitados,
    List<AcopioDeJornadaResponse> acopios
) {}
