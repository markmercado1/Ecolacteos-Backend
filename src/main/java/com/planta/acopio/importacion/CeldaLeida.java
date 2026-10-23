package com.planta.acopio.importacion;

/**
 * Lo que habia en una celda del archivo importado, sin interpretar.
 *
 * @param texto    el valor como texto ya recortado (vacio si la celda esta en blanco). Un numero se entrega en su forma
 *                 plana ("4567890", nunca "4.56789E6") y una fecha en ISO (yyyy-MM-dd).
 * @param numerica true si Excel la guardo como NUMERO (la causa tipica de un DNI sin ceros iniciales).
 * @param formula  true si la celda contiene una formula: nunca se evalua ni se importa su resultado.
 * @param error    true si la celda muestra un error de Excel (#REF!, #DIV/0!...).
 */
public record CeldaLeida(String texto, boolean numerica, boolean formula, boolean error) {

    public static final CeldaLeida VACIA = new CeldaLeida("", false, false, false);

    public boolean vacia() {
        return texto.isEmpty() && !formula && !error;
    }

    /** Hay algo que impide usar el valor tal cual. */
    public boolean problematica() {
        return formula || error;
    }
}
