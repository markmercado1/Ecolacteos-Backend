package com.planta.acopio.dto;

import com.planta.acopio.entity.MotivoSalida;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record SalidaInventarioRequest(
        @NotNull UUID uuidCliente,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal cantidad,
        @NotNull MotivoSalida tipo,
        @NotBlank @Size(max = 500) String motivo,
        @Pattern(regexp = "LOTE|REGULARIZACION") String origenLote,
        UUID loteId) {}
