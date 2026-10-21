package com.planta.acopio.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// DTO de creacion (offline-first): uuidCliente es la clave de idempotencia
// generada en el telefono (CORE_DESIGN.md §2.2.2 / §10).
public record RegistroAcopioDTO(
    @NotBlank String uuidCliente,
    @NotNull UUID proveedorId,
    @NotNull UUID unidadId,
    @NotNull LocalDateTime fechaHora,
    @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal litros,
    BigDecimal gpsLat,
    BigDecimal gpsLng,
    UUID motivoObservacionId,           // nullable
    Boolean litrosPorVoz,
    String fotoUrl,
    // Fase 1 de jornada (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend): id de servidor de la
    // Jornada activa al momento de la captura, ya resuelto por el motor de sync del movil -- null si no
    // habia jornada activa (captura sin jornada, compatible con el flujo actual sin romper nada).
    UUID jornadaId
) {}
