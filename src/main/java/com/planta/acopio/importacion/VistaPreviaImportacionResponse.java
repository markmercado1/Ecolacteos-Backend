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
        List<FilaImportacionResponse> filas,
        // true = con filas invalidas o duplicadas, confirmar exige elegir «importar solo las validas» (el tipo no importa a medias por defecto).
        boolean exigeConfirmarParcial
) {
    /** Compatibilidad: los tipos que importan lo valido y informan el resto no exigen confirmar a medias. */
    public VistaPreviaImportacionResponse(UUID id, String tipo, String titulo, int version, String nombreArchivo, int totalFilas, int validas,
                                          int invalidas, int duplicadas, int omitidas, LocalDateTime expiraEn, boolean puedeConfirmar,
                                          List<ColumnaImportacionResponse> columnas, List<FilaImportacionResponse> filas) {
        this(id, tipo, titulo, version, nombreArchivo, totalFilas, validas, invalidas, duplicadas, omitidas, expiraEn, puedeConfirmar, columnas, filas, false);
    }
}
