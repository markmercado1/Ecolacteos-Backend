package com.planta.acopio.dto;

import com.planta.acopio.service.DisponibilidadPublica;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// Fase 3: respuesta PUBLICA (sin JWT) del catalogo. Deliberadamente minima: nada de cantidades de stock, lotes,
// fechas de vencimiento, costos ni datos internos -- solo lo que un cliente necesita para elegir y, en la Fase 4,
// separar un producto. La disponibilidad es un estado calculado en el servidor a partir del stock vendible.
public record CatalogoPublicoResponse(List<Producto> productos) {

    public record Producto(
        UUID id,
        String nombre,
        String familia,
        // URL publica de la foto del producto, o null.
        String fotoUrl,
        List<Presentacion> presentaciones
    ) {}

    public record Presentacion(
        UUID id,
        String nombre,
        BigDecimal contenidoCantidad,
        String contenidoUnidad,
        String unidadConteo,
        // Foto de la presentacion o, si no tiene, la del producto; null si ninguna.
        String fotoUrl,
        // Precio PUBLICO vigente hoy (America/Lima); null = "Precio a confirmar" (la presentacion se puede reservar igual,
        // pero la reserva queda pendiente de que el personal defina el precio y el cliente lo acepte).
        BigDecimal precio,
        boolean precioAConfirmar,
        DisponibilidadPublica disponibilidad,
        // Unidades que se pueden reservar, SOLO cuando quedan pocas (POCAS_UNIDADES); null en cualquier otro caso: con mucho stock
        // no se publica la cantidad. Mide la primera fecha de retiro posible; la comprobacion exacta sigue siendo al reservar.
        Integer unidadesDisponibles,
        // Fase G: true solo si el calculo de IGV esta activo y hoy la presentacion se vende GRAVADA con una tasa vigente: el
        // precio publicado "Incluye IGV". false en cualquier otro caso (sin clasificar, exonerada, inafecta o calculo apagado).
        boolean incluyeIgv
    ) {
        public Presentacion(UUID id, String nombre, BigDecimal contenidoCantidad, String contenidoUnidad, String unidadConteo,
                            String fotoUrl, BigDecimal precio, boolean precioAConfirmar, DisponibilidadPublica disponibilidad,
                            Integer unidadesDisponibles) {
            this(id, nombre, contenidoCantidad, contenidoUnidad, unidadConteo, fotoUrl, precio, precioAConfirmar, disponibilidad,
                    unidadesDisponibles, false);
        }
    }
}
