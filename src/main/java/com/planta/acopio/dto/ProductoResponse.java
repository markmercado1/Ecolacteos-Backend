package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ProductoResponse(
    UUID id,
    String nombre,
    UUID familiaId,
    String familiaNombre,
    BigDecimal rendimientoEsperadoPct,
    String cicloCapital,
    Boolean activo,
    // Presentaciones activas e inactivas de este producto (la ficha del producto las gestiona embebidas,
    // sin un modulo aparte). Crear un producto no genera presentaciones automaticamente.
    List<PresentacionResponse> presentaciones,
    RangoRendimientoDto rangoRendimiento,
    // URL publica de la foto (GET sin autenticacion), o null si el producto no tiene foto.
    String fotoUrl
) {}
