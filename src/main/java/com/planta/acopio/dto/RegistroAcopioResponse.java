package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record RegistroAcopioResponse(
    UUID id,
    String uuidCliente,
    UUID proveedorId,
    String proveedorNombre,
    UUID unidadId,
    LocalDateTime fechaHora,
    BigDecimal litros,
    BigDecimal gpsLat,
    BigDecimal gpsLng,
    String motivoObservacion,
    Boolean litrosPorVoz,
    LocalDateTime sincronizadoEn,
    // Auditoria de bajo riesgo (Fase 6): se persistia y aceptaba en el DTO de
    // entrada, pero nunca se devolvia -- el frontend no podia mostrar la foto
    // de un registro ya guardado.
    String fotoUrl,
    // Fase 4 PRODUCCION (docs/PROMPTS_FINALIZAR_MOVIL.md): "disena e implementa
    // una forma verificable de distinguir los elegibles antes del POST" --
    // LoteProduccionService ya rechaza reutilizar un registro vinculado a otro
    // lote (v1: un registro pertenece a un unico lote), pero el cliente no
    // tenia forma de saberlo ANTES de intentar el POST. true = todavia no
    // pertenece a ningun LoteProduccion.
    Boolean disponibleParaLote,
    // Campo aditivo (trazabilidad GPS del recorrido de jornada, vista web /acopios): id de la Jornada a la
    // que pertenece este registro, o null si se capturo sin jornada activa (historico, o registros previos
    // a esta fase). Permite al frontend agrupar los RegistroAcopio de una misma jornada y reconstruir el
    // recorrido reutilizando GET /api/jornadas/{id} + este mismo endpoint, sin un endpoint nuevo. Aditivo y
    // al final del record: el movil deserializa con ignoreUnknownKeys=true (network/JsonConfig.kt), no rompe.
    UUID jornadaId
) {}
