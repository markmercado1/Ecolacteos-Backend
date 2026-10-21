package com.planta.acopio.dto;

import java.math.BigDecimal;

// Calculado desde movimiento_inventario_leche (SUM(cantidad), un unico pool de planta), nunca leido de una
// tabla de saldo mutable. Ver InventarioLecheService.obtenerSaldoDisponibleLitros().
public record SaldoLecheResponse(
    BigDecimal litrosDisponibles
) {}
