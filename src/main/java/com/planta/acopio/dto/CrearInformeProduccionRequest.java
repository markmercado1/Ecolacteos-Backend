package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// POST /api/informes-produccion. Idempotente por uuidCliente (mismo criterio que RegistroAcopio/Jornada/
// LoteProduccion): la web no tiene cola offline, pero igual genera el uuid en el cliente para que un
// reintento de red tras un POST incierto no duplique el informe del dia.
public record CrearInformeProduccionRequest(
    @NotBlank String uuidCliente,
    @NotNull LocalDate fecha,
    @NotNull @Min(0) Integer quesosDiarios,
    @Min(0) Integer quesosReparados,
    @Min(0) Integer quesosDeRecorte,
    @DecimalMin(value = "0") BigDecimal recorteEnKilos,
    @Valid List<InsumoUsadoRequest> insumos
) {}
