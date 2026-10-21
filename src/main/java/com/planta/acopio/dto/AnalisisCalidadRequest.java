package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record AnalisisCalidadRequest(
    @NotBlank String uuidCliente,          // mejora #1: capturable offline
    @NotNull UUID registroAcopioId,
    @NotBlank String folioMuestra,
    BigDecimal agua,
    BigDecimal proteina,
    BigDecimal lactosa,
    BigDecimal densidad,
    BigDecimal temperatura,
    BigDecimal ph,
    Boolean aguaAnadida
) {}
