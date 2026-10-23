package com.planta.acopio.importacion;

/**
 * Que paso con una fila del archivo.
 *
 * VALIDA: cumple todo y se creara al confirmar. INVALIDA: tiene errores de formato o de datos. DUPLICADA: ya existe en el sistema
 * o esta repetida en el archivo (la primera version solo CREA, nunca actualiza). OMITIDA: no se proceso (fila en blanco, o una
 * valida que dejo de corresponder entre la vista previa y la confirmacion). CREADA: se creo al confirmar.
 */
public enum EstadoFila {
    VALIDA, INVALIDA, DUPLICADA, OMITIDA, CREADA
}
