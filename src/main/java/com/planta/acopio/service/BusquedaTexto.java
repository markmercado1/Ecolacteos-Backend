package com.planta.acopio.service;

import java.util.Locale;

// Paginacion pendiente: patron LIKE para las busquedas de texto que se
// resuelven en la BD (antes la web filtraba solo la pagina descargada).
// Devuelve null si no hay termino, para que la query lo ignore con
// "CAST(:busqueda AS varchar) IS NULL". Los comodines que escriba el usuario
// (% y _) se escapan con '!' -- las queries declaran ESCAPE '!' -- para que
// "50%" busque literalmente "50%" y no "50 seguido de cualquier cosa".
public final class BusquedaTexto {

    private BusquedaTexto() {
    }

    public static String patronLike(String termino) {
        if (termino == null || termino.isBlank()) {
            return null;
        }
        String escapado = termino.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escapado + "%";
    }

    // Variante para queries JPQL: en vez de null devuelve "%" (coincide con
    // cualquier valor no nulo), asi la query no necesita ":busqueda IS NULL"
    // sobre un parametro String nulo, cuyo tipo PostgreSQL no siempre puede
    // inferir. Solo usar contra columnas NOT NULL.
    public static String patronLikeOTodos(String termino) {
        String patron = patronLike(termino);
        return patron != null ? patron : "%";
    }
}
