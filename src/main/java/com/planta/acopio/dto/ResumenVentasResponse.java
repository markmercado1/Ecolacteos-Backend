package com.planta.acopio.dto;

import java.math.BigDecimal;

// Totales de las ventas que cumplen un filtro (GET /api/ventas/resumen): cuantas son y cuanto suman. Mismos filtros y misma busqueda que el
// listado (GET /api/ventas), sobre TODAS las ventas del filtro, no solo la pagina que se esta viendo.
public record ResumenVentasResponse(
    long cantidad,
    BigDecimal total
) {}
