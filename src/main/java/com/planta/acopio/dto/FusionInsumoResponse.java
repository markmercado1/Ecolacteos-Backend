package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Registro de auditoria de una fusion de insumos. */
public record FusionInsumoResponse(
    UUID id,
    UUID origenId,
    String origenNombre,
    UUID destinoId,
    String destinoNombre,
    String unidadMedida,
    BigDecimal stockOrigen,
    BigDecimal stockDestinoAntes,
    BigDecimal stockDestinoDespues,
    int movimientos,
    int retiros,
    int recetas,
    int lotesProduccion,
    int informes,
    String usuarioNombre,
    LocalDateTime creadoEn
) {}
