package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// Una fila del kardex (leche, insumo o producto terminado), solo cantidades. entrada/salida son siempre
// positivas o null; saldo es el acumulado real despues de este movimiento (no el de la pagina).
public record MovimientoKardexResponse(
    UUID id,
    LocalDateTime fecha,
    // Tipo del libro de origen: ENTRADA_LECHE_RECEPCION, SALIDA_LECHE_PRODUCCION, SALDO_INICIAL, INGRESO,
    // SALIDA_PRODUCCION, AJUSTE, ENTRADA_PRODUCCION, SALIDA_VENTA.
    String tipoMovimiento,
    BigDecimal entrada,
    BigDecimal salida,
    BigDecimal saldo,
    // RECEPCION | LOTE | VENTA | AJUSTE | INGRESO | SALDO_INICIAL
    String documentoTipo,
    UUID documentoId,
    LocalDate documentoFecha,
    String referencia,
    String usuarioNombre,
    String motivo,
    // V43/V44 (solo kardex de insumos; null en leche y producto terminado): lote al que afecta el movimiento, su proveedor y
    // vencimiento, y el documento de ingreso (tipo y numero) del que vino ese lote.
    UUID loteId,
    String loteCodigo,
    String proveedorNombre,
    LocalDate fechaVencimiento,
    String ingresoDocumentoTipo,
    String ingresoDocumentoNumero
) {
    public MovimientoKardexResponse(UUID id, LocalDateTime fecha, String tipoMovimiento, BigDecimal entrada, BigDecimal salida, BigDecimal saldo,
                                    String documentoTipo, UUID documentoId, LocalDate documentoFecha, String referencia, String usuarioNombre,
                                    String motivo) {
        this(id, fecha, tipoMovimiento, entrada, salida, saldo, documentoTipo, documentoId, documentoFecha, referencia, usuarioNombre, motivo,
                null, null, null, null, null, null);
    }
}
