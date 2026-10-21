package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Resultado de registrar un ingreso o un ajuste de insumo: el movimiento escrito y el saldo antes/despues.
public record MovimientoInsumoResponse(
    UUID id,
    UUID insumoId,
    String insumoNombre,
    String unidadMedida,
    String tipoMovimiento,
    // Con signo: positiva entra, negativa sale (un ajuste puede ser de cualquiera de los dos).
    BigDecimal cantidad,
    BigDecimal saldoAnterior,
    BigDecimal saldoResultante,
    String referencia,
    String motivo,
    String usuarioNombre,
    LocalDateTime creadoEn
) {}
