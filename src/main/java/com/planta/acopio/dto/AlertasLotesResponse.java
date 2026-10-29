package com.planta.acopio.dto;

import java.util.List;

/** Lotes de insumo con existencia que vencen pronto o ya vencieron (para avisar en Inventario). */
public record AlertasLotesResponse(
    /** Dias de anticipacion configurados (app.insumos.dias-alerta-vencimiento, 15 por defecto). */
    int diasAlerta,
    long porVencer,
    long vencidos,
    /** Ordenados por vencimiento (los vencidos primero). */
    List<LoteInsumoResponse> lotes
) {}
