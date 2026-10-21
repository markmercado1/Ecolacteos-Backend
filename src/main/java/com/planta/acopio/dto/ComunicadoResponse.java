package com.planta.acopio.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ComunicadoResponse(
    UUID id,
    String mensaje,
    LocalDateTime fecha,
    List<String> zonasNombres
) {}
