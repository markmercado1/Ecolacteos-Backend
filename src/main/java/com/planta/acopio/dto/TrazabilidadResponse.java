package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Resultado de la busqueda de trazabilidad por codigo de lote. Hacia atras: de donde vino el lote de insumo (proveedor, documento y
 * su recibo). Hacia adelante: en que lotes de produccion se uso. Si la consulta es el id de un lote de produccion, devuelve ademas
 * los lotes de insumo que consumio cada insumo.
 */
public record TrazabilidadResponse(
    String consulta,
    List<LoteInsumoTrazado> lotesInsumo,
    List<LoteProduccionTrazado> lotesProduccion
) {
    public record LoteInsumoTrazado(
        LoteInsumoResponse lote,
        Documento documento,
        List<AdjuntoResponse> adjuntos,
        List<Movimiento> movimientos,
        List<Produccion> producciones
    ) {}

    public record Documento(UUID id, String tipo, String numero, LocalDate fecha, String proveedorNombre, String proveedorRuc,
                            String usuarioNombre, LocalDateTime creadoEn) {}

    public record Movimiento(LocalDateTime fecha, String tipo, BigDecimal cantidad, String usuarioNombre, String motivo, String referencia) {}

    /** Un lote de produccion que uso este lote de insumo, con la cantidad que tomo de el. */
    public record Produccion(UUID loteProduccionId, LocalDate fecha, String productoNombre, BigDecimal cantidad) {}

    public record LoteProduccionTrazado(UUID id, LocalDate fecha, String productoNombre, List<InsumoConsumido> insumos) {}

    public record InsumoConsumido(String insumoNombre, String unidadMedida, List<LoteConsumido> lotes) {}

    public record LoteConsumido(UUID loteId, String codigo, String codigoProveedor, String proveedorNombre, LocalDate fechaVencimiento,
                                BigDecimal cantidad) {}
}
