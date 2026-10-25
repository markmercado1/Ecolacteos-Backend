package com.planta.acopio.service;

// Fase H: datos personales que un rol sin acceso completo solo necesita para CONFIRMAR una identidad con el comprador
// ("¿su numero termina en 321?"), nunca para leerlos enteros.
public final class Enmascarar {

    private Enmascarar() {}

    /** +51987654321 -> ••• ••• 321 */
    public static String telefono(String telefono) {
        if (telefono == null || telefono.isBlank()) return null;
        String digitos = telefono.replaceAll("[^0-9]", "");
        if (digitos.length() < 3) return "•••";
        return "••• ••• " + digitos.substring(digitos.length() - 3);
    }

    /** 04567890 -> ••••7890 ; 20601234565 -> •••••••4565 */
    public static String documento(String documento) {
        if (documento == null || documento.isBlank()) return null;
        if (documento.length() <= 4) return "•".repeat(documento.length());
        return "•".repeat(documento.length() - 4) + documento.substring(documento.length() - 4);
    }

    /** rosa.quispe@correo.pe -> r•••@correo.pe */
    public static String correo(String correo) {
        if (correo == null || correo.isBlank()) return null;
        int arroba = correo.indexOf('@');
        if (arroba <= 0) return "•••";
        return correo.charAt(0) + "•••" + correo.substring(arroba);
    }
}
