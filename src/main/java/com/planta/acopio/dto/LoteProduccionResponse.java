package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record LoteProduccionResponse(
    UUID id,
    LocalDate fecha,
    UUID productoId,
    String productoNombre,
    BigDecimal litrosUsados,
    // Fase 3: una fila por presentacion obtenida. Lista vacia = produccion sin ningun rendimiento
    // aprovechable (consumio leche, no generó salidas) -- distinto de "sin cálculo configurado" de
    // rendimientoPct, que es sobre la FORMULA, no sobre si hubo o no producto obtenido.
    List<DetalleSalidaResponse> detalles,
    // Historico preservado tal cual para lotes anteriores a esta fase; para lotes nuevos, solo se calcula
    // cuando hay exactamente una salida hacia una presentacion sin contenido declarado y el producto tiene
    // rendimiento esperado configurado (ver LoteProduccionService.crear()) -- null en cualquier otro caso
    // ("Sin cálculo configurado", nunca se inventa una equivalencia entre tamaños distintos).
    BigDecimal rendimientoPct,
    BigDecimal rendimientoEsperadoPct,
    // Trazabilidad de lotes creados ANTES del refactor de fase 2 (lote_registro_acopio) -- vacio para
    // lotes nuevos, que usan recepcionesConsumidas en su lugar. No se reinterpreta el historial.
    List<UUID> registroAcopioIds,
    Integer unidadesEsperadas,
    BigDecimal rendimientoEsperadoPctSnapshot,
    // Trazabilidad de lotes creados DESDE el refactor de fase 2 -- desglose FIFO real de RecepcionPlanta.
    // Vacio para lotes anteriores (ver registroAcopioIds).
    List<RecepcionConsumidaResponse> recepcionesConsumidas,
    String motivoRendimiento,
    String revisionRendimiento,
    // Insumos usados en el lote (vacio si no se registro ninguno, y para lotes anteriores a V16). En un lote
    // PLANIFICADA son los insumos PLANIFICADOS (escalados desde la receta), todavia no descontados del stock;
    // desde EN_PRODUCCION son los efectivamente consumidos.
    List<InsumoUsadoResponse> insumos,
    // V18 (recetas). Campos aditivos: la app movil los ignora (ignoreUnknownKeys).
    // PLANIFICADA | EN_PRODUCCION | FINALIZADA | CANCELADA. Lotes de un solo paso: FINALIZADA.
    String estado,
    UUID recetaId,
    String recetaNombre,
    // Salida ESPERADA (snapshot al planificar) en unidades de la presentacion de la receta. Nunca es la
    // cantidad producida: esa es SUM(detalles.cantidadObtenida), registrada solo al finalizar.
    BigDecimal cantidadEsperada,
    UUID presentacionEsperadaId,
    String presentacionEsperadaNombre,
    // Solo FINALIZADA con receta: obtenido en la presentacion de la receta - cantidadEsperada (negativo =
    // se obtuvo menos de lo esperado). null en cualquier otro caso.
    BigDecimal diferencia,
    LocalDateTime iniciadoEn,
    LocalDateTime finalizadoEn,
    LocalDateTime canceladoEn
) {}
