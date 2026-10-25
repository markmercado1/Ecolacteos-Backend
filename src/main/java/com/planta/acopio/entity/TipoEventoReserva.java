package com.planta.acopio.entity;

public enum TipoEventoReserva {
    CREADA, PRECIOS_RECOTIZADOS, PRECIO_ACEPTADO, CONFIRMADA, RECHAZADA, CANCELADA, RETIRADA,
    // Fase H: el personal confirma (o quita) quien es el comprador de la reserva
    COMPRADOR_VINCULADO, COMPRADOR_DESVINCULADO
}
