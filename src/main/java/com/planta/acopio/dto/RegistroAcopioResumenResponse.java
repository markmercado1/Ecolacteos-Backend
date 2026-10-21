package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Historial que el acopiador muestra al proveedor en su propio telefono (RF-TRA-01).
// Deliberadamente mas liviano que RegistroAcopioResponse.
public record RegistroAcopioResumenResponse(
    UUID id,
    LocalDateTime fechaHora,
    BigDecimal litros,
    boolean tieneObservacion,
    // Fase 4 PRODUCCION: mismo campo y mismo motivo que RegistroAcopioResponse
    // -- ver el comentario ahi. Este DTO es el que devuelve
    // GET /api/registros-acopio/proveedor/{id}, el otro punto de entrada que
    // PRODUCCION necesita para elegir registros de un proveedor puntual.
    boolean disponibleParaLote
) {}
