package com.planta.acopio.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

// Pagos efectivos: aplicar (parte de) un desembolso ya registrado a una o varias liquidaciones del mismo proveedor.
public record AplicarPagoProveedorRequest(
    @NotEmpty @Size(max = 50) @Valid List<AplicacionPagoRequest> aplicaciones
) {}
