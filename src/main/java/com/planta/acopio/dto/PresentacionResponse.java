package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PresentacionResponse(
    UUID id,
    UUID productoId,
    String nombre,
    BigDecimal contenidoCantidad,
    String contenidoUnidad,
    String unidadConteo,
    Boolean activo,
    // URL publica de la foto propia de la presentacion, o null (el catalogo usa entonces la del producto).
    String fotoUrl
) {}
