package com.planta.acopio.exception;

// Violacion de una unicidad/regla de negocio (ej. doble liquidacion de la
// misma semana, placa duplicada, DNI duplicado) -> HTTP 409.
public class ConflictoNegocioException extends RuntimeException {
    public ConflictoNegocioException(String mensaje) {
        super(mensaje);
    }
}
