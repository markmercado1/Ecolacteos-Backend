package com.planta.acopio.exception;

// Regla de negocio violada que no encaja en los casos anteriores
// (ej. fechaHora fuera de tolerancia, registro ya vinculado a otro lote) -> HTTP 400.
public class ValidacionNegocioException extends RuntimeException {
    public ValidacionNegocioException(String mensaje) {
        super(mensaje);
    }
}
