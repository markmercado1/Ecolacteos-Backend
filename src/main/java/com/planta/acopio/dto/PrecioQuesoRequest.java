package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// Fase 2 (Precios por presentacion): serie historica append-only por presentacion + modalidad -- sin
// editar ni eliminar, un registro nuevo abre una vigencia distinta.
public record PrecioQuesoRequest(
    @NotNull UUID presentacionId,
    @NotBlank String modalidad,          // MAYORISTA | PROVEEDOR | PUBLICO
    @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal precio,
    @NotNull LocalDate vigenteDesde
) {}
