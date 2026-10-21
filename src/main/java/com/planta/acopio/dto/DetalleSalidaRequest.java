package com.planta.acopio.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

// Fase 3 (Produccion con varias presentaciones de salida): una fila del formulario "Presentaciones
// obtenidas". cantidadObtenida es siempre >= 1 -- nunca se envia ni se persiste una linea de cantidad cero
// (la ausencia total de rendimiento se representa con una lista de detalles VACIA en CrearLoteRequest, no
// con una fila en cero). Las validaciones de pertenencia al producto, estado activo y no-duplicados se
// hacen en LoteProduccionService (no expresables como anotacion de campo sin conocer el resto del request).
public record DetalleSalidaRequest(
    @NotNull UUID presentacionId,
    @NotNull @Min(1) Integer cantidadObtenida
) {}
