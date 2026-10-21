package com.planta.acopio.entity;

// Fase 4. Estados de una reserva. PENDIENTE y CONFIRMADA comprometen unidades; el resto no.
//   PENDIENTE  -> CONFIRMADA | RECHAZADA | CANCELADA | VENCIDA(24 h sin atender)
//   CONFIRMADA -> RETIRADA   | CANCELADA | VENCIDA(paso su fecha de retiro)
// RECHAZADA, CANCELADA, VENCIDA y RETIRADA son finales. VENCIDA no se escribe: se calcula (Reserva.estadoEfectivo).
public enum EstadoReserva {
    PENDIENTE, CONFIRMADA, RECHAZADA, CANCELADA, VENCIDA, RETIRADA
}
