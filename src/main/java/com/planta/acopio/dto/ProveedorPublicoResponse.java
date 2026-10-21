package com.planta.acopio.dto;

import java.util.UUID;

// DTO reducido para roles operativos (ACOPIADOR/RECEPCION) y para el
// endpoint de sincronizacion de catalogos (RNF-12): no expone dni ni
// telefono. zonaActualNombre se agrega en la segunda auditoria (§14-D) para
// que el frontend pueda mostrar el nombre de la zona sin necesitar ademas un
// catalogo de Zona propio (que sigue siendo ADMIN-only).
public record ProveedorPublicoResponse(
    UUID id,
    String nombre,
    UUID zonaActualId,
    String zonaActualNombre,
    String codigoQr
) {}
