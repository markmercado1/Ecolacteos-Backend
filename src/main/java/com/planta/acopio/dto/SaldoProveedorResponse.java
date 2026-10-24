package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// Pagos efectivos: situacion de un proveedor. saldoLiquidaciones = suma de los saldos de sus liquidaciones; adelantosSinAplicar = lo
// desembolsado y vigente que aun no se aplico a ninguna liquidacion; saldoNeto = saldoLiquidaciones - adelantosSinAplicar (negativo = a favor).
public record SaldoProveedorResponse(
    UUID proveedorId,
    String proveedorNombre,
    List<SaldoPagoResponse> liquidaciones,
    BigDecimal saldoLiquidaciones,
    BigDecimal adelantosSinAplicar,
    BigDecimal saldoNeto
) {}
