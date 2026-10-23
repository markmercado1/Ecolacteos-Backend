package com.planta.acopio.importacion;

import java.util.List;
import java.util.Map;

/** Resultado de validar UNA fila: su estado, los valores (normalizados si es valida, tal cual llegaron si no) y los motivos. */
public record ResultadoFila(EstadoFila estado, Map<String, String> valores, List<String> mensajes) {

    public static ResultadoFila valida(Map<String, String> valoresNormalizados) {
        return new ResultadoFila(EstadoFila.VALIDA, valoresNormalizados, List.of());
    }

    public static ResultadoFila invalida(Map<String, String> valoresOriginales, List<String> motivos) {
        return new ResultadoFila(EstadoFila.INVALIDA, valoresOriginales, List.copyOf(motivos));
    }

    public static ResultadoFila duplicada(Map<String, String> valores, String motivo) {
        return new ResultadoFila(EstadoFila.DUPLICADA, valores, List.of(motivo));
    }
}
