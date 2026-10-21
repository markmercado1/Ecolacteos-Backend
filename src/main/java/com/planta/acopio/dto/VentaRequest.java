package com.planta.acopio.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// Fase 3 (Inventario por presentacion): presentacionId reemplaza a productoId -- el usuario selecciona una
// presentacion concreta (no "el producto", con una resolucion automatica de por medio); el backend valida y
// descuenta stock de esa MISMA presentacion.
//
// Fase 4 (Venta multi-linea / carrito): este DTO y POST /api/ventas se CONSERVAN tal cual -- es el contrato
// real que usa la app movil para su sync offline (ver SyncService.procesarLoteVentas, shared/.../VentaDto.kt
// en acopio-mobile). El carrito multi-linea es un endpoint nuevo (CrearVentaCarritoRequest,
// POST /api/ventas/carrito), exclusivo de la web; internamente, una venta creada por este DTO sigue
// generando exactamente una fila de VentaDetalle (ver VentaService).
public record VentaRequest(
    @NotBlank String uuidCliente,
    @NotNull LocalDate fecha,
    @NotBlank String tipoCliente,           // MAYORISTA | PROVEEDOR | PUBLICO
    @NotNull UUID presentacionId,
    @NotNull @Min(1) Integer cantidad,
    @NotNull @DecimalMin("0.0") BigDecimal precioUnitario
) {}
