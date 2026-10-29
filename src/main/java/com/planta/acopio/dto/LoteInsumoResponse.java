package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Un lote de insumo con su origen (proveedor, documento) y su estado calculado. */
public record LoteInsumoResponse(
    UUID id,
    UUID insumoId,
    String insumoNombre,
    String unidadMedida,
    String codigo,
    String codigoProveedor,
    LocalDate fechaIngreso,
    LocalDate fechaVencimiento,
    UUID proveedorId,
    String proveedorNombre,
    String proveedorRuc,
    UUID documentoIngresoId,
    String tipoDocumento,
    String numeroDocumento,
    BigDecimal cantidadInicial,
    BigDecimal cantidadDisponible,
    /** VIGENTE, POR_VENCER, VENCIDO o AGOTADO. */
    String estado,
    /** Dias hasta el ultimo dia valido (negativo si ya vencio); null si no tiene vencimiento. */
    Long diasParaVencer
) {}
