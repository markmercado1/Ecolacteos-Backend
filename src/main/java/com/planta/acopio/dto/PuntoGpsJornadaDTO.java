package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase 3 (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend): un punto del recorrido GPS. Offline-
// first, idempotente por uuidCliente (mismo criterio que JornadaDTO/RegistroAcopioDTO). `jornadaId` ya es
// el id de SERVIDOR de la jornada, resuelto por el motor de sync del movil antes de enviar -- nunca se
// sincroniza un punto cuya jornada todavia no se confirmo (mismo patron que RegistroAcopioDTO.jornadaId,
// pero aca es obligatorio: un punto sin jornada no tiene sentido, a diferencia de un RegistroAcopio que
// puede capturarse sin jornada activa).
public record PuntoGpsJornadaDTO(
    @NotBlank String uuidCliente,
    @NotNull UUID jornadaId,
    @NotNull @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0") BigDecimal lat,
    @NotNull @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0") BigDecimal lng,
    BigDecimal precisionM,                 // nullable -- solo si el dispositivo la proveyo
    @NotNull LocalDateTime capturadoEn
) {}
