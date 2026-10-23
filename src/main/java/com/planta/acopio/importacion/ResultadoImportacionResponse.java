package com.planta.acopio.importacion;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Resultado de confirmar. `yaConfirmada` = true cuando la peticion repite una confirmacion anterior (reintento): no se creo nada
 * nuevo y se devuelve el resultado guardado. `filasNoCreadas` son las invalidas, duplicadas y omitidas, con su motivo.
 */
public record ResultadoImportacionResponse(
        UUID id,
        String tipo,
        String titulo,
        int creadas,
        int invalidas,
        int duplicadas,
        int omitidas,
        boolean yaConfirmada,
        LocalDateTime confirmadoEn,
        List<ColumnaImportacionResponse> columnas,
        List<FilaImportacionResponse> filasNoCreadas
) {
}
