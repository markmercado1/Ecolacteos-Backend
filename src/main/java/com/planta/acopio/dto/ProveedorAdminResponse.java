package com.planta.acopio.dto;

import java.util.UUID;

// DTO completo, solo para rol ADMIN (incluye datos personales RNF-12).
// Fase G: dni es null en una empresa; tipoPersona, ruc y razonSocial van al final.
public record ProveedorAdminResponse(
    UUID id,
    String nombre,
    String dni,
    String telefono,
    UUID zonaActualId,
    String zonaActualNombre,
    String codigoQr,
    Boolean activo,
    String tipoPersona,
    String ruc,
    String razonSocial
) {}
