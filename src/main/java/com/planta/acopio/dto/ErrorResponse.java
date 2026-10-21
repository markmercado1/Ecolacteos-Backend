package com.planta.acopio.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// Forma unica de cualquier error de la API, producida por GlobalExceptionHandler
// (incluidos los 401/403 generados por SecurityConfig.authenticationEntryPoint
// y por el fallo de un @PreAuthorize). Documentado para Swagger -- reemplaza
// el antiguo Map<String,Object> con un tipo real, sin cambiar el JSON que ya
// devuelve el handler.
@Schema(description = "Forma estandar de cualquier error de la API.")
public record ErrorResponse(

        @Schema(description = "Momento en que ocurrio el error (ISO-8601).", example = "2026-09-03T10:15:30.123Z")
        String timestamp,

        @Schema(description = "Codigo de estado HTTP.", example = "404")
        int status,

        @Schema(description = "Nombre del codigo HTTP.", example = "Not Found")
        String error,

        @Schema(description = "Mensaje legible del error, listo para mostrar o loguear.",
                example = "Proveedor no encontrado: 550e8400-e29b-41d4-a716-446655440000")
        String mensaje
) {}
