package com.planta.acopio.excel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Un .xlsx ya armado en un archivo temporal. Se lee UNA vez ({@link #abrir()}): al cerrar el flujo el archivo se borra,
 * asi que ni una descarga completa ni una cancelada dejan basura en disco.
 */
public record ArchivoExcel(Path ruta, String nombre, long filas) {

    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    public long tamano() throws IOException {
        return Files.size(ruta);
    }

    /** Abre el archivo para lectura; se elimina al cerrar el flujo. */
    public InputStream abrir() throws IOException {
        return Files.newInputStream(ruta, StandardOpenOption.READ, StandardOpenOption.DELETE_ON_CLOSE);
    }

    /** Descarta el archivo sin haberlo enviado. */
    public void descartar() {
        try {
            Files.deleteIfExists(ruta);
        } catch (IOException ignorada) {
            // el barrido de archivos temporales viejos lo recoge luego
        }
    }
}
