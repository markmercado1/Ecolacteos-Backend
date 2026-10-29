package com.planta.acopio.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** `origenId` se absorbe en `destinoId`: el origen queda inactivo y todo su historial pasa al destino. */
public record FusionarInsumosRequest(@NotNull UUID origenId, @NotNull UUID destinoId) {}
