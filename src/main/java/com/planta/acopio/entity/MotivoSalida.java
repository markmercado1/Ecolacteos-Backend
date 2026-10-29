package com.planta.acopio.entity;

// VENCIMIENTO, DETERIORO y OTRA valen para insumos y producto terminado; los demas, solo para insumos (V45).
public enum MotivoSalida {
    VENCIMIENTO, DETERIORO, OTRA, MERMA, DERRAME, DEVOLUCION_PROVEEDOR, CONSUMO_MANUAL;

    /** true si el motivo es valido para producto terminado. */
    public boolean esDeProducto() {
        return this == VENCIMIENTO || this == DETERIORO || this == OTRA;
    }
}
