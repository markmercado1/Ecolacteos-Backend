package com.planta.acopio.dto;

import java.util.List;

// Fase H: avisos de datos personales vigentes (publico). `completo` = false mientras falte algun dato del responsable que exige
// el reglamento (lista en `faltantes`); los textos solo incluyen datos configurados, nunca inventados.
public record AvisoPrivacidadResponse(
    String version,
    boolean completo,
    List<String> faltantes,
    String responsable,
    String ruc,
    String domicilio,
    String contacto,
    // Para el formulario publico de reservas (deber de informar; la reserva no requiere consentimiento: Ley 29733 art. 14.5).
    List<String> avisoReserva,
    // Para leer al comprador antes de registrarlo como cliente (finalidad adicional: requiere su autorizacion).
    List<String> avisoRegistro,
    // Pregunta SEPARADA por la autorizacion de comunicaciones comerciales.
    String textoComunicaciones
) {}
