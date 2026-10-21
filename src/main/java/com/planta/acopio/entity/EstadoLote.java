package com.planta.acopio.entity;

// Ciclo de vida de un lote (V18__receta_produccion.sql). Los lotes creados en un solo paso (app movil,
// registro manual web, historicos) nacen FINALIZADA; solo los planificados desde una receta recorren
// PLANIFICADA -> EN_PRODUCCION -> FINALIZADA. CANCELADA solo es alcanzable desde PLANIFICADA: una vez
// iniciada, la leche e insumos ya salieron del stock y el cierre se registra como FINALIZADA (con 0
// unidades obtenidas si se perdio el lote), nunca revirtiendo consumos ya confirmados.
public enum EstadoLote {
    PLANIFICADA, EN_PRODUCCION, FINALIZADA, CANCELADA
}
