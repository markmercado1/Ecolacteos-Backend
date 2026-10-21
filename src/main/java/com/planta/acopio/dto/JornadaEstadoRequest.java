package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

// Transicion de estado de una Jornada YA creada, identificada por su uuidCliente (no por id de servidor --
// el movil nunca necesita resolverlo para esto). Idempotente: reenviar la misma transicion cuando el
// estado actual ya es el destino no falla ni lo vuelve a aplicar, devuelve la jornada tal cual esta.
public record JornadaEstadoRequest(
    @NotBlank String uuidCliente,
    @NotNull TipoTransicionJornada tipo,
    @NotNull LocalDateTime horaEvento,
    BigDecimal gpsLat,
    BigDecimal gpsLng,
    String motivoCancelacion
) {
    public enum TipoTransicionJornada { FINALIZAR, CANCELAR }
}
