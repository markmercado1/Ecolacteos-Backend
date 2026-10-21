package com.planta.acopio.dto;

import com.planta.acopio.entity.EstadoJornada;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Creacion de una Jornada (offline-first): uuidCliente es la clave de idempotencia generada en el
// telefono. `estado`/`horaFin`/`gpsLatFin`/`gpsLngFin`/`motivoCancelacion` viajan porque un recorrido
// completo (inicio, acopios y fin) puede suceder enteramente sin conexion y sincronizarse recien al
// reconectar: en ese caso esta unica llamada crea la jornada YA en su estado final, sin pasar por un
// EN_RECOLECCION transitorio que nunca existio de cara al servidor. Si `estado` es null, se asume
// EN_RECOLECCION (recorrido todavia en curso al momento de sincronizar).
public record JornadaDTO(
    @NotBlank String uuidCliente,
    @NotNull UUID unidadId,
    @NotNull LocalDateTime horaInicio,
    BigDecimal gpsLatInicio,
    BigDecimal gpsLngInicio,
    EstadoJornada estado,
    LocalDateTime horaFin,
    BigDecimal gpsLatFin,
    BigDecimal gpsLngFin,
    String motivoCancelacion
) {}
