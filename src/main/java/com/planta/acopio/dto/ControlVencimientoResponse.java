package com.planta.acopio.dto;

import java.util.List;
import java.util.UUID;

// Fase 1: estado del control de vencimiento de un producto tras activarlo o desactivarlo. Para cada
// presentacion, saldoSinTrazabilidadInicial es el stock que quedo declarado "sin trazabilidad" al activar
// (null si el control esta apagado).
public record ControlVencimientoResponse(
    UUID productoId,
    boolean controlaVencimiento,
    List<PresentacionControl> presentaciones
) {
    public record PresentacionControl(UUID presentacionId, String nombre, Integer saldoSinTrazabilidadInicial) {}
}
