package com.planta.acopio.importacion;

import com.planta.acopio.excel.ArchivoExcel;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Arma archivos .xlsx para las pruebas de importacion: parte de la plantilla oficial real y le escribe filas. */
public final class ExcelDePrueba {

    private ExcelDePrueba() {
    }

    /** Una celda con formula (nunca debe importarse). */
    public record Formula(String texto) {
    }

    public static byte[] bytes(ArchivoExcel archivo) {
        try (InputStream in = archivo.abrir()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * La plantilla con estas filas de datos desde la fila 2. Cada valor es un String (texto), un Number (numero de Excel,
     * como un DNI sin ceros) o una {@link Formula}; null deja la celda vacia.
     */
    public static byte[] conFilas(byte[] plantilla, List<List<Object>> filas) {
        return modificar(plantilla, libro -> {
            Sheet datos = libro.getSheet(PlantillaExcel.HOJA_DATOS);
            int r = 1;
            for (List<Object> valores : filas) {
                Row fila = datos.createRow(r++);
                for (int c = 0; c < valores.size(); c++) {
                    escribir(fila.createCell(c), valores.get(c));
                }
            }
        });
    }

    public static byte[] conFilas(ArchivoExcel plantilla, List<List<Object>> filas) {
        return conFilas(bytes(plantilla), filas);
    }

    public static void escribir(Cell celda, Object valor) {
        if (valor == null) {
            celda.setBlank();
        } else if (valor instanceof Formula f) {
            celda.setCellFormula(f.texto());
        } else if (valor instanceof Number n) {
            celda.setCellValue(n.doubleValue());
        } else {
            celda.setCellValue(valor.toString());
        }
    }

    /** Abre el libro, aplica un cambio y lo vuelve a serializar. */
    public static byte[] modificar(byte[] origen, Consumer<XSSFWorkbook> cambio) {
        try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(origen)); ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            cambio.accept(libro);
            libro.write(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Copia el zip agregando una entrada (p. ej. un componente prohibido). */
    public static byte[] conEntradaExtra(byte[] xlsx, String nombre, byte[] contenido) {
        try (ByteArrayOutputStream salida = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(salida);
             ZipInputStream entrada = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry e;
            while ((e = entrada.getNextEntry()) != null) {
                zip.putNextEntry(new ZipEntry(e.getName()));
                entrada.transferTo(zip);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(nombre));
            zip.write(contenido);
            zip.closeEntry();
            zip.finish();
            return salida.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Copia el zip reemplazando el contenido de una entrada (por texto). */
    public static byte[] reemplazandoEntrada(byte[] xlsx, String nombre, java.util.function.UnaryOperator<String> cambio) {
        try (ByteArrayOutputStream salida = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(salida);
             ZipInputStream entrada = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry e;
            while ((e = entrada.getNextEntry()) != null) {
                zip.putNextEntry(new ZipEntry(e.getName()));
                byte[] contenido = entrada.readAllBytes();
                if (e.getName().equals(nombre)) {
                    contenido = cambio.apply(new String(contenido, java.nio.charset.StandardCharsets.UTF_8)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                zip.write(contenido);
                zip.closeEntry();
            }
            zip.finish();
            return salida.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Un zip con una sola entrada enorme de ceros: pesa unos KB comprimido y decenas de MB descomprimido. */
    public static byte[] zipBomba(long bytesDescomprimidos) {
        try (ByteArrayOutputStream salida = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(salida)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
            byte[] bloque = new byte[1024 * 1024];
            long restante = bytesDescomprimidos;
            while (restante > 0) {
                int n = (int) Math.min(bloque.length, restante);
                zip.write(bloque, 0, n);
                restante -= n;
            }
            zip.closeEntry();
            zip.finish();
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void borrar(ArchivoExcel archivo) {
        try {
            Files.deleteIfExists(archivo.ruta());
        } catch (IOException ignorada) {
            // temporal
        }
    }
}
