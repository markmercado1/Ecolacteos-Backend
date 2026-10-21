package com.planta.acopio.dto;

// Bloque 3 (confirmado): carga real de archivo (almacenamiento local en
// disco, sin proveedor externo). `url` es relativa a este backend --
// GET {url} devuelve el contenido real, usable directamente como
// RegistroAcopioDTO.fotoUrl.
public record ArchivoResponse(
    String url,
    String nombreArchivo,
    long tamanoBytes
) {}
