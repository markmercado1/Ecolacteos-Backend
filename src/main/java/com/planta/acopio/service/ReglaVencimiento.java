package com.planta.acopio.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

// Fase 2: unica fuente de "hoy" y de "esta vencido" para el control de vencimiento. La fecha de vencimiento es
// el ULTIMO DIA VALIDO (inclusive): un lote que vence el 12 todavia se vende el 12 y esta vencido desde el 13.
// "Hoy" se toma del reloj del SERVIDOR en la zona configurada (America/Lima por defecto), nunca de la `fecha`
// que manda el cliente en una venta: el vencimiento depende de cuando sale fisicamente el producto.
@Component
public class ReglaVencimiento {

    private final Clock reloj;

    @Autowired
    public ReglaVencimiento(@Value("${app.vencimiento.zona-horaria:America/Lima}") String zonaHoraria) {
        this(Clock.system(ZoneId.of(zonaHoraria)));
    }

    // Para pruebas con un reloj fijo.
    ReglaVencimiento(Clock reloj) {
        this.reloj = reloj;
    }

    public LocalDate hoy() {
        return LocalDate.now(reloj);
    }

    /** Fecha y hora actuales en la zona de negocio (America/Lima): las reservas guardan y comparan en esta hora. */
    public LocalDateTime ahora() {
        return LocalDateTime.now(reloj);
    }

    public boolean estaVencido(LocalDate fechaVencimiento) {
        return fechaVencimiento.isBefore(hoy());
    }
}
