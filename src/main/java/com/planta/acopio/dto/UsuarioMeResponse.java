package com.planta.acopio.dto;

import java.util.List;
import java.util.UUID;

// GET /api/auth/me (bloque 1, confirmado): Usuario NO tiene una relacion
// propia con Zona ni con Unidad en el modelo actual -- no se inventa aqui.
// unidadesResponsable expone la relacion INVERSA que SI existe
// (Unidad.responsable_id -> Usuario), para que el movil no tenga que
// hardcodear unidadId cuando el usuario ya es responsable de una unidad
// conocida. No hay "zonaId" del usuario: la zona vive en Proveedor.zonaActual
// y en Unidad.zona, nunca en Usuario.
public record UsuarioMeResponse(
    UUID id,
    String nombre,
    String email,
    String rolNombre,
    Boolean activo,
    List<UUID> unidadesResponsable
) {}
