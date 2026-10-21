package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

// Una fila de insumo usado dentro de Crear/EditarInformeProduccionRequest.insumos.
public record InsumoUsadoRequest(
    @NotNull UUID insumoId,
    @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal cantidad
) {}
