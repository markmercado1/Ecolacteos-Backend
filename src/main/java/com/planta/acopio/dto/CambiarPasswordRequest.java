package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Auditoria de bajo riesgo (Fases 1/3/4): self-service, distinto de
// UsuarioUpdateRequest (ADMIN-only, no exige la password actual).
public record CambiarPasswordRequest(
    @NotBlank String passwordActual,
    @NotBlank @Size(min = 8, message = "debe tener al menos 8 caracteres") String passwordNueva
) {}
