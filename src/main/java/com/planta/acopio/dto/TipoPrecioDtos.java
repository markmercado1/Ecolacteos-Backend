package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

// Tipos de precio administrables (V37). Etapa 1: se pueden activar y desactivar los tres tipos que ya existen.
public final class TipoPrecioDtos {

    private TipoPrecioDtos() {}

    public record CambioEstadoRequest(@NotNull Boolean activo, @NotBlank String motivo) {}

    public record UltimoCambioResponse(boolean activo, String motivo, String usuarioNombre, LocalDateTime creadoEn) {}

    /** `protegido`: no se puede desactivar (lo necesitan el catalogo publico y las reservas). */
    public record TipoPrecioResponse(String codigo, String nombre, String descripcion, boolean activo, boolean protegido,
                                     UltimoCambioResponse ultimoCambio) {}
}
