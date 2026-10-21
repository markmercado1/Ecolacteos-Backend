package com.planta.acopio.dto;

import java.util.UUID;

// Fase 3 (Inventario por presentacion): consulta puntual de stock de UNA presentacion (usada por
// VentaFormDialog al seleccionar una presentacion, para no traer el listado paginado completo solo para
// mostrar un numero). Calculado igual que el resto del inventario: SUM(cantidad) del libro mayor.
public record StockPresentacionResponse(
    UUID presentacionId,
    Integer unidadesDisponibles
) {}
