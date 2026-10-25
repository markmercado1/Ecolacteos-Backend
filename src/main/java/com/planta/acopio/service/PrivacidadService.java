package com.planta.acopio.service;

import com.planta.acopio.dto.AvisoPrivacidadResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

// Fase H: avisos de datos personales (Ley 29733 y su reglamento, DS 016-2024-JUS).
//
//  * Reserva y compra: los datos son necesarios para ejecutar la relacion contractual (Ley art. 14.5): no requieren
//    consentimiento, pero si el deber de informar (reglamento art. 6.1).
//  * Registro voluntario como cliente: finalidad adicional a la compra -> consentimiento libre, previo, expreso e informado
//    (arts. 2 a 6), que se puede dar verbalmente en persona (art. 5.1.1) y cuya prueba es del responsable (art. 9).
//  * Comunicaciones comerciales: consentimiento directo y separado (art. 26.1); negarse o revocar, gratis y en no mas de 10
//    dias, sin ser mas complejo que otorgarlo (arts. 10 y 26.5).
//
// Los datos del RESPONSABLE (razon social, RUC, domicilio, canal para ejercer derechos, banco de datos inscrito, destinatarios,
// transferencias y plazos de conservacion) son decisiones del negocio: vienen de configuracion y, mientras falten, el aviso se
// publica con lo que hay y `completo` = false con la lista de `faltantes` (ver docs). No se inventa ningun dato.
@Service
public class PrivacidadService {

    private final String version;
    private final String responsable;
    private final String ruc;
    private final String domicilio;
    private final String contacto;
    private final String bancoDatos;
    private final String destinatarios;
    private final String transferencias;
    private final String plazoReservas;
    private final String plazoClientes;

    public PrivacidadService(@Value("${app.privacidad.version:2026-10-24}") String version,
                             @Value("${app.privacidad.responsable:}") String responsable,
                             @Value("${app.privacidad.ruc:}") String ruc,
                             @Value("${app.privacidad.domicilio:}") String domicilio,
                             @Value("${app.privacidad.contacto:}") String contacto,
                             @Value("${app.privacidad.banco-datos:}") String bancoDatos,
                             @Value("${app.privacidad.destinatarios:}") String destinatarios,
                             @Value("${app.privacidad.transferencias:}") String transferencias,
                             @Value("${app.privacidad.plazo-reservas:}") String plazoReservas,
                             @Value("${app.privacidad.plazo-clientes:}") String plazoClientes) {
        this.version = version.trim();
        this.responsable = limpio(responsable);
        this.ruc = limpio(ruc);
        this.domicilio = limpio(domicilio);
        this.contacto = limpio(contacto);
        this.bancoDatos = limpio(bancoDatos);
        this.destinatarios = limpio(destinatarios);
        this.transferencias = limpio(transferencias);
        this.plazoReservas = limpio(plazoReservas);
        this.plazoClientes = limpio(plazoClientes);
    }

    public String version() {
        return version;
    }

    public boolean completo() {
        return faltantes().isEmpty();
    }

    public List<String> faltantes() {
        List<String> f = new ArrayList<>();
        if (responsable == null) f.add("Razón social del responsable del tratamiento");
        if (ruc == null) f.add("RUC del responsable");
        if (domicilio == null) f.add("Domicilio del responsable");
        if (contacto == null) f.add("Canal para ejercer derechos y revocar autorizaciones (correo o teléfono)");
        if (bancoDatos == null) f.add("Banco de datos inscrito en el Registro Nacional de Protección de Datos Personales");
        if (destinatarios == null) f.add("Destinatarios o encargados que acceden a los datos (por ejemplo, el servicio de alojamiento)");
        if (transferencias == null) f.add("Si hay transferencia nacional o internacional de datos (por ejemplo, servidores fuera del Perú)");
        if (plazoReservas == null) f.add("Plazo de conservación de los datos de las reservas");
        if (plazoClientes == null) f.add("Plazo de conservación de las fichas de clientes");
        return f;
    }

    public AvisoPrivacidadResponse avisos() {
        String canalDerechos = contacto != null ? contacto : "el personal de EcoLácteos";
        List<String> comunes = new ArrayList<>();
        if (responsable != null) {
            comunes.add("Responsable: " + responsable + (ruc != null ? " (RUC " + ruc + ")" : "") + (domicilio != null ? ", " + domicilio : "") + ".");
        }
        if (bancoDatos != null) comunes.add("Los datos se guardan en el banco de datos «" + bancoDatos + "».");
        if (destinatarios != null) comunes.add("Pueden acceder a ellos: " + destinatarios + ".");
        if (transferencias != null) comunes.add(transferencias);
        String derechos = "Puedes pedir información, acceso, rectificación, cancelación u oposición, y revocar tus autorizaciones, de forma gratuita, "
                + "a través de " + canalDerechos + ". Respondemos dentro de los plazos de la Ley 29733 y su reglamento.";

        List<String> reserva = new ArrayList<>();
        reserva.add("Usamos tu nombre, tu teléfono y, si lo das, tu correo y tu nota solo para gestionar esta reserva: confirmarla, avisarte de "
                + "cambios y entregarte el pedido.");
        reserva.add("El nombre y el teléfono son obligatorios para reservar; sin ellos no podemos atender tu reserva. El correo y la nota son opcionales.");
        reserva.add("No usamos estos datos para enviarte publicidad, no creamos una ficha de cliente por reservar y no tomamos decisiones automatizadas sobre ti.");
        reserva.addAll(comunes);
        if (plazoReservas != null) reserva.add("Conservamos los datos de la reserva " + plazoReservas + ".");
        reserva.add(derechos);

        List<String> registro = new ArrayList<>();
        registro.add("Registrarte como cliente es voluntario: si no aceptas, tu compra sigue igual y al mismo precio.");
        registro.add("Si aceptas, guardamos una ficha con tu nombre y tu teléfono (y tu correo, DNI o RUC solo si nos los das) para reconocerte en "
                + "próximas compras y consultar tu historial de compras y reservas. No implica descuentos ni beneficios.");
        registro.add("Registrarte no significa aceptar publicidad: eso se pregunta aparte.");
        registro.addAll(comunes);
        if (plazoClientes != null) registro.add("Conservamos la ficha " + plazoClientes + ".");
        registro.add(derechos);

        String comunicaciones = "Aparte y solo si quieres: ¿autorizas que EcoLácteos te envíe promociones y novedades por los medios que elijas "
                + "(WhatsApp, SMS, llamada o correo)? Es opcional, no cambia tu compra ni tu registro, y puedes retirarla cuando quieras, gratis, "
                + "a través de " + canalDerechos + "; la atendemos en un máximo de 10 días.";

        return new AvisoPrivacidadResponse(version, completo(), faltantes(), responsable, ruc, domicilio, contacto,
                List.copyOf(reserva), List.copyOf(registro), comunicaciones);
    }

    private static String limpio(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }
}
