package com.planta.acopio.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Fase 4 (Venta multi-linea / carrito): endpoint nuevo, exclusivo de la web -- el contrato movil
// (VentaRequest, una sola linea) no se toca (ver SyncService.procesarLoteVentas). `detalles` nunca es una
// lista vacia (a diferencia de CrearLoteRequest, una venta sin ninguna linea no tiene sentido de negocio):
// exige al menos una presentacion. Precio se resuelve en el servidor por cada linea (misma autoridad que
// VentaRequest.precioUnitario, que nunca se acepta del cliente).
public record CrearVentaCarritoRequest(
    @NotBlank String uuidCliente,           // idempotencia: reenviar el mismo valor nunca duplica la venta
    @NotNull LocalDate fecha,
    @NotBlank String tipoCliente,            // MAYORISTA | PROVEEDOR | PUBLICO
    @NotEmpty @Valid List<@NotNull DetalleVentaRequest> detalles,
    // Fase 4 (opcional, solo web): la venta es el RETIRO de una reserva confirmada. La venta y el paso de la reserva a
    // RETIRADA ocurren en la misma transaccion (si la venta falla, la reserva sigue confirmada). Exige canal PUBLICO.
    UUID reservaId,
    // Fase H (opcional): quien compra (CLIENTE o PROVEEDOR). Sin comprador = cliente varios (solo en canal PUBLICO).
    @Valid ClienteDtos.CompradorRef comprador,
    // V48 (opcional): NOTA_VENTA (por defecto) o BOLETA. Cada tipo lleva su propia serie y su propio correlativo.
    String tipoComprobante
) {
    public CrearVentaCarritoRequest(String uuidCliente, LocalDate fecha, String tipoCliente, List<DetalleVentaRequest> detalles) {
        this(uuidCliente, fecha, tipoCliente, detalles, null, null, null);
    }

    public CrearVentaCarritoRequest(String uuidCliente, LocalDate fecha, String tipoCliente, List<DetalleVentaRequest> detalles, UUID reservaId) {
        this(uuidCliente, fecha, tipoCliente, detalles, reservaId, null, null);
    }

    public CrearVentaCarritoRequest(String uuidCliente, LocalDate fecha, String tipoCliente, List<DetalleVentaRequest> detalles, UUID reservaId,
                                    ClienteDtos.CompradorRef comprador) {
        this(uuidCliente, fecha, tipoCliente, detalles, reservaId, comprador, null);
    }
}
