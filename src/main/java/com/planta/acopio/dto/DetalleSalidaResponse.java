package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.UUID;

// Fase 3 (Produccion con varias presentaciones de salida): una fila de la seccion "Presentaciones
// obtenidas" de un lote ya registrado. contenidoCantidad/contenidoUnidad/unidadConteo vienen denormalizados
// desde Presentacion para que la UI muestre "20 x Botella 2 L" sin una llamada aparte al catalogo.
public record DetalleSalidaResponse(
    UUID presentacionId,
    String presentacionNombre,
    BigDecimal contenidoCantidad,
    String contenidoUnidad,
    String unidadConteo,
    Integer cantidadObtenida
) {}
