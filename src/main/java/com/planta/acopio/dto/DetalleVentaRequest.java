package com.planta.acopio.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

// Fase 4 (Venta multi-linea / carrito): una fila del carrito. cantidad es siempre >= 1. Las validaciones de
// existencia, estado activo y no-duplicados se hacen en VentaService (no expresables como anotacion de campo
// sin conocer el resto del request).
public record DetalleVentaRequest(
    @NotNull UUID presentacionId,
    @NotNull @Min(1) Integer cantidad
) {}
