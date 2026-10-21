package com.planta.acopio.dto;

import java.util.UUID;

public record FamiliaResponse(
    UUID id,
    String nombre,
    Boolean activo
) {}
