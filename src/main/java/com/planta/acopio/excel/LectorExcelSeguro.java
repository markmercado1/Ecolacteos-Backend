package com.planta.acopio.excel;

import com.planta.acopio.exception.ValidacionNegocioException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Abre un .xlsx subido por un usuario SIN confiar en el: todo archivo de importacion pasa por aqui antes de que POI lo
 * interprete. Defensas, en orden:
 *
 *  1. Nombre .xlsx (no .xlsm/.xls/.csv...) y tamano maximo.
 *  2. Firma de zip ("PK\u0003\u0004"): el nombre y el Content-Type los controla quien sube, el contenido no miente.
 *  3. Recorrido del zip SIN abrirlo con POI: cantidad de entradas, bytes descomprimidos totales (zip bombs), nombres
 *     sospechosos (rutas con ".."), y componentes que no se aceptan: macros VBA, controles ActiveX, objetos OLE/incrustados,
 *     vinculos externos y personalizaciones de cinta. Un libro .xlsm se rechaza tambien por su tipo de contenido.
 *  4. POI con limites de inflado (ZipSecureFile) y manejo de cualquier fallo como "archivo no valido", sin filtrar
 *     detalles internos al usuario.
 *
 * Las formulas no se evaluan nunca: una celda con formula se informa como error de la fila (ver {@link CeldaLeida}).
 */
@Component
public class LectorExcelSeguro {

    private static final Logger log = LoggerFactory.getLogger(LectorExcelSeguro.class);
    private static final byte[] FIRMA_ZIP = {'P', 'K', 3, 4};
    private static final String[] PROHIBIDOS = {
            "vbaproject", "macrosheets/", "/activex/", "oleobject", "/embeddings/", "externallinks/", "customui", "xl/ctrlprops/"
    };

    static {
        // Limites globales de POI (aplican a todo el proceso): un xlsx legitimo comprime texto ~10:1, no 1000:1.
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(25L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(10L * 1024 * 1024);
    }

    private final LimitesExcel limites;

    public LectorExcelSeguro(LimitesExcel limites) {
        this.limites = limites;
    }

    /** Un libro abierto y su huella; se cierra con try-with-resources. */
    public record LibroSeguro(XSSFWorkbook libro, String sha256, String nombre) implements AutoCloseable {
        @Override
        public void close() {
            try {
                libro.close();
            } catch (IOException ignorada) {
                // nada que recuperar: el libro vive en memoria
            }
        }
    }

    public LibroSeguro abrir(MultipartFile archivo) {
        if (archivo == null || archivo.isEmpty()) {
            throw new ValidacionNegocioException("Elige un archivo de Excel (.xlsx) para importar.");
        }
        String nombre = limpiarNombre(archivo.getOriginalFilename());
        if (!nombre.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new ValidacionNegocioException("Solo se aceptan archivos .xlsx (la plantilla oficial). Si tu archivo es .xls, .xlsm o .csv, "
                    + "ábrelo en Excel y guárdalo como «Libro de Excel (.xlsx)».");
        }
        if (archivo.getSize() > limites.importacionMaxBytes()) {
            throw new ValidacionNegocioException("El archivo pesa " + (archivo.getSize() / 1024) + " KB y el máximo es "
                    + (limites.importacionMaxBytes() / 1024) + " KB. Divide la carga en varios archivos.");
        }
        byte[] bytes;
        try (InputStream in = archivo.getInputStream()) {
            bytes = in.readNBytes((int) limites.importacionMaxBytes() + 1);
        } catch (IOException e) {
            throw new ValidacionNegocioException("No se pudo leer el archivo. Vuelve a seleccionarlo.");
        }
        if (bytes.length > limites.importacionMaxBytes()) {
            throw new ValidacionNegocioException("El archivo supera el tamaño máximo de " + (limites.importacionMaxBytes() / 1024) + " KB.");
        }
        return abrir(bytes, nombre);
    }

    /** Variante sobre bytes ya leidos (las pruebas la usan directamente). */
    public LibroSeguro abrir(byte[] bytes, String nombre) {
        verificarFirma(bytes);
        inspeccionarZip(bytes);
        try {
            XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(bytes));
            return new LibroSeguro(libro, sha256(bytes), nombre);
        } catch (Exception e) {
            // Cualquier fallo de POI (formato, xml danado, limites de inflado...) es "archivo no valido" para el usuario.
            log.warn("Archivo Excel rechazado por POI: {}", e.toString());
            throw archivoNoValido();
        }
    }

