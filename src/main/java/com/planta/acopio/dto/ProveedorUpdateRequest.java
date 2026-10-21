package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;

// Auditoria CRUD (prioridad ALTA): antes solo se podia "actualizar" un
// proveedor reasignando zona (PUT /{id}/zona). zonaActualId no viaja aqui a
// proposito -- ese cambio ya tiene su propio endpoint con la regla de negocio
// de cerrar el HistorialZona abierto (ver ProveedorService.reasignarZona).
public record ProveedorUpdateRequest(
    @NotBlank String nombre,
    @NotBlank String dni,
    String telefono
) {}
