package com.planta.acopio.dto;

import com.planta.acopio.entity.TipoDocumentoIngreso;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ingreso de insumos con su documento de respaldo (V44): cabecera (proveedor, tipo, numero, fecha) y lineas, cada una un LOTE
 * (insumo, cantidad, codigo, lote del proveedor, vencimiento). `uuidCliente` (opcional) hace el reenvio idempotente. Sin tipo =
 * SIN_DOCUMENTO; cualquier otro tipo exige numero.
 */
public record IngresoDocumentoRequest(
    UUID uuidCliente,
    UUID proveedorId,
    TipoDocumentoIngreso tipoDocumento,
    @Size(max = 40) String numeroDocumento,
    LocalDate fechaDocumento,
    @Size(max = 500) String observacion,
    @NotEmpty @Valid List<Linea> lineas
) {
    public record Linea(
        @NotNull UUID insumoId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal cantidad,
        /** Vacio = se autogenera (AAA-yyyymmdd-NN); si se indica, debe ser unico para el insumo. */
        @Size(max = 40) String codigoLote,
        @Size(max = 60) String loteProveedor,
        /** Ultimo dia valido (inclusive). Opcional; no puede ser anterior a hoy. */
        LocalDate fechaVencimiento
    ) {}
}
