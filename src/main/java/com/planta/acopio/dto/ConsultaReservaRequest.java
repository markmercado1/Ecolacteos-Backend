package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Fase 4 (publico): el cliente consulta SU reserva con el codigo que recibio y su telefono (POST, para que el telefono
// no viaje en la URL). Si no coinciden la respuesta es la misma 404 que para un codigo inexistente.
public record ConsultaReservaRequest(
    @NotBlank @Size(max = 12) String codigo,
    @NotBlank @Size(max = 30) String telefono
) {}
