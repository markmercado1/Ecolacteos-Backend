package com.planta.acopio.dto;

import java.time.LocalTime;
import java.util.UUID;

public record RutaProveedorOrdenResponse(
    UUID id,
    UUID proveedorId,
    String proveedorNombre,
    Integer orden,
    LocalTime horaEstimada
) {}
