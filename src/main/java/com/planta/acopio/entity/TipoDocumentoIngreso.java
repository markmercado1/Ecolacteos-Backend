package com.planta.acopio.entity;

/** Tipo de documento de respaldo de un ingreso de insumos (V44). */
public enum TipoDocumentoIngreso {
    GUIA("Guía"), FACTURA("Factura"), BOLETA("Boleta"), OTRO("Documento"), SIN_DOCUMENTO("Sin documento");

    private final String etiqueta;

    TipoDocumentoIngreso(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String etiqueta() {
        return etiqueta;
    }
}
