package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// Calculo de una produccion contra el stock actual (V18, recetas). Lo usan tanto la vista previa de una
// receta (GET /api/recetas/{id}/calculo?litros=) como la validacion previa a iniciar un lote planificado
// (GET /api/lotes-produccion/{id}/disponibilidad). Informativo: la autoridad real es el bloqueo/descuento
// atomico al iniciar (LoteProduccionService.iniciar()).
public record DisponibilidadProduccionResponse(
    BigDecimal litrosSolicitados,
    // litrosSolicitados / litrosBase de la receta. null en la validacion de un lote ya planificado (su plan
    // es un snapshot: no se recalcula contra una receta que pudo cambiar despues).
    BigDecimal factor,
    Linea leche,
    List<Linea> insumos,
    // Salida esperada (no producida) en unidades de la presentacion de la receta.
    BigDecimal cantidadEsperada,
    String presentacionNombre,
    String unidadConteo,
    boolean todoDisponible,
    // Solo en la vista previa de una receta: los litros maximos producibles con el stock actual (limitados
    // por la leche o por el insumo mas escaso) y la salida entera esperada con ellos. Nunca se aplican
    // automaticamente: el usuario decide si reduce la cantidad.
    BigDecimal litrosMaximos,
    BigDecimal cantidadEsperadaMaxima
) {
    public record Linea(
        UUID insumoId,          // null para la leche (no es un insumo del catalogo)
        String nombre,
        String unidadMedida,
        BigDecimal requerido,
        BigDecimal disponible,
        BigDecimal faltante,    // 0 si alcanza
        boolean suficiente
    ) {}
}
