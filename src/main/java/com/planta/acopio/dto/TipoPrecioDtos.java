package com.planta.acopio.dto;

import com.planta.acopio.entity.ReglaComprador;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

// Tipos de precio dinamicos (V37, V39). El codigo es el identificador estable; el nombre se edita sin alterar referencias historicas.
public final class TipoPrecioDtos {

    private TipoPrecioDtos() {}

    public record CambioEstadoRequest(@NotNull Boolean activo, @NotBlank String motivo) {}

    /** `codigo` opcional: si falta se deriva del nombre (mayusculas, sin tildes, hasta 15 caracteres). La regla no se cambia despues de crear. */
    public record CrearTipoPrecioRequest(@NotBlank @Size(max = 60) String nombre, @Size(max = 15) String codigo,
                                         @Size(max = 300) String descripcion, @NotNull ReglaComprador reglaComprador, @NotBlank String motivo) {}

    public record EditarTipoPrecioRequest(@NotBlank @Size(max = 60) String nombre, @Size(max = 300) String descripcion, @NotBlank String motivo) {}

    public record UltimoCambioResponse(boolean activo, String motivo, String usuarioNombre, LocalDateTime creadoEn) {}

    public record TipoPrecioResponse(String codigo, String nombre, String descripcion, boolean activo, boolean protegido,
                                     ReglaComprador reglaComprador, UltimoCambioResponse ultimoCambio) {}
}
