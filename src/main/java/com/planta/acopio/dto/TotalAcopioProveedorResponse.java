package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.UUID;

// Paginacion pendiente: total de litros por proveedor para TODO el filtro
// (GET /api/registros-acopio/resumen-por-proveedor), no solo la pagina visible.
public record TotalAcopioProveedorResponse(
    UUID proveedorId,
    String proveedorNombre,
    BigDecimal totalLitros,
    long cantidad
) {}
