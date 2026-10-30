package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.UUID;

// Resultado del buscador de la pantalla "Nueva venta" (GET /api/ventas/presentaciones): una presentacion vendible con lo que el
// vendedor necesita ver para elegirla. El precio es el vigente para el canal y la fecha indicados; el servidor vuelve a resolverlo
// al registrar la venta. `disponible` = false (con su `motivo`) deja ver el producto pero no permite agregarlo.
public record PresentacionVentaResponse(
    UUID presentacionId,
    UUID productoId,
    String productoNombre,
    String presentacionNombre,
    String codigo,
    int stockVendible,
    BigDecimal precio,        // null = sin precio vigente para ese canal y fecha
    boolean disponible,
    String motivo             // null si esta disponible; SIN_STOCK o SIN_PRECIO
) {}
