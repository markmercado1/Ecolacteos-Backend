package com.planta.acopio.dto;

// Auditoria CRUD (prioridad ALTA): antes solo se podia "actualizar" un
// proveedor reasignando zona (PUT /{id}/zona). zonaActualId no viaja aqui a
// proposito -- ese cambio ya tiene su propio endpoint con la regla de negocio
// de cerrar el HistorialZona abierto (ver ProveedorService.reasignarZona).
// Fase G: mismas reglas de documentos que ProveedorRequest. tipoPersona null = conserva el tipo actual.
public record ProveedorUpdateRequest(
    String nombre,
    String dni,
    String telefono,
    String tipoPersona,
    String ruc,
    String razonSocial
) {
    public ProveedorUpdateRequest(String nombre, String dni, String telefono) {
        this(nombre, dni, telefono, null, null, null);
    }
}
