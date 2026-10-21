package com.planta.acopio.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

// Inventario: accion "Ajustar stock". cantidadFisica es el conteo real (nunca un delta) -- el backend
// calcula la diferencia contra el stock actual del sistema al momento de aplicar el ajuste. motivo es
// obligatorio (a diferencia de CorreccionRegistroRequest): un ajuste de stock siempre debe explicar la
// diferencia encontrada (perdida, rotura, error de conteo...).
public record AjusteInventarioRequest(
    @NotNull UUID presentacionId,
    @NotNull @Min(0) Integer cantidadFisica,
    @NotBlank @Size(max = 500) String motivo
) {}
