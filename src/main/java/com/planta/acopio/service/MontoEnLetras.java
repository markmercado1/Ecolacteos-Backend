package com.planta.acopio.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

// Monto en letras para el comprobante ("SON: TREINTA Y SIETE CON 62/100 SOLES"). Castellano, hasta 999 999 999 999 soles.
// Apocope delante de "mil" y "millones" (VEINTIUN MIL, UN MILLON); CIEN solo cuando es exacto (CIEN, CIENTO UNO).
public final class MontoEnLetras {

    private static final String[] HASTA_29 = {"", "UNO", "DOS", "TRES", "CUATRO", "CINCO", "SEIS", "SIETE", "OCHO", "NUEVE", "DIEZ",
            "ONCE", "DOCE", "TRECE", "CATORCE", "QUINCE", "DIECISEIS", "DIECISIETE", "DIECIOCHO", "DIECINUEVE", "VEINTE",
            "VEINTIUNO", "VEINTIDOS", "VEINTITRES", "VEINTICUATRO", "VEINTICINCO", "VEINTISEIS", "VEINTISIETE", "VEINTIOCHO", "VEINTINUEVE"};
    private static final String[] DECENAS = {"", "", "", "TREINTA", "CUARENTA", "CINCUENTA", "SESENTA", "SETENTA", "OCHENTA", "NOVENTA"};
    private static final String[] CENTENAS = {"", "CIENTO", "DOSCIENTOS", "TRESCIENTOS", "CUATROCIENTOS", "QUINIENTOS", "SEISCIENTOS",
            "SETECIENTOS", "OCHOCIENTOS", "NOVECIENTOS"};

    private static final long LIMITE = 1_000_000_000_000L;

    private MontoEnLetras() {}

    /** "TREINTA Y SIETE CON 62/100 SOLES". El monto se redondea a centimos; no admite negativos. */
    public static String soles(BigDecimal monto) {
        if (monto == null || monto.signum() < 0) {
            throw new IllegalArgumentException("El monto debe ser cero o positivo.");
        }
        BigDecimal redondeado = monto.setScale(2, RoundingMode.HALF_UP);
        long enteros = redondeado.toBigInteger().longValueExact();
        if (enteros >= LIMITE) {
            throw new IllegalArgumentException("Monto demasiado grande para expresarlo en letras: " + monto);
        }
        int centimos = redondeado.remainder(BigDecimal.ONE).movePointRight(2).intValueExact();
        return enLetras(enteros) + String.format(" CON %02d/100 SOLES", centimos);
    }

    static String enLetras(long n) {
        if (n == 0) return "CERO";
        long millones = n / 1_000_000;
        long miles = (n % 1_000_000) / 1000;
        long resto = n % 1000;
        StringBuilder sb = new StringBuilder();
        if (millones > 0) {
            sb.append(millones == 1 ? "UN MILLÓN" : apocope(enLetras(millones)) + " MILLONES");
        }
        if (miles > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(miles == 1 ? "MIL" : apocope(menorDeMil((int) miles)) + " MIL");
        }
        if (resto > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(menorDeMil((int) resto));
        }
        return sb.toString();
    }

    /** 1 a 999. */
    private static String menorDeMil(int n) {
        if (n == 100) return "CIEN";
        StringBuilder sb = new StringBuilder();
        int centenas = n / 100;
        int resto = n % 100;
        if (centenas > 0) sb.append(CENTENAS[centenas]);
        if (resto > 0) {
            if (sb.length() > 0) sb.append(' ');
            if (resto < 30) {
                sb.append(HASTA_29[resto]);
            } else {
                sb.append(DECENAS[resto / 10]);
                if (resto % 10 > 0) sb.append(" Y ").append(HASTA_29[resto % 10]);
            }
        }
        return sb.toString();
    }

    /** UNO -> UN, VEINTIUNO -> VEINTIUN, TREINTA Y UNO -> TREINTA Y UN (delante de "mil" y "millones"). */
    private static String apocope(String texto) {
        return texto.endsWith("UNO") ? texto.substring(0, texto.length() - 1) : texto;
    }
}
