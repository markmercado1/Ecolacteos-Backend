package com.planta.acopio.exception;

// Referencia a un catalogo desactivado (ej. motivo_observacion.activo = false,
// zona.activa = false) -> HTTP 422.
public class RecursoInactivoException extends RuntimeException {
    public RecursoInactivoException(String mensaje) {
        super(mensaje);
    }
}
