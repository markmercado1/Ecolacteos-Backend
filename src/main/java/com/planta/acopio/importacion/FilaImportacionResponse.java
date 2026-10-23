package com.planta.acopio.importacion;

import java.util.List;
import java.util.Map;

public record FilaImportacionResponse(int fila, String estado, Map<String, String> valores, List<String> mensajes) {

    static FilaImportacionResponse de(FilaImportacion f) {
        return new FilaImportacionResponse(f.fila(), f.estado().name(), f.valores(), f.mensajes());
    }
}
