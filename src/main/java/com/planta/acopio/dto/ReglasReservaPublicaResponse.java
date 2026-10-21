package com.planta.acopio.dto;

import java.time.LocalDate;
import java.util.List;

// Fase 6 (publico): lo que la web necesita saber de las reglas de reserva SIN reimplementarlas: las fechas de retiro que el
// servidor aceptaria ahora mismo (plazo, dias de atencion, feriados y hora de cierre ya aplicados, hora de Lima), el maximo de
// unidades por reserva y cuantas horas queda retenida una solicitud pendiente. Solo informativo: la validacion autoritativa
// sigue ocurriendo al crear la reserva.
public record ReglasReservaPublicaResponse(
    List<LocalDate> fechasDisponibles,
    int maxUnidades,
    int horasPendiente
) {}
