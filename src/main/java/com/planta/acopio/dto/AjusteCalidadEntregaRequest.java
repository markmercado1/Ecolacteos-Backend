package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

// Fase C: nuevo precio por litro SOLO para la entrega indicada en la URL, dentro de ese pago. Se exige el analisis de calidad que lo
// sustenta (de esa misma entrega) y un motivo (auditoria de disputas). No hay porcentajes ni categorias: el precio lo fija ADMIN.
public record AjusteCalidadEntregaRequest(
    @NotNull @DecimalMin("0.001") @Digits(integer = 3, fraction = 3) BigDecimal precioLitroNuevo,
    @NotNull UUID analisisCalidadId,
    @NotBlank @Size(max = 500) String motivo
) {}
