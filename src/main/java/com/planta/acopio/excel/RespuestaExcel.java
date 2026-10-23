package com.planta.acopio.excel;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Respuesta HTTP de una descarga .xlsx: tipo, nombre de archivo, sin cache y sin adivinar el tipo de contenido. */
public final class RespuestaExcel {

    private RespuestaExcel() {
    }

    public static ResponseEntity<Resource> de(ArchivoExcel archivo) {
        try {
            long tamano = archivo.tamano();
            InputStream flujo = archivo.abrir();
            Resource recurso = new InputStreamResource(flujo) {
                @Override
                public long contentLength() {
                    return tamano;
                }

                @Override
                public String getFilename() {
                    return archivo.nombre();
                }
            };
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(archivo.nombre(), StandardCharsets.UTF_8).build().toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.parseMediaType(ArchivoExcel.CONTENT_TYPE))
                    .contentLength(tamano)
                    .body(recurso);
        } catch (IOException e) {
            archivo.descartar();
            throw new UncheckedIOException("No se pudo leer el archivo Excel generado", e);
        }
    }
}
