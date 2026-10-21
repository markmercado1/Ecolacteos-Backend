package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.UUID;

// Compatibilidad con el movil: CambiosResponse.tiposQueso sigue siendo un campo obligatorio en la app
// (data/remote/dto/CambiosResponse.kt). Se deriva de Producto; sin el, la app falla al deserializar
// /api/sync/cambios y no guarda ningun catalogo (ni unidades).
public record TipoQuesoResponse(
    UUID id,
    String nombre,
    BigDecimal rendimientoEsperadoPct,
    String cicloCapital,
    Boolean activo
) {}
