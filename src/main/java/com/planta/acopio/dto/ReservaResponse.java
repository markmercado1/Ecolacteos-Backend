package com.planta.acopio.dto;

import com.planta.acopio.entity.EstadoReserva;
import com.planta.acopio.entity.TipoEventoReserva;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// Fase 4 (personal): detalle de una reserva con sus lineas, la aceptacion de precio y el historial completo.
// `sinCobertura` = true si hoy las unidades comprometidas ya no pueden respaldarse con lotes vendibles (por ejemplo
// tras un ajuste por perdida fisica): hay que rechazarla o cancelarla.
public record ReservaResponse(
    UUID id,
    String codigo,
    EstadoReserva estado,
    String nombreCliente,
    String telefono,
    String correo,
    String notaCliente,
    LocalDate fechaRetiro,
    LocalDate fechaMaximaRetiro,
    LocalDateTime creadoEn,
    LocalDateTime expiraEn,
    BigDecimal total,
    boolean precioAConfirmar,
    boolean requiereAceptacion,
    Aceptacion aceptacion,
    boolean sinCobertura,
    UUID ventaId,
    List<Linea> lineas,
    List<Evento> eventos
) {
    public record Linea(UUID presentacionId, String producto, String presentacion, String unidadConteo,
                        int cantidad, BigDecimal precioUnitario, BigDecimal subtotal) {}

    /** Constancia de que el cliente acepto el importe. Todos los campos son null si aun no se registro. */
    public record Aceptacion(BigDecimal totalAceptado, LocalDateTime aceptadoEn, String medio, String nota, String registradoPor) {}

    public record Evento(TipoEventoReserva tipo, String usuario, String motivo, LocalDateTime creadoEn) {}
}
