package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// Fase 4 (Venta multi-linea / carrito): forma CONSERVADA tal cual -- es la respuesta real de
// POST /api/ventas que la app movil deserializa (ver shared/.../VentaDto.kt en acopio-mobile). Solo la
// devuelve el endpoint de una sola linea; el listado/detalle/carrito de la web usan VentaResumenResponse.
public record VentaResponse(
    UUID id,
    LocalDate fecha,
    String tipoCliente,
    UUID presentacionId,
    String presentacionNombre,
    String productoNombre,
    Integer cantidad,
    BigDecimal precioUnitario,
    BigDecimal total
) {}
