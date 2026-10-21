package com.planta.acopio.service;

import java.util.Optional;
import java.util.regex.Pattern;

// Fase 4: telefono movil peruano. Acepta 9 digitos nacionales que empiezan con 9, o el mismo numero con prefijo 51 o
// +51, y espacios, guiones o parentesis como separadores. Siempre se guarda normalizado como +519XXXXXXXX, de modo que
// el limite de reservas pendientes por telefono no se puede eludir cambiando el formato.
public final class TelefonoPeru {

    private static final Pattern SEPARADORES = Pattern.compile("[\\s\\-()\\u00A0]");
    private static final Pattern NACIONAL = Pattern.compile("9[0-9]{8}");

    private TelefonoPeru() {}

    /** El numero normalizado (+519XXXXXXXX), o vacio si no es un movil peruano valido. */
    public static Optional<String> normalizar(String crudo) {
        if (crudo == null) return Optional.empty();
        String limpio = SEPARADORES.matcher(crudo).replaceAll("");
        if (limpio.startsWith("+51")) {
            limpio = limpio.substring(3);
        } else if (limpio.startsWith("51") && limpio.length() == 11) {
            limpio = limpio.substring(2);
        }
        return NACIONAL.matcher(limpio).matches() ? Optional.of("+51" + limpio) : Optional.empty();
    }
}
