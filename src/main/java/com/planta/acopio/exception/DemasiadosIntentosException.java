package com.planta.acopio.exception;

// Bloque 10 (confirmado): demasiados intentos fallidos de login -> HTTP 429.
public class DemasiadosIntentosException extends RuntimeException {
    public DemasiadosIntentosException(String mensaje) {
        super(mensaje);
    }
}
