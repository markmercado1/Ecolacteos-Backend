package com.planta.acopio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

// Auditoria CRUD (prioridad ALTA): precio_litro no tenia service/controller
// propio pese a ser consultado por PagoService/ForecastingService/PanelEconomicoService.
// Sin editar/eliminar: es una serie historica (append-only), igual que un
// precio nuevo no reemplaza al anterior -- solo abre una vigencia nueva.
public record PrecioLitroRequest(
    @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal precio,
    @NotNull LocalDate vigenteDesde
) {}
