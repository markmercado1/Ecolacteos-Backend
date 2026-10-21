package com.planta.acopio.dto;

import java.util.UUID;

// DTO completo, solo para rol ADMIN (incluye datos personales RNF-12).
public record ProveedorAdminResponse(
    UUID id,
    String nombre,
    String dni,
    String telefono,
    UUID zonaActualId,
    String zonaActualNombre,
    String codigoQr,
    Boolean activo
) {}
