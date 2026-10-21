package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

// contenidoCantidad/contenidoUnidad son opcionales a proposito: una "Pieza" sin peso ni volumen declarado
// deja ambos en null -- nunca se inventa un valor. Deben venir JUNTOS o ninguno de los dos: esa regla cruzada
// se valida en PresentacionService (son dos campos independientes a nivel de anotacion).
// Los limites replican los de la BD (nombre <= 60, unidadConteo <= 30, NUMERIC(10,3)) para devolver 400 en
// vez de un 500 de Postgres.
public record PresentacionRequest(
    @NotBlank @Size(max = 60, message = "El nombre no puede superar los 60 caracteres") String nombre,
    @DecimalMin(value = "0.0", inclusive = false)
    @Digits(integer = 7, fraction = 3, message = "El contenido admite hasta 7 enteros y 3 decimales")
    BigDecimal contenidoCantidad,
    @Pattern(regexp = "ML|L|G|KG", message = "La unidad de contenido debe ser ML, L, G o KG")
    String contenidoUnidad,        // ML | L | G | KG, o null si contenidoCantidad tambien es null
    @NotBlank @Size(max = 30, message = "La unidad de conteo no puede superar los 30 caracteres")
    String unidadConteo  // "botella", "frasco", "pieza", "envase"... vocabulario abierto
) {}
