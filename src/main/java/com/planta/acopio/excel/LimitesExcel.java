package com.planta.acopio.excel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Limites documentados de exportacion e importacion de Excel (ver docs/EXCEL.md). Todos se pueden ajustar por
// configuracion (app.excel.*) sin tocar codigo; los valores por defecto son conservadores a proposito.
//
// Exportacion: el archivo se arma por lotes en streaming (SXSSF), asi que el tope de filas protege el tiempo de la
// consulta y el tamano de la descarga, no la memoria.
// Importacion: el archivo lo controla un tercero, asi que cada limite es una defensa (tamano, filas, entradas del zip y
// bytes descomprimidos frente a "zip bombs").
@Component
public class LimitesExcel {

    private final int exportacionMaxFilas;
    private final int exportacionLote;
    private final long importacionMaxBytes;
    private final int importacionMaxFilas;
    private final int importacionMaxFilasFisicas;
    private final int importacionMaxEntradasZip;
    private final long importacionMaxBytesDescomprimidos;
    private final int importacionVigenciaMinutos;

    public LimitesExcel(
            @Value("${app.excel.exportacion.max-filas:50000}") int exportacionMaxFilas,
            @Value("${app.excel.exportacion.lote:500}") int exportacionLote,
            @Value("${app.excel.importacion.max-bytes:2097152}") long importacionMaxBytes,
            @Value("${app.excel.importacion.max-filas:2000}") int importacionMaxFilas,
            @Value("${app.excel.importacion.max-filas-fisicas:10000}") int importacionMaxFilasFisicas,
            @Value("${app.excel.importacion.max-entradas-zip:100}") int importacionMaxEntradasZip,
            @Value("${app.excel.importacion.max-bytes-descomprimidos:26214400}") long importacionMaxBytesDescomprimidos,
            @Value("${app.excel.importacion.vigencia-minutos:30}") int importacionVigenciaMinutos) {
        this.exportacionMaxFilas = exportacionMaxFilas;
        this.exportacionLote = exportacionLote;
        this.importacionMaxBytes = importacionMaxBytes;
        this.importacionMaxFilas = importacionMaxFilas;
        this.importacionMaxFilasFisicas = importacionMaxFilasFisicas;
        this.importacionMaxEntradasZip = importacionMaxEntradasZip;
        this.importacionMaxBytesDescomprimidos = importacionMaxBytesDescomprimidos;
        this.importacionVigenciaMinutos = importacionVigenciaMinutos;
    }

    public static LimitesExcel porDefecto() {
        return new LimitesExcel(50_000, 500, 2L * 1024 * 1024, 2_000, 10_000, 100, 25L * 1024 * 1024, 30);
    }

    /** Filas maximas de una exportacion; una consulta con mas filas se rechaza pidiendo filtros. */
    public int exportacionMaxFilas() { return exportacionMaxFilas; }
    /** Filas que se piden a la base de datos por consulta mientras se arma el archivo. */
    public int exportacionLote() { return exportacionLote; }
    /** Tamano maximo del .xlsx que se sube a una importacion. */
    public long importacionMaxBytes() { return importacionMaxBytes; }
    /** Filas de datos (no vacias) maximas por archivo importado. */
    public int importacionMaxFilas() { return importacionMaxFilas; }
    /** Filas fisicas maximas que se recorren en la hoja (aunque esten vacias): evita hojas con formato "infinito". */
    public int importacionMaxFilasFisicas() { return importacionMaxFilasFisicas; }
    /** Entradas maximas dentro del zip del .xlsx. */
    public int importacionMaxEntradasZip() { return importacionMaxEntradasZip; }
    /** Bytes maximos del contenido descomprimido (defensa contra archivos comprimidos anomalos). */
    public long importacionMaxBytesDescomprimidos() { return importacionMaxBytesDescomprimidos; }
    /** Minutos que dura una vista previa antes de exigir cargar el archivo otra vez. */
    public int importacionVigenciaMinutos() { return importacionVigenciaMinutos; }
}
