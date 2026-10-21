package com.planta.acopio.dto;

import com.planta.acopio.entity.EstadoReserva;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase 4 (personal): una fila del listado de reservas. `estado` es el estado EFECTIVO (una pendiente pasada de hora o
// una confirmada con la fecha de retiro vencida figuran como VENCIDA).
public record ReservaResumenResponse(
    UUID id,
    String codigo,
    EstadoReserva estado,
    String nombreCliente,
    String telefono,
    LocalDate fechaRetiro,
    LocalDateTime creadoEn,
    LocalDateTime expiraEn,
    int unidades,
    BigDecimal total,
    boolean precioAConfirmar,
    boolean requiereAceptacion,
    boolean precioAceptado,
    // Solo reservas que aun comprometen unidades (pendiente vigente / confirmada): sus lotes vendibles ya no la respaldan.
    boolean sinCobertura
) {}
