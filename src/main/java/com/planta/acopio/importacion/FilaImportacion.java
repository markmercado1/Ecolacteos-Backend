package com.planta.acopio.importacion;

import java.util.List;
import java.util.Map;

/** Una fila ya procesada: es lo que se guarda (JSON) en importacion_excel y lo que ve el usuario en la vista previa. */
public record FilaImportacion(int fila, EstadoFila estado, Map<String, String> valores, List<String> mensajes) {

    public FilaImportacion conEstado(EstadoFila nuevo, String mensajeExtra) {
        List<String> nuevos = mensajeExtra == null ? mensajes : java.util.stream.Stream.concat(mensajes.stream(), java.util.stream.Stream.of(mensajeExtra)).toList();
        return new FilaImportacion(fila, nuevo, valores, nuevos);
    }
}
