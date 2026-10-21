package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RecepcionPlantaRequest(
    @NotNull LocalDate fecha,
    String turno,                            // opcional, default "UNICO"
    @NotNull UUID unidadId,
    @NotNull @DecimalMin("0.0") BigDecimal litrosCampo,
    @NotNull @DecimalMin("0.0") BigDecimal litrosPlanta,
    // Fase 3, decision de negocio 3 (opcion B, confirmada): seleccion
    // explicita de que RegistroAcopio componen esta recepcion (mismo patron
    // que CrearLoteRequest.registroAcopioIds). Opcional (no @NotEmpty a
    // proposito): litrosCampo=0 con litrosPlanta>0 es un caso valido sin
    // ningun registro que vincular (ver ConciliacionService.calcularEstado).
    List<UUID> registroAcopioIds
) {}
