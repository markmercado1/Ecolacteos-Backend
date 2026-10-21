package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Fase 4 (Venta multi-linea / carrito): forma usada por el listado (GET /api/ventas), el detalle
// (GET /api/ventas/{id}) y la confirmacion del carrito (POST /api/ventas/carrito) -- todos ellos exclusivos
// de la web (el contrato movil de una sola linea sigue siendo VentaResponse, sin cambios, ver VentaRequest).
public record VentaResumenResponse(
    UUID id,
    LocalDate fecha,
    String tipoCliente,
    List<DetalleVentaResponse> detalles,
    BigDecimal total
) {}
