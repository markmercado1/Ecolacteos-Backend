package com.planta.acopio.dto;

import java.time.LocalDateTime;
import java.util.UUID;

// Auditoria CRUD (prioridad MEDIA): ComunicadoConfirmacionRepository.findByComunicadoId
// ya existia pero no se exponia -- ADMIN no podia ver quien confirmo un comunicado.
public record ComunicadoConfirmacionResponse(
    UUID id,
    UUID proveedorId,
    String proveedorNombre,
    UUID acopiadorId,
    String acopiadorNombre,
    LocalDateTime confirmadoEn
) {}
