package com.planta.acopio.excel;

import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.service.BusquedaTexto;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

/**
 * Exportacion a Excel reutilizable por TODAS las tablas del panel.
 *
 * - Las filas salen de la misma consulta (servicio + filtros + busqueda) que la tabla en pantalla, pero recorriendo
 *   TODAS las paginas en lotes: el archivo trae el conjunto completo del filtro, no las 20 filas visibles.
 * - El libro se arma en streaming (SXSSF, 100 filas en memoria) y se escribe a un archivo temporal ANTES de responder:
 *   si algo falla (p. ej. demasiadas filas) la respuesta es un error JSON normal, nunca un xlsx a medias.
 * - Tope de filas (LimitesExcel.exportacionMaxFilas): una consulta mas grande se rechaza pidiendo filtros.
 * - Las celdas de texto se neutralizan contra inyeccion de formulas (EscritorExcel).
 *
 * El recorrido por paginas es una foto "casi consistente": si alguien crea registros mientras se exporta, pueden
 * correrse las paginas; para reportes de gestion es aceptable y se documenta en docs/EXCEL.md.
 */
@Service
public class ExportacionExcelService {

    private static final Logger log = LoggerFactory.getLogger(ExportacionExcelService.class);
    private static final ZoneId ZONA = ZoneId.of("America/Lima");
    private static final DateTimeFormatter SELLO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final String PREFIJO_TEMPORAL = "ecolacteos-xlsx-";
    private static final Duration ANTIGUEDAD_BASURA = Duration.ofHours(1);

    private final LimitesExcel limites;

    public ExportacionExcelService(LimitesExcel limites) {
        this.limites = limites;
    }

    /** Exporta el resultado completo de una consulta paginada. */
    public <T> ArchivoExcel exportar(String nombreBase, String hoja, List<ColumnaExcel<T>> columnas, FuentePaginada<T> fuente) {
        barrerTemporalesViejos();
        int lote = Math.max(1, limites.exportacionLote());
        int tope = limites.exportacionMaxFilas();

        Page<T> pagina = fuente.pagina(PageRequest.of(0, lote));
        if (pagina.getTotalElements() > tope) {
            throw excedeTope(pagina.getTotalElements(), tope);
        }

        Path temporal = null;
        SXSSFWorkbook libro = new SXSSFWorkbook(100);
        try {
            libro.setCompressTempFiles(true);
            EscritorExcel escritor = new EscritorExcel(libro);
            SXSSFSheet hojaExcel = libro.createSheet(EscritorExcel.nombreHoja(hoja));
            prepararHoja(hojaExcel, escritor, columnas);

            long filas = 0;
            int numeroPagina = 0;
            while (true) {
                for (T item : pagina.getContent()) {
                    if (filas >= tope) {
                        throw excedeTope(filas + 1, tope);
                    }
                    Row fila = hojaExcel.createRow((int) filas + 1);
                    for (int c = 0; c < columnas.size(); c++) {
                        ColumnaExcel<T> columna = columnas.get(c);
                        escritor.escribirValor(fila.createCell(c), columna.valor().apply(item), columna.tipo(), columna.decimales());
                    }
                    filas++;
                }
                if (!pagina.hasNext()) {
                    break;
                }
                numeroPagina++;
                pagina = fuente.pagina(PageRequest.of(numeroPagina, lote));
            }
            if (columnas.size() > 0) {
                hojaExcel.setAutoFilter(new CellRangeAddress(0, 0, 0, columnas.size() - 1));
            }

            temporal = Files.createTempFile(PREFIJO_TEMPORAL, ".xlsx");
            try (OutputStream salida = Files.newOutputStream(temporal)) {
                libro.write(salida);
            }
            String nombre = nombreBase + "-" + LocalDateTime.now(ZONA).format(SELLO) + ".xlsx";
            log.info("Exportacion Excel '{}' por {}: {} filas, {} bytes", nombreBase, usuarioActual(), filas, Files.size(temporal));
            return new ArchivoExcel(temporal, nombre, filas);
        } catch (IOException e) {
            borrar(temporal);
            throw new UncheckedIOException("No se pudo generar el archivo Excel", e);
        } catch (RuntimeException e) {
            borrar(temporal);
            throw e;
        } finally {
            libro.dispose();
            try {
                libro.close();
            } catch (IOException ignorada) {
                // los temporales de POI se limpian con dispose()
            }
        }
    }

    /**
     * Exporta una lista que el backend entrega completa (catalogos, unidades...). Si hay {@code busqueda}, conserva solo
     * las filas cuyo texto (en cualquier columna exportada) la contiene, sin distinguir mayusculas ni acentos: el
     * mismo criterio de "contiene" de la busqueda de las tablas.
     */
    public <T> ArchivoExcel exportarLista(String nombreBase, String hoja, List<ColumnaExcel<T>> columnas, List<T> filas, String busqueda) {
        List<T> filtradas = filtrar(filas, columnas, busqueda);
        return exportar(nombreBase, hoja, columnas, pageable -> {
            int desde = (int) Math.min(pageable.getOffset(), filtradas.size());
            int hasta = Math.min(desde + pageable.getPageSize(), filtradas.size());
            return new PageImpl<>(filtradas.subList(desde, hasta), pageable, filtradas.size());
        });
    }

    static <T> List<T> filtrar(List<T> filas, List<ColumnaExcel<T>> columnas, String busqueda) {
        if (busqueda == null || busqueda.isBlank()) {
            return filas;
        }
        String termino = BusquedaTexto.plegar(busqueda.trim());
        return filas.stream().filter(fila -> columnas.stream().anyMatch(columna -> {
            Object valor = columna.valor().apply(fila);
            return valor != null && BusquedaTexto.plegar(valor.toString()).contains(termino);
        })).toList();
    }

    private <T> void prepararHoja(SXSSFSheet hoja, EscritorExcel escritor, List<ColumnaExcel<T>> columnas) {
        String[] titulos = columnas.stream().map(ColumnaExcel::titulo).toArray(String[]::new);
        escritor.escribirEncabezado(hoja.createRow(0), titulos);
        for (int c = 0; c < columnas.size(); c++) {
            hoja.setColumnWidth(c, Math.min(255, Math.max(8, columnas.get(c).ancho())) * 256);
        }
        hoja.createFreezePane(0, 1);
    }

    private static ValidacionNegocioException excedeTope(long total, int tope) {
        return new ValidacionNegocioException("La exportación tiene " + total + " filas y el máximo permitido es " + tope
                + ". Acota la búsqueda o los filtros (por ejemplo, una fecha o una zona) y vuelve a exportar.");
    }

    private static String usuarioActual() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        return autenticacion != null ? autenticacion.getName() : "desconocido";
    }

    private static void borrar(Path ruta) {
        if (ruta != null) {
            try {
                Files.deleteIfExists(ruta);
            } catch (IOException ignorada) {
                // lo recoge el barrido
            }
        }
    }

    /** Borra los xlsx temporales que quedaron de descargas que nunca se leyeron (cierre abrupto, error al responder). */
    private void barrerTemporalesViejos() {
        Path directorio = Path.of(System.getProperty("java.io.tmpdir"));
        Instant limite = Instant.now().minus(ANTIGUEDAD_BASURA);
        try (Stream<Path> archivos = Files.list(directorio)) {
            archivos.filter(p -> p.getFileName().toString().startsWith(PREFIJO_TEMPORAL))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant().isBefore(limite);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(ExportacionExcelService::borrar);
        } catch (IOException | RuntimeException ignorada) {
            // el barrido es un extra: nunca debe impedir una exportacion
        }
    }
}
