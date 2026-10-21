package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

// Fase 2 (ampliacion: listado paginado de /admin/precios-queso/presentaciones). Situacion de precio de una
// presentacion para UNA modalidad, ya resuelta por el servidor (nunca "hoy" calculado en el cliente).
// estado: "VIGENTE" (precio y vigenteDesde son los de la tarifa aplicable ahora), "PROGRAMADO" (precio y
// vigenteDesde son los de la proxima tarifa futura, todavia no aplicable) o "SIN_PRECIO" (precio/vigenteDesde
// null: nunca se configuro ninguna tarifa para esa presentacion+modalidad).
public record PrecioModalidadResponse(
    String estado,
    BigDecimal precio,
    LocalDate vigenteDesde
) {}
