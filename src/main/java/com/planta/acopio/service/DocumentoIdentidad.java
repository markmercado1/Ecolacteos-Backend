package com.planta.acopio.service;

import java.util.regex.Pattern;

// Fase G: forma de los documentos de un proveedor. DNI = 8 digitos. RUC = 11 digitos con digito verificador modulo 11
// (pesos 5,4,3,2,7,6,5,4,3,2 sobre los 10 primeros); el de una persona natural con DNI es 10 + DNI + verificador y el de
// una empresa empieza con 20. Solo valida la forma: no consulta a SUNAT si el RUC existe o esta activo.
public final class DocumentoIdentidad {

    private static final Pattern SEPARADORES = Pattern.compile("[\\s\\-.\\u00A0]");
    private static final Pattern OCHO_DIGITOS = Pattern.compile("[0-9]{8}");
    private static final Pattern ONCE_DIGITOS = Pattern.compile("[0-9]{11}");
    private static final int[] PESOS = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};

    public static final String PREFIJO_RUC_NATURAL = "10";
    public static final String PREFIJO_RUC_EMPRESA = "20";

    private DocumentoIdentidad() {}

    /** Sin espacios, guiones ni puntos; null si queda vacio. */
    public static String limpiar(String crudo) {
        if (crudo == null) return null;
        String limpio = SEPARADORES.matcher(crudo).replaceAll("");
        return limpio.isEmpty() ? null : limpio;
    }

    public static boolean dniValido(String dni) {
        return dni != null && OCHO_DIGITOS.matcher(dni).matches();
    }

    /** 11 digitos y digito verificador correcto (no mira el prefijo). */
    public static boolean rucValido(String ruc) {
        if (ruc == null || !ONCE_DIGITOS.matcher(ruc).matches()) return false;
        int suma = 0;
        for (int i = 0; i < 10; i++) {
            suma += (ruc.charAt(i) - '0') * PESOS[i];
        }
        int digito = 11 - suma % 11;
        if (digito == 10) digito = 0;
        if (digito == 11) digito = 1;
        return ruc.charAt(10) - '0' == digito;
    }

    /** El DNI que contiene un RUC de persona natural (10 + DNI + verificador), o null si no es de ese tipo. */
    public static String dniDelRuc(String ruc) {
        if (ruc == null || ruc.length() != 11 || !ruc.startsWith(PREFIJO_RUC_NATURAL)) return null;
        return ruc.substring(2, 10);
    }
}
