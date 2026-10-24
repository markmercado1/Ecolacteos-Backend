package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Pagos efectivos: anular conserva la fila; el motivo es obligatorio.
public record AnularPagoProveedorRequest(
    @NotBlank @Size(max = 500) String motivo
) {}
