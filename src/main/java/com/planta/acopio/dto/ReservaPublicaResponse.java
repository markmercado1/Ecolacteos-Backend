package com.planta.acopio.dto;

import com.planta.acopio.entity.EstadoReserva;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// Fase 4 (publico): lo que ve el cliente de SU reserva. Sin datos de otros clientes ni notas internas del personal.
// `total` es null mientras alguna linea tenga "Precio a confirmar" (precioAConfirmar = true): nunca se inventa un importe.
public record ReservaPublicaResponse(
    String codigo,
    EstadoReserva estado,
    LocalDate fechaRetiro,
    LocalDate fechaMaximaRetiro,
    LocalDateTime expiraEn,
    boolean precioAConfirmar,
    BigDecimal total,
    List<Linea> lineas
) {
    public record Linea(UUID presentacionId, String producto, String presentacion, String unidadConteo,
                        int cantidad, BigDecimal precioUnitario, BigDecimal subtotal) {}
}
