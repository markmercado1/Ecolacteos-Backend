package com.planta.acopio.dto;

import jakarta.validation.constraints.*;

// Auditoria CRUD (prioridad ALTA): antes no habia forma de corregir un typo,
// cambiar el rol o resetear la contraseña de un usuario ya creado.
// nuevaPassword es opcional a proposito: si viene null/blank, no se toca el
// password_hash existente -- este DTO nunca reutiliza UsuarioRequest porque
// ese exige password obligatorio (alta), lo cual no aplica a una edicion.
public record UsuarioUpdateRequest(
    @NotBlank String nombre,
    @NotBlank @Email String email,
    @NotBlank String rolNombre,
    @Size(min = 8, message = "debe tener al menos 8 caracteres") String nuevaPassword
) {}
