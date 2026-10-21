package com.planta.acopio.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// Crear/editar receta. Todas las cantidades son "por litrosBase" de leche (la base de la receta):
// rendimientoBase es la cantidad de la presentacion de salida esperada por litrosBase. productoId y
// presentacionId no pueden cambiar al editar (ver RecetaService.actualizar()). `insumos` puede ser vacia
// (receta solo de leche), nunca null.
public record RecetaRequest(
    @NotBlank @Size(max = 100) String nombre,
    @NotNull UUID productoId,
    @NotNull UUID presentacionId,
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 7, fraction = 2) BigDecimal litrosBase,
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3) BigDecimal rendimientoBase,
    @NotNull @Valid List<@NotNull RecetaInsumoRequest> insumos
) {}
