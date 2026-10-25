package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// Fase D: resumen consolidado de un proveedor. Solo LECTURA sobre datos que ya existen (nada se duplica ni se guarda). Cada seccion
// se incluye solo para los roles que pueden verla: `entregas` todos; `calidad` ADMIN y CALIDAD; `pagos` ADMIN y RECEPCION; `zonas`
// ADMIN. Una seccion que el rol no puede ver llega nula, y `secciones` dice cuales llegaron. Sin DNI ni telefono.
public record HistorialResumenResponse(
    UUID proveedorId,
    String proveedorNombre,
    String zonaActualNombre,
    List<String> secciones,
    EntregasResumen entregas,
    CalidadResumen calidad,
    PagosResumen pagos,
    List<HistorialZonaResponse> zonas,
    // Fase H: compras del proveedor como comprador (solo ADMIN); las ventas anonimas no se le atribuyen.
    ClienteDtos.ComprasResumen compras
) {
    public HistorialResumenResponse(UUID proveedorId, String proveedorNombre, String zonaActualNombre, List<String> secciones,
                                    EntregasResumen entregas, CalidadResumen calidad, PagosResumen pagos, List<HistorialZonaResponse> zonas) {
        this(proveedorId, proveedorNombre, zonaActualNombre, secciones, entregas, calidad, pagos, zonas, null);
    }


    /** Lo recibido: los litros originales nunca cambian; los efectivos incluyen la ultima correccion justificada. */
    public record EntregasResumen(
        long total,
        BigDecimal litrosRecibidos,
        BigDecimal litrosEfectivos,
        LocalDateTime primeraEntrega,
        LocalDateTime ultimaEntrega,
        long conCorreccion,
        long sinZona
    ) {}

    /** Resultado del ULTIMO analisis de cada entrega. analizadas + sinAnalisis = entregas.total; aprobadas + observadas + rechazadas = analizadas. */
    public record CalidadResumen(
        long analizadas,
        long sinAnalisis,
        long aprobadas,
        long observadas,
        long rechazadas,
        long alertas,
        long conPrecioDeCalidad
    ) {}

    /**
     * Lo liquidado, pagado y por pagar. totalReconocido = suma de los totalAPagar de sus liquidaciones; totalConAjustes = totalReconocido +
     * ajustesLitros; saldoLiquidaciones = totalConAjustes - pagado; saldoNeto = saldoLiquidaciones - adelantosSinAplicar (negativo = a favor).
     */
    public record PagosResumen(
        long liquidaciones,
        BigDecimal litrosLiquidados,
        BigDecimal totalOriginal,
        BigDecimal totalReconocido,
        BigDecimal ajusteCalidad,
        BigDecimal ajustesLitros,
        BigDecimal totalConAjustes,
        BigDecimal pagado,
        BigDecimal saldoLiquidaciones,
        BigDecimal adelantosSinAplicar,
        BigDecimal saldoNeto,
        long entregasSinLiquidar,
        BigDecimal litrosSinLiquidar
    ) {}
}
