package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.UUID;

// rendimientoEsperadoPct/cicloCapital dejan de ser obligatorios (Fase 1, Producto+Presentacion): son
// atributos de produccion de queso especificamente, no comerciales -- un producto de otra familia
// (yogurt, leche, mantequilla...) se crea sin ellos, sin inventarle un rendimiento que el negocio no definio.
//
// Los limites (nombre <= 60, rendimiento NUMERIC(5,2) >= 0, ciclo del enum) replican los de la BD para que un
// valor fuera de rango se rechace con 400 y un mensaje claro, en vez de llegar a Postgres y caer en un 500.
public record ProductoRequest(
    @NotBlank @Size(max = 60, message = "El nombre no puede superar los 60 caracteres") String nombre,
    @NotNull UUID familiaId,
    @DecimalMin(value = "0", message = "El rendimiento esperado no puede ser negativo")
    @Digits(integer = 3, fraction = 2, message = "El rendimiento esperado admite hasta 3 enteros y 2 decimales")
    BigDecimal rendimientoEsperadoPct,
    @Pattern(regexp = "RAPIDO|MADURACION", message = "El ciclo de capital debe ser RAPIDO o MADURACION") String cicloCapital,
    @Valid RangoRendimientoDto rangoRendimiento
) {
    public ProductoRequest(String nombre, UUID familiaId, BigDecimal rendimientoEsperadoPct, String cicloCapital) {
        this(nombre, familiaId, rendimientoEsperadoPct, cicloCapital, null);
    }
}
