package com.planta.acopio.importacion;

import java.util.Map;

/**
 * Una fila de datos del archivo: su numero tal como la ve el usuario en Excel (la fila 1 es el encabezado) y sus celdas por
 * clave de columna (ColumnaImportacion.clave), no por posicion: el orden de las columnas en el archivo no importa.
 */
public record FilaCruda(int numero, Map<String, CeldaLeida> celdas) {

    public CeldaLeida celda(String clave) {
        return celdas.getOrDefault(clave, CeldaLeida.VACIA);
    }

    public boolean vacia() {
        return celdas.values().stream().allMatch(CeldaLeida::vacia);
    }
}
