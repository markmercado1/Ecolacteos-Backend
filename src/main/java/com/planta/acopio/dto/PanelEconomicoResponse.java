package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// RF-INN-05: combina las salidas de los servicios de innovacion en KPIs
// accionables (CORE_DESIGN.md §2.10.5 / §11).
public record PanelEconomicoResponse(
    UUID zonaId,
    BigDecimal litrosEnRiesgoValorizados,
    PrediccionZonaResponse forecastSemana,
    List<MargenPorCanal> margenPorCanal,
    BigDecimal pctRechazoSiScoreSube10
) {
    public record MargenPorCanal(String tipoCliente, BigDecimal totalVentas) {}
}
