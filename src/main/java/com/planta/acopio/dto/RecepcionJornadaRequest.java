package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase 2 (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend): recepcion vinculada a una Jornada.
// Offline-first, idempotente por uuidCliente (mismo criterio que JornadaDTO/RegistroAcopioDTO) -- a
// diferencia de RecepcionPlantaRequest (flujo historico, online-only, sin uuidCliente), este SI lo tiene
// porque RECEPCION debe poder capturar sin conexion y sincronizar despues (ESPEC §14/§18).
//
// A proposito NO tiene litrosCampo: RECEPCION nunca vuelve a escribir los litros de campo -- el backend los
// calcula sumando los RegistroAcopio asociados a la jornada (ver ConciliacionService.registrarRecepcionDeJornada).
public record RecepcionJornadaRequest(
    @NotBlank String uuidCliente,
    @NotNull UUID jornadaId,
    @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal litrosReales,
    String turno,                            // opcional, default "UNICO" (igual que RecepcionPlantaRequest)
    @NotNull LocalDateTime horaLlegada,
    String observacion                       // opcional
) {}
