package com.planta.acopio.importacion;

/**
 * Una columna de la plantilla oficial de un tipo de importacion.
 *
 * @param clave       nombre interno estable (nombre, dni, zona...); los valores validados se guardan con esta clave.
 * @param encabezado  titulo que ve el usuario en la fila 1 de la hoja «Datos». Si es obligatoria se escribe con « *» al final
 *                    y el lector lo ignora al comparar.
 * @param obligatoria si la fila no se importa cuando esta vacia.
 * @param texto       true = la columna se formatea como TEXTO en la plantilla (DNI, telefono: Excel no debe quitarle ceros).
 * @param ancho       ancho de la columna en caracteres.
 * @param ayuda       una frase para la hoja de instrucciones (formato esperado).
 * @param ejemplo     un ejemplo para la hoja de instrucciones.
 */
public record ColumnaImportacion(String clave, String encabezado, boolean obligatoria, boolean texto, int ancho, String ayuda, String ejemplo) {

    /** Encabezado tal como se escribe en la plantilla. */
    public String encabezadoEnPlantilla() {
        return obligatoria ? encabezado + " *" : encabezado;
    }
}
