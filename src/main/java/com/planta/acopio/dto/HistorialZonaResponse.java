package com.planta.acopio.dto;

import java.time.LocalDate;
import java.util.UUID;

// Auditoria CRUD (prioridad BAJA): HistorialZonaRepository.findByProveedorIdOrderByDesdeDesc
// ya existia pero no se exponia en ningun endpoint.
public record HistorialZonaResponse(
    UUID id,
    UUID zonaId,
    String zonaNombre,
    LocalDate desde,
    LocalDate hasta
) {}
