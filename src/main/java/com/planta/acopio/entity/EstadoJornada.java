package com.planta.acopio.entity;

// Estados minimos de una Jornada (docs/ESPEC_JORNADA_TRAZABILIDAD.md §5, Ecolacteos-Frontend). Fase 1 del
// nucleo de jornada solo implementa las transiciones EN_RECOLECCION -> REGRESANDO_A_PLANTA y
// EN_RECOLECCION -> CANCELADA; RECIBIDA existe en el enum (documentado en la especificacion maestra como
// parte del ciclo de vida completo) pero ningun endpoint de esta fase la asigna -- la cierra Recepcion en
// una fase posterior, fuera de alcance aqui.
public enum EstadoJornada {
    EN_RECOLECCION,
    REGRESANDO_A_PLANTA,
    RECIBIDA,
    CANCELADA;

    public boolean esActiva() {
        return this == EN_RECOLECCION || this == REGRESANDO_A_PLANTA;
    }
}
