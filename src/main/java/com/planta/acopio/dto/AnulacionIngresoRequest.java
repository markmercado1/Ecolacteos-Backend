package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Anula un ingreso registrado por error (solo si su lote no se ha usado). El motivo es obligatorio. */
public record AnulacionIngresoRequest(@NotBlank @Size(max = 500) String motivo) {}
