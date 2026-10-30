package com.planta.acopio.service;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.planta.acopio.dto.ClienteDtos;
import com.planta.acopio.dto.DetalleVentaResponse;
import com.planta.acopio.dto.VentaResumenResponse;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

// Comprobante de venta en PDF: se lee el texto del PDF generado para comprobar numero, tipo, comprador, lineas con su codigo, IGV,
// total y monto en letras; y que NO se imprimen datos personales que la venta no guarda (DNI, telefono).
@ExtendWith(MockitoExtension.class)
class ComprobanteVentaPdfServiceTest {

    @Mock private VentaService ventaService;

    private ComprobanteVentaPdfService service;
    private final UUID id = UUID.fromString("a1b2c3d4-0000-4000-8000-0000000000aa");

    @BeforeEach
    void setUp() {
        PrivacidadService privacidad = new PrivacidadService("2026-10-24", "Comercial EcoLácteos S.A.C.", "20601234567", "Av. Arequipa 1250, Lince",
                "ventas@ecolacteos.pe", "", "", "", "", "");
        service = new ComprobanteVentaPdfService(ventaService, privacidad, "EcoLácteos Huata");
    }

    private static DetalleVentaResponse linea(String producto, String presentacion, String codigo, int cantidad, String precio, String subtotal,
                                              String afectacion, String tasa, String valor, String igv) {
        return new DetalleVentaResponse(UUID.randomUUID(), presentacion, producto, cantidad, new BigDecimal(precio), new BigDecimal(subtotal),
                afectacion, tasa == null ? null : new BigDecimal(tasa), valor == null ? null : new BigDecimal(valor),
                igv == null ? null : new BigDecimal(igv), codigo);
    }

    private VentaResumenResponse venta(String tipo, String comprobante, ClienteDtos.CompradorResumen comprador,
                                       VentaResumenResponse.ResumenIgvVenta igv, List<DetalleVentaResponse> lineas, String total) {
        return new VentaResumenResponse(id, LocalDate.of(2026, 10, 30), "PUBLICO", lineas, new BigDecimal(total), igv, comprador, null, null,
                tipo, comprobante, "Cristian Mamani", LocalDateTime.of(2026, 10, 30, 12, 34, 48));
    }

    private static String texto(byte[] pdf) throws IOException {
        PdfReader lector = new PdfReader(pdf);
        try {
            StringBuilder sb = new StringBuilder();
            PdfTextExtractor extractor = new PdfTextExtractor(lector);
            for (int p = 1; p <= lector.getNumberOfPages(); p++) sb.append(extractor.getTextFromPage(p)).append('\n');
            return sb.toString();
        } finally {
            lector.close();
        }
    }

