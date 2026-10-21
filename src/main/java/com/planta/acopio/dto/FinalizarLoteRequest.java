package com.planta.acopio.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

// Resultado REAL de un lote EN_PRODUCCION (V18). Mismo shape que las salidas de CrearLoteRequest:
// `detalles` vacia = no se obtuvo nada aprovechable (lote perdido), nunca una linea en cero. La leche e
// insumos realmente utilizados son los consumidos al iniciar (no se editan aqui).
//
// Fase 1 (control de vencimiento): `vencimientos` (opcional, solo web) trae la fecha de vencimiento de cada
// salida. Es OBLIGATORIA para todas las salidas de un producto que controla vencimiento y no se admite para
// uno que no lo controla.
public record FinalizarLoteRequest(
    @NotNull @Valid List<@NotNull DetalleSalidaRequest> detalles,
    @Size(max = 500) String motivoRendimiento,
    @Valid List<@NotNull VencimientoSalidaRequest> vencimientos
) {
    public FinalizarLoteRequest(List<DetalleSalidaRequest> detalles, String motivoRendimiento) {
        this(detalles, motivoRendimiento, null);
    }
}
