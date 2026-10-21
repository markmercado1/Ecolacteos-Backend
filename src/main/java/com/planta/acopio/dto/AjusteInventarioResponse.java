package com.planta.acopio.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record AjusteInventarioResponse(
    UUID id,
    UUID presentacionId,
    String presentacionNombre,
    String productoNombre,
    Integer cantidadAnterior,
    Integer cantidadCorregida,
    Integer delta,
    String motivo,
    String usuarioNombre,
    LocalDateTime creadoEn
) {}
