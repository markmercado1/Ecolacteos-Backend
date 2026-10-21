package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase 3, decision de negocio 1 (opcion C): ajuste generado por una
// CorreccionRegistro anexada sobre un registro_acopio ya liquidado.
public record AjustePagoResponse(
    UUID id,
    UUID pagoId,
    UUID registroAcopioId,
    UUID correccionRegistroId,
    BigDecimal litrosDiferencia,
    BigDecimal precioLitro,
    BigDecimal monto,
    String estado,
    LocalDateTime creadoEn
) {}
