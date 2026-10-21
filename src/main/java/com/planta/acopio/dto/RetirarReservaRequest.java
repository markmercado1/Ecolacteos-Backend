package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Fase 4: retiro de una reserva confirmada con TODAS sus lineas. Crea la venta real (flujo normal de ventas) y marca la
// reserva RETIRADA en la misma transaccion. `uuidCliente` es la clave de idempotencia de esa venta.
public record RetirarReservaRequest(
    @NotBlank @Size(max = 36) String uuidCliente
) {}
