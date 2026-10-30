package com.planta.acopio.service;

import java.text.Normalizer;
import java.util.Locale;

// Busqueda de texto en el servidor, unica para todas las pantallas: "contiene" el texto, sin distinguir mayusculas ni
// acentos (huaman encuentra Huaman y Huamán; nino encuentra Niño). Se resuelve en la BD sobre TODO el conjunto de
// registros (no sobre la pagina descargada).
//
// Como se usa en una query nativa:
//   (CAST(:busqueda AS varchar) IS NULL OR PLEGAR_ABRE + "p.nombre" + PLEGAR_CIERRA + COINCIDE)
// y el service pasa BusquedaTexto.patronLike(termino). Los dos lados se pliegan IGUAL: la columna con
// LOWER(TRANSLATE(...)) en SQL y el termino con plegar() aqui, a partir de la misma tabla de letras, sin extensiones de
// PostgreSQL ni migraciones.
//
// Devuelve null si no hay termino, para que la query lo ignore con "CAST(:busqueda AS varchar) IS NULL". Los comodines
// que escriba el usuario (% y _) se escapan con '!' -- las queries declaran ESCAPE '!' -- para que "50%" busque
// literalmente "50%" y no "50 seguido de cualquier cosa".
public final class BusquedaTexto {

    // Letras acentuadas que se pliegan, y su equivalente sin acento (misma posicion). La ñ se pliega a n.
    // Escapes unicode para no depender de la codificacion del archivo fuente.
    static final String CON_ACENTO = "áéíóúüñÁÉÍÓÚÜÑ";
    static final String SIN_ACENTO = "aeiouunaeiouun";

    /** Abre la expresion SQL que pliega una columna: PLEGAR_ABRE + "p.nombre" + PLEGAR_CIERRA. */
    public static final String PLEGAR_ABRE = "LOWER(TRANSLATE(";
    public static final String PLEGAR_CIERRA = ", '" + CON_ACENTO + "', '" + SIN_ACENTO + "'))";
    /** Condicion "ya no hay termino": la query ignora el filtro. */
    public static final String SIN_TERMINO = "CAST(:busqueda AS varchar) IS NULL";
    /** Cola de la comparacion: la columna plegada contiene el patron (ya plegado y con comodines escapados). */
    public static final String COINCIDE = " LIKE CAST(:busqueda AS varchar) ESCAPE '!'";

    private BusquedaTexto() {
    }

    /** Minusculas y sin acentos, con la misma tabla de letras que usa la BD (ver PLEGAR_CIERRA). */
    public static String plegar(String texto) {
        String nfc = Normalizer.normalize(texto, Normalizer.Form.NFC);
        StringBuilder sb = new StringBuilder(nfc.length());
        for (int i = 0; i < nfc.length(); i++) {
            char c = nfc.charAt(i);
            int pos = CON_ACENTO.indexOf(c);
            sb.append(pos >= 0 ? SIN_ACENTO.charAt(pos) : c);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Clave para comparar nombres como "el mismo": sin mayusculas ni acentos, sin espacios sobrantes (al inicio, al final ni
     * repetidos). "Azúcar ", "azucar" y "AZUCAR" tienen la misma clave. Es la comparacion que usan la validacion de insumos
     * duplicados y la importacion de Excel.
     */
    public static String clave(String texto) {
        return texto == null ? "" : plegar(texto.trim().replaceAll("\\s+", " "));
    }

    public static String patronLike(String termino) {
        if (termino == null || termino.isBlank()) {
            return null;
        }
        String escapado = plegar(termino.trim())
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escapado + "%";
    }

    // Variante para queries JPQL: en vez de null devuelve "%" (coincide con
    // cualquier valor no nulo), asi la query no necesita ":busqueda IS NULL"
    // sobre un parametro String nulo, cuyo tipo PostgreSQL no siempre puede
    // inferir. Solo usar contra columnas NOT NULL. (El pliegue de acentos no
    // esta disponible en JPQL: las busquedas que lo necesitan son nativas.)
    public static String patronLikeOTodos(String termino) {
        String patron = patronLike(termino);
        return patron != null ? patron : "%";
    }

    // Buscador con autocompletado (Nueva venta): el termino plegado y escapado, con el comodin donde corresponda.
    //   prefijo  -> el texto EMPIEZA con el termino ("lec" encuentra "Leche", no "Alcalá")
    //   palabra  -> alguna palabra del texto empieza con el termino ("lec" encuentra "Yogurt de leche")
    private static String escapado(String termino) {
        return plegar(termino.trim()).replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    public static String patronPrefijo(String termino) {
        return termino == null || termino.isBlank() ? null : escapado(termino) + "%";
    }

    public static String patronPalabra(String termino) {
        return termino == null || termino.isBlank() ? null : "% " + escapado(termino) + "%";
    }
}
