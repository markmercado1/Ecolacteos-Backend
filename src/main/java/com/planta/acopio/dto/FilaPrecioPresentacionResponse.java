package com.planta.acopio.dto;

import java.util.UUID;

// Fase 2 (ampliacion: listado paginado de /admin/precios-queso/presentaciones). Una fila por presentacion
// ACTIVA de un producto ACTIVO; un producto activo sin ninguna presentacion activa aparece como una unica
// fila informativa con presentacionId/presentacionNombre null y los tres resumenes en SIN_PRECIO -- nunca
// se inventa una presentacion para que "encaje" en la tabla. familiaId/familiaNombre denormalizados (via
// producto.familia) para no requerir una llamada aparte al agrupar/filtrar por familia en la UI.
public record FilaPrecioPresentacionResponse(
    UUID familiaId,
    String familiaNombre,
    UUID productoId,
    String productoNombre,
    UUID presentacionId,
    String presentacionNombre,
    PrecioModalidadResponse mayorista,   // compatibilidad: los tres tipos originales; la fuente completa es `porTipo`
    PrecioModalidadResponse proveedor,
    PrecioModalidadResponse publico,
    java.util.Map<String, PrecioModalidadResponse> porTipo   // un resumen por cada tipo del catalogo, por codigo
) {}
