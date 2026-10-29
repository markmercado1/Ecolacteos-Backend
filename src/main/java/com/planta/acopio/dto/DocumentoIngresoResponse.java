package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record DocumentoIngresoResponse(
    UUID id,
    UUID proveedorId,
    String proveedorNombre,
    String proveedorRuc,
    String tipoDocumento,
    String numeroDocumento,
    LocalDate fechaDocumento,
    String observacion,
    String usuarioNombre,
    LocalDateTime creadoEn,
    List<Linea> lineas
) {
    public record Linea(
        UUID insumoId,
        String insumoNombre,
        String unidadMedida,
        BigDecimal cantidad,
        UUID loteId,
        String loteCodigo,
        String loteProveedor,
        LocalDate fechaVencimiento,
        UUID movimientoId,
        BigDecimal saldoAnterior,
        BigDecimal saldoResultante
    ) {}
}
