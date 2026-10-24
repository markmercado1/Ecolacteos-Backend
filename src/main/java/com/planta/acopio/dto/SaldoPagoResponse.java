package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// Pagos efectivos: saldo derivado de UNA liquidacion (no se guarda).
//   totalConAjustes = totalAPagar (total reconocido, con precios de calidad) + ajustesLitros (± de las correcciones posteriores)
//   saldo           = totalConAjustes - aplicado (desembolsos vigentes); negativo = saldo a favor del proveedor
//   estado          = PENDIENTE (nada aplicado) | PARCIAL | PAGADO (saldo 0) | SALDO_A_FAVOR
public record SaldoPagoResponse(
    UUID pagoId,
    UUID proveedorId,
    LocalDate semanaInicio,
    LocalDate semanaFin,
    BigDecimal totalAPagar,
    BigDecimal ajustesLitros,
    BigDecimal totalConAjustes,
    BigDecimal aplicado,
    BigDecimal saldo,
    String estado
) {}