    @Test
    void laBoletaLlevaNumeroCompradorLineasConCodigoIgvTotalYMontoEnLetras() throws Exception {
        var igv = new VentaResumenResponse.ResumenIgvVenta("DETERMINADO", true, new BigDecimal("31.88"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("5.74"), BigDecimal.ZERO);
        var v = venta("BOLETA", "B001-00000213", new ClienteDtos.CompradorResumen("CLIENTE", UUID.randomUUID(), "Luis Navarro Torres", null), igv,
                List.of(linea("Aceite vegetal", "1 L", "PRD-003", 1, "9.90", "9.90", "GRAVADO", "18.00", "8.39", "1.51"),
                        linea("Agua mineral sin gas", "2.5 L", "PRD-006", 7, "3.96", "27.72", "GRAVADO", "18.00", "23.49", "4.23")),
                "37.62");
        when(ventaService.obtener(id)).thenReturn(v);

        var pdf = service.generarPdf(id);
        String t = texto(pdf.contenido());

        assertThat(pdf.nombreArchivo()).isEqualTo("B001-00000213.pdf");
        assertThat(new String(pdf.contenido(), 0, 4)).isEqualTo("%PDF");
        assertThat(t).contains("BOLETA DE VENTA ELECTRÓNICA").contains("B001-00000213");
        assertThat(t).contains("R.U.C. 20601234567").contains("Av. Arequipa 1250, Lince").contains("COMERCIAL ECOLÁCTEOS S.A.C.");
        assertThat(t).contains("LUIS NAVARRO TORRES").contains("30/10/2026").contains("12:34:48").contains("Cristian Mamani");
        assertThat(t).contains("PRD-003").contains("Aceite vegetal - 1 L").contains("PRD-006").contains("27.72");
        assertThat(t).contains("Los precios unitarios incluyen IGV.").contains("Artículos: 8");
        assertThat(t).contains("OP. GRAVADA").contains("S/ 31.88").contains("IGV (18%)").contains("S/ 5.74");
        assertThat(t).contains("IMPORTE TOTAL").contains("S/ 37.62");
        assertThat(t).contains("SON: TREINTA Y SIETE CON 62/100 SOLES");
        assertThat(t).contains("no tiene validez tributaria");
    }

    @Test
    void laNotaDeVentaSinIgvNoMuestraDesgloseYSinCompradorDiceClienteVarios() throws Exception {
        var igv = new VentaResumenResponse.ResumenIgvVenta("SIN_DETERMINAR", false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("1.80"));
        var v = venta("NOTA_VENTA", "NV01-00000041", null, igv,
                List.of(linea("Agua mineral sin gas", "625 ml", "PRD-005", 1, "1.80", "1.80", null, null, null, null)), "1.80");
        when(ventaService.obtener(id)).thenReturn(v);

        String t = texto(service.generarPdf(id).contenido());

        assertThat(t).contains("NOTA DE VENTA").contains("NV01-00000041").contains("CLIENTE VARIOS");
        assertThat(t).doesNotContain("OP. GRAVADA").doesNotContain("IGV (").doesNotContain("incluyen IGV");
        assertThat(t).contains("SON: UNO CON 80/100 SOLES");
    }

    @Test
    void elRucDelCompradorSeImprimeYNuncaSeImprimenDniNiTelefono() throws Exception {
        var v = venta("NOTA_VENTA", "NV01-00000042", new ClienteDtos.CompradorResumen("CLIENTE", UUID.randomUUID(), "Distribuidora Andina S.A.C.", "20555666777"),
                null, List.of(linea("Queso", "1 kg", "PRD-001", 2, "20.00", "40.00", null, null, null, null)), "40.00");
        when(ventaService.obtener(id)).thenReturn(v);

        String t = texto(service.generarPdf(id).contenido());

        assertThat(t).contains("DISTRIBUIDORA ANDINA S.A.C.").contains("RUC").contains("20555666777");
        assertThat(t).doesNotContain("DNI").doesNotContain("TELÉFONO").doesNotContain("CORREO");
    }

    @Test
    void sinDatosDeLaEmpresaNoSeInventaNingunoYUnaVentaAnteriorSinNumeroUsaElInicioDelId() throws Exception {
        PrivacidadService sinConfig = new PrivacidadService("2026-10-24", "", "", "", "", "", "", "", "", "");
        var sinDatos = new ComprobanteVentaPdfService(ventaService, sinConfig, "EcoLácteos Huata");
        var v = venta(null, null, null, null, List.of(linea("Queso", "1 kg", null, 1, "20.00", "20.00", null, null, null, null)), "20.00");
        when(ventaService.obtener(id)).thenReturn(v);

        var pdf = sinDatos.generarPdf(id);
        String t = texto(pdf.contenido());

        assertThat(pdf.nombreArchivo()).isEqualTo("venta-A1B2C3D4.pdf");
        assertThat(t).contains("EcoLácteos Huata").contains("NOTA DE VENTA").doesNotContain("R.U.C.");
    }

    @Test
    void unaVentaInexistenteSePropagaComoNoEncontrada() {
        when(ventaService.obtener(id)).thenThrow(new RecursoNoEncontradoException("Venta no encontrada: " + id));
        assertThatThrownBy(() -> service.generarPdf(id)).isInstanceOf(RecursoNoEncontradoException.class);
    }
}
