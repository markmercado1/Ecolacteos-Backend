package com.planta.acopio.dto;

import com.planta.acopio.entity.ModalidadPagoProveedor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// Pagos efectivos: un desembolso a un proveedor. `uuidCliente` lo genera quien registra (reenviarlo no crea otro pago). Transferencia y
// deposito exigen banco y numero de operacion. `aplicaciones` es opcional: sin ellas el pago queda sin aplicar (adelanto) y se aplica despues.
public record PagoProveedorRequest(
    @NotBlank @Size(max = 36) String uuidCliente,
    @NotNull UUID proveedorId,
    @NotNull ModalidadPagoProveedor modalidad,
    @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal monto,
    @Size(max = 200) String concepto,
    @Size(max = 80) String banco,
    @Size(max = 80) String nroOperacion,
    @Size(max = 80) String nroRecibo,
    @Size(max = 500) String observacion,
    @Valid @Size(max = 50) List<AplicacionPagoRequest> aplicaciones
) {}
