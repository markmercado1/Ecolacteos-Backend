package com.planta.acopio.dto;

import java.util.UUID;

// password_hash NUNCA se expone (CORE_DESIGN.md §10/§15).
public record UsuarioResponse(
    UUID id,
    String nombre,
    String email,
    String rolNombre,
    Boolean activo
) {}
