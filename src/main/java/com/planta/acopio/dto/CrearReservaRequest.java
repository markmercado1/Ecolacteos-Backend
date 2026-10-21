package com.planta.acopio.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Fase 4 (publico, sin JWT): solicitud de reserva. Los limites reales (unidades, pendientes por telefono, plazo, dias de
// atencion) se validan en el SERVIDOR con la configuracion vigente; estas anotaciones solo acotan el tamano de la entrada.
// `uuidCliente` es la clave de idempotencia: reenviar la misma solicitud nunca crea dos reservas.
public record CrearReservaRequest(
    @NotBlank @Size(max = 36) String uuidCliente,
    @NotBlank @Size(max = 80) String nombreCliente,
    // 9 digitos que empiezan con 9, con o sin 51 / +51, con espacios o guiones; se guarda como +519XXXXXXXX.
    @NotBlank @Size(max = 30) String telefono,
    @Email @Size(max = 120) String correo,
    @NotNull LocalDate fechaRetiro,
    @Size(max = 300) String nota,
    @NotEmpty @Size(max = 10, message = "Una reserva admite como máximo 10 líneas") List<@NotNull @Valid Linea> lineas
) {
    public record Linea(
        @NotNull UUID presentacionId,
        @NotNull @Min(value = 1, message = "La cantidad debe ser al menos 1") @Max(100000) Integer cantidad
    ) {}
}
