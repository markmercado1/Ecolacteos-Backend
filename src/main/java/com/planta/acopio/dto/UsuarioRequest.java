package com.planta.acopio.dto;

import jakarta.validation.constraints.*;

public record UsuarioRequest(
    @NotBlank String nombre,
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8) String password,
    @NotBlank String rolNombre   // ACOPIADOR | CALIDAD | RECEPCION | PRODUCCION | VENTAS | ADMIN
) {}
