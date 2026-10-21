package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;

// PUT /api/informes-produccion/{id}. Sin uuidCliente ni fecha (la fecha de un informe ya creado no se
// reasigna -- crear uno nuevo si la fecha estaba mal); reemplaza por completo quesos/recorte/insumos, igual
// que la correccion a mano en el papel.
public record EditarInformeProduccionRequest(
    @NotNull @Min(0) Integer quesosDiarios,
    @Min(0) Integer quesosReparados,
    @Min(0) Integer quesosDeRecorte,
    @DecimalMin(value = "0") BigDecimal recorteEnKilos,
    @Valid List<InsumoUsadoRequest> insumos
) {}
