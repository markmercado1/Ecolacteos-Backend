package com.planta.acopio.importacion;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Lo que el servidor entendio del archivo, ANTES de guardar nada. `id` es la clave para confirmar o descargar el reporte.
 * `omitidas` son las filas en blanco. `puedeConfirmar` es false si no hay ninguna fila valida.
 */
public record VistaPreviaImportacionResponse(
        UUID id,
        String tipo,
        String titulo,
        int version,
        String nombreArchivo,
        int totalFilas,
        int validas,
        int invalidas,
        int duplicadas,
        int omitidas,
        LocalDateTime expiraEn,
        boolean puedeConfirmar,
        List<ColumnaImportacionResponse> columnas,
        List<FilaImportacionResponse> filas
) {
}
