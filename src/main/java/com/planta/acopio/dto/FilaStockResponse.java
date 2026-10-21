package com.planta.acopio.dto;

import java.util.UUID;

// Fase 3 (Inventario por presentacion, listado paginado /api/inventario/stock/presentaciones). Una fila por
// presentacion, visible si esta activa (junto a su producto) o si conserva stock remanente distinto de
// cero (producto/presentacion desactivados no pierden trazabilidad de lo que aun tienen en existencia).
public record FilaStockResponse(
    UUID familiaId,
    String familiaNombre,
    UUID productoId,
    String productoNombre,
    boolean productoActivo,
    UUID presentacionId,
    String presentacionNombre,
    String unidadConteo,
    boolean presentacionActivo,
    // Existencias fisicas (todo lo que hay en el libro, vencido o no).
    Integer stock,
    // Fase 2: solo la web. Si el producto controla vencimiento, `stockVendible` es lo que se puede vender hoy
    // (lotes vigentes); si no, es igual a `stock`.
    boolean controlaVencimiento,
    Integer stockVendible,
    // Fase 5 (solo web): unidades separadas por reservas vigentes (0 si el producto no controla vencimiento).
    Integer stockComprometido,
    // Fase 5: unidades que una venta directa puede tomar hoy sin dejar sin cobertura a las reservas (<= vendible).
    Integer stockLibre
) {}
