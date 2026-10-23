package com.planta.acopio.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// Auditoria CRUD (prioridad BAJA): HistorialZonaRepository.findByProveedorIdOrderByDesdeDesc
// ya existia pero no se exponia en ningun endpoint.
public record HistorialZonaResponse(
    UUID id,
    UUID zonaId,
    String zonaNombre,
    LocalDate desde,
    LocalDate hasta,
    // Fase A (campos aditivos): el periodo exacto, de donde venia, por que y quien; requiereRevision = periodo
    // invertido del codigo anterior (se conserva, pero no decide la zona de ninguna entrega).
    LocalDateTime inicioEn,
    LocalDateTime finEn,
    String zonaOrigenNombre,
    String motivo,
    String usuarioNombre,
    boolean requiereRevision,
    String notaRevision
) {}
