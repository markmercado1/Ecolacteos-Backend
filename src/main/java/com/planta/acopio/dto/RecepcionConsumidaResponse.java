package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.UUID;

// Refactor de Produccion, fase 2: una fila del desglose FIFO de un lote nuevo -- de que RecepcionPlanta
// puntual salieron cuantos litros. Vacio para lotes creados antes de esta migracion (ver
// LoteProduccionResponse.registroAcopioIds, que sigue siendo su trazabilidad real).
public record RecepcionConsumidaResponse(
    UUID recepcionPlantaId,
    BigDecimal litrosConsumidos
) {}
