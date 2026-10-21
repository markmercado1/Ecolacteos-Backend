package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase 2 (docs/ESPEC_JORNADA_TRAZABILIDAD.md): una fila de detalle dentro de JornadaDetalleResponse -- un
// RegistroAcopio individual de la jornada, tal cual pide la ESPEC ("acopios individuales, litros de cada
// acopio"). Deliberadamente liviano (no reusa RegistroAcopioResponse completo): RECEPCION no necesita gps,
// foto ni motivo de observacion para decidir la recepcion, y este endpoint no requiere el permiso amplio de
// RegistroAcopioController (RECEPCION no lee ese controller).
public record AcopioDeJornadaResponse(
    UUID registroAcopioId,
    UUID proveedorId,
    String proveedorNombre,
    LocalDateTime fechaHora,
    BigDecimal litros
) {}
