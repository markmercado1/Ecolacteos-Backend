package com.planta.acopio.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

// RF-RUT-04 (CORE_DESIGN.md §3 / §7): "se administra desde 'definir ruta de
// zona'". Semantica de reemplazo total: cada llamada define la ruta completa
// de la zona (borra el orden anterior y guarda el nuevo), no la modifica
// item por item -- evita dejar huecos u ordenes inconsistentes a medio editar.
public record DefinirRutaZonaRequest(
    @NotEmpty @Valid List<RutaItemRequest> items
) {}
