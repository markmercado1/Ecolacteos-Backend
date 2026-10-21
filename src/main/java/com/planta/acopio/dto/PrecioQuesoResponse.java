package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// Se reutiliza tanto para la administracion de precios (AdminCatalogosController) como para la consulta
// de precio vigente antes de confirmar una venta (VentaController /precio-vigente) -- mismo shape, un solo
// DTO. productoId/productoNombre son denormalizados (via presentacion.producto) para que la UI no necesite
// una llamada aparte para mostrar a que producto pertenece la presentacion.
public record PrecioQuesoResponse(
    UUID id,
    UUID presentacionId,
    String presentacionNombre,
    UUID productoId,
    String productoNombre,
    String modalidad,
    BigDecimal precio,
    LocalDate vigenteDesde
) {}