    private static void verificarFirma(byte[] bytes) {
        if (bytes.length < FIRMA_ZIP.length) {
            throw archivoNoValido();
        }
        for (int i = 0; i < FIRMA_ZIP.length; i++) {
            if (bytes[i] != FIRMA_ZIP[i]) {
                throw archivoNoValido();
            }
        }
    }

    private void inspeccionarZip(byte[] bytes) {
        long total = 0;
        int entradas = 0;
        boolean contenidoTipos = false;
        boolean libroPrincipal = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entrada;
            byte[] buffer = new byte[8192];
            while ((entrada = zip.getNextEntry()) != null) {
                if (++entradas > limites.importacionMaxEntradasZip()) {
                    throw new ValidacionNegocioException("El archivo tiene demasiados componentes internos y no es una plantilla válida.");
                }
                String ruta = entrada.getName().replace('\\', '/');
                String minuscula = ruta.toLowerCase(Locale.ROOT);
                if (minuscula.contains("../") || minuscula.startsWith("/") || minuscula.contains(":")) {
                    throw archivoNoValido();
                }
                for (String prohibido : PROHIBIDOS) {
                    if (minuscula.contains(prohibido)) {
                        throw new ValidacionNegocioException("El archivo contiene macros, controles, objetos incrustados o vínculos externos, "
                                + "que no se aceptan. Usa la plantilla oficial sin modificar su estructura.");
                    }
                }
                ByteArrayOutputStream contenido = "[Content_Types].xml".equals(ruta) ? new ByteArrayOutputStream() : null;
                int leidos;
                while ((leidos = zip.read(buffer)) > 0) {
                    total += leidos;
                    if (total > limites.importacionMaxBytesDescomprimidos()) {
                        throw new ValidacionNegocioException("El archivo se descomprime a un tamaño anormal y no se puede procesar.");
                    }
                    if (contenido != null && contenido.size() < 1_000_000) {
                        contenido.write(buffer, 0, leidos);
                    }
                }
                if (contenido != null) {
                    contenidoTipos = true;
                    String tipos = contenido.toString(StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    if (tipos.contains("macroenabled") || tipos.contains("vbaproject")) {
                        throw new ValidacionNegocioException("El archivo es un libro con macros (.xlsm). Solo se aceptan archivos .xlsx sin macros.");
                    }
                }
                if ("xl/workbook.xml".equals(ruta)) {
                    libroPrincipal = true;
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Zip de Excel ilegible: {}", e.toString());
            throw archivoNoValido();
        }
        if (!contenidoTipos || !libroPrincipal) {
            throw archivoNoValido();
        }
    }

    private static ValidacionNegocioException archivoNoValido() {
        return new ValidacionNegocioException("El archivo no es un .xlsx válido o está dañado. Descarga la plantilla oficial y vuelve a llenarla.");
    }

    /** Solo el nombre final, sin rutas, y acotado: el nombre lo escribe quien sube el archivo. */
    static String limpiarNombre(String original) {
        if (original == null || original.isBlank()) {
            return "archivo.xlsx";
        }
        String nombre = original.replace('\\', '/');
        nombre = nombre.substring(nombre.lastIndexOf('/') + 1).trim();
        if (nombre.isEmpty()) {
            return "archivo.xlsx";
        }
        return nombre.length() > 150 ? nombre.substring(nombre.length() - 150) : nombre;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
