package com.planta.acopio.service;

// Fase 3: estado de disponibilidad que ve el publico en el catalogo. Se decide UNA sola vez, aqui, a partir del
// stock VENDIBLE (lotes vigentes: sin vencidos ni "sin trazabilidad") -- nunca del stock fisico. El frontend solo
// muestra el estado que recibe; no repite esta regla.
public enum DisponibilidadPublica {
    /** Stock vendible por encima del umbral de "pocas unidades". */
    DISPONIBLE,
    /** Hay stock vendible, pero es bajo (1 hasta el umbral, inclusive). */
    POCAS_UNIDADES,
    /** Sin stock vendible. */
    AGOTADO;

    public static DisponibilidadPublica de(int stockVendible, int umbralPocasUnidades) {
        if (stockVendible <= 0) return AGOTADO;
        return stockVendible <= umbralPocasUnidades ? POCAS_UNIDADES : DISPONIBLE;
    }
}
