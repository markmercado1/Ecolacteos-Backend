package com.planta.acopio.dto;

import java.time.LocalDate;
import java.util.UUID;

// Fase 2: un lote con saldo dentro de ExistenciasPresentacionResponse. `origen` = LOTE (salida de un lote de
// produccion) o REGULARIZACION (existencias antiguas etiquetadas a mano). `diasParaVencer`: 0 = vence hoy (aun
// vendible, es el ultimo dia valido); negativo = ya vencido.
public record LoteExistenciaResponse(
    String origen,
    UUID id,
    UUID loteProduccionId,
    LocalDate fechaLote,
    LocalDate fechaVencimiento,
    int saldo,
    boolean vencido,
    long diasParaVencer
) {}
