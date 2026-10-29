package com.planta.acopio.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** Un archivo adjunto (la referencia; el contenido se descarga aparte). */
public record AdjuntoResponse(
    UUID id,
    String entidadTipo,
    UUID entidadId,
    String nombreOriginal,
    String contentType,
    long tamanoBytes,
    String subidoPorNombre,
    LocalDateTime subidoEn,
    /** Ruta relativa para descargar el contenido (requiere autenticacion). */
    String urlContenido
) {}
