package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// Fase 4 (Venta multi-linea / carrito): forma usada por el listado (GET /api/ventas), el detalle
// (GET /api/ventas/{id}) y la confirmacion del carrito (POST /api/ventas/carrito) -- todos ellos exclusivos
// de la web (el contrato movil de una sola linea sigue siendo VentaResponse, sin cambios, ver VentaRequest).
public record VentaResumenResponse(
    UUID id,
    LocalDate fecha,
    String tipoCliente,
    List<DetalleVentaResponse> detalles,
    BigDecimal total,
    // Fase G: resumen del IGV contenido en el total (ver ResumenIgvVenta).
    ResumenIgvVenta igv,
    // Fase H: comprador (null = cliente varios; tipo null + nombre = el nombre dado en la reserva, sin ficha) y reserva de origen.
    ClienteDtos.CompradorResumen comprador,
    UUID reservaId,
    String reservaCodigo,
    // V48: comprobante. tipoComprobante = NOTA_VENTA | BOLETA; comprobante = serie y numero ("NV01-00000041").
    String tipoComprobante,
    String comprobante,
    // Quien registro la venta y la hora real del registro (hora local de la planta, sin zona).
    String vendedorNombre,
    LocalDateTime creadoEn
) {

    public VentaResumenResponse(UUID id, LocalDate fecha, String tipoCliente, List<DetalleVentaResponse> detalles, BigDecimal total) {
        this(id, fecha, tipoCliente, detalles, total, null, null, null, null);
    }

    public VentaResumenResponse(UUID id, LocalDate fecha, String tipoCliente, List<DetalleVentaResponse> detalles, BigDecimal total,
                                ResumenIgvVenta igv) {
        this(id, fecha, tipoCliente, detalles, total, igv, null, null, null);
    }

    public VentaResumenResponse(UUID id, LocalDate fecha, String tipoCliente, List<DetalleVentaResponse> detalles, BigDecimal total,
                                ResumenIgvVenta igv, ClienteDtos.CompradorResumen comprador, UUID reservaId, String reservaCodigo) {
        this(id, fecha, tipoCliente, detalles, total, igv, comprador, reservaId, reservaCodigo, null, null, null, null);
    }

    /**
     * estado: SIN_DETERMINAR (ninguna linea tiene foto tributaria: ventas anteriores o calculo apagado), DETERMINADO (todas) o
     * PARCIAL. Los importes suman solo las lineas determinadas; montoSinDeterminar es el resto del total. incluyeIgv = alguna
     * linea gravada (el total "incluye IGV" por igvTotal).
     */
    public record ResumenIgvVenta(
        String estado,
        boolean incluyeIgv,
        BigDecimal operacionesGravadas,
        BigDecimal operacionesExoneradas,
        BigDecimal operacionesInafectas,
        BigDecimal igvTotal,
        BigDecimal montoSinDeterminar
    ) {}
}
