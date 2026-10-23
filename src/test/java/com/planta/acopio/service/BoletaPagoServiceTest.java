package com.planta.acopio.service;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.planta.acopio.entity.AjusteCalidadEntrega;
import com.planta.acopio.entity.AjustePrecioPago;
import com.planta.acopio.entity.AnalisisCalidad;
import com.planta.acopio.entity.LineaPago;
import com.planta.acopio.entity.ResultadoCalidad;
import com.planta.acopio.entity.Pago;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.AjusteCalidadEntregaRepository;
import com.planta.acopio.repository.AjustePrecioPagoRepository;
import com.planta.acopio.repository.LineaPagoRepository;
import com.planta.acopio.repository.PagoRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

// Boleta de pago a proveedor en PDF: comprobante interno (no fiscal). Se lee el texto del PDF generado para comprobar
// que lleva los importes correctos, los ajustes de precio con su motivo y el total a pagar.
@ExtendWith(MockitoExtension.class)
class BoletaPagoServiceTest {

    @Mock private PagoRepository pagoRepository;
    @Mock private AjustePrecioPagoRepository ajustePrecioRepository;
    @Mock private LineaPagoRepository lineaPagoRepository;
    @Mock private AjusteCalidadEntregaRepository ajusteCalidadRepository;

    private BoletaPagoService service;
    private Pago pago;

    @BeforeEach
    void setUp() {
        service = new BoletaPagoService(pagoRepository, ajustePrecioRepository, lineaPagoRepository, ajusteCalidadRepository, "EcoLácteos Huata");
        pago = new Pago();
        pago.setId(UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001"));
        Proveedor proveedor = new Proveedor();
        proveedor.setId(UUID.randomUUID());
        proveedor.setNombre("Alejandro Terrones Huamán");
        pago.setProveedor(proveedor);
        pago.setSemanaInicio(LocalDate.of(2026, 10, 14));
        pago.setSemanaFin(LocalDate.of(2026, 10, 21));
        pago.setLitrosTotales(new BigDecimal("100"));
        pago.setPrecioLitro(new BigDecimal("1.000"));
        pago.setRegistrosLiquidados(Set.of(new RegistroAcopio()));
    }

    @Test
    void generaUnPdfValidoSinAjustesConElTotalOriginal() throws IOException {
        when(pagoRepository.findById(pago.getId())).thenReturn(Optional.of(pago));
        when(ajustePrecioRepository.findByPagoIdOrderByCreadoEnDescIdDesc(pago.getId())).thenReturn(List.of());

        byte[] pdf = service.generarPdf(pago.getId());

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String texto = texto(pdf);
        assertThat(texto).contains("EcoLácteos Huata", "Boleta de pago a proveedor", "A1B2C3D4",
                "Alejandro Terrones Huamán", "14/10/2026 al 21/10/2026", "100 L", "S/ 1.00", "S/ 100.00", "TOTAL A PAGAR");
        assertThat(texto).doesNotContain("Ajustes de precio"); // sin ajustes no hay esa sección
        assertThat(texto).contains("Documento interno", "no tiene valor tributario"); // aclara que no es comprobante fiscal
    }

    @Test
    void conUnAjusteMuestraElPrecioAjustadoSuMotivoYElTotalAPagar() throws IOException {
        when(pagoRepository.findById(pago.getId())).thenReturn(Optional.of(pago));
        AjustePrecioPago ajuste = new AjustePrecioPago();
        ajuste.setPago(pago);
        ajuste.setPrecioLitroAnterior(new BigDecimal("1.000"));
        ajuste.setPrecioLitroNuevo(new BigDecimal("1.200"));
        ajuste.setMotivo("Reconocimiento por calidad");
        Usuario u = new Usuario();
        u.setNombre("Administrador");
        ajuste.setUsuario(u);
        ajuste.setCreadoEn(LocalDateTime.of(2026, 10, 21, 10, 30));
        when(ajustePrecioRepository.findByPagoIdOrderByCreadoEnDescIdDesc(pago.getId())).thenReturn(List.of(ajuste));

        String texto = texto(service.generarPdf(pago.getId()));

        assertThat(texto).contains("Ajustes de precio de este pago", "21/10/2026 10:30", "S/ 1.00 a S/ 1.20",
                "Reconocimiento por calidad", "Administrador");
        assertThat(texto).contains("S/ 100.00");  // total original, se conserva a la vista
        assertThat(texto).contains("S/ 120.00");  // total a pagar con el precio ajustado
        assertThat(texto).contains("100 L × S/ 1.20");
    }

    @Test
    void conVariosAjustesElTotalAPagarUsaElUltimo() throws IOException {
        when(pagoRepository.findById(pago.getId())).thenReturn(Optional.of(pago));
        AjustePrecioPago primero = ajuste("1.000", "1.200", "Primer cambio", LocalDateTime.of(2026, 10, 20, 9, 0));
        AjustePrecioPago segundo = ajuste("1.200", "0.800", "Corrección por análisis", LocalDateTime.of(2026, 10, 21, 9, 0));
        // el repositorio devuelve el mas reciente primero
        when(ajustePrecioRepository.findByPagoIdOrderByCreadoEnDescIdDesc(pago.getId())).thenReturn(List.of(segundo, primero));

        String texto = texto(service.generarPdf(pago.getId()));

        assertThat(texto).contains("Primer cambio", "Corrección por análisis", "S/ 80.00", "100 L × S/ 0.80");
        assertThat(texto.indexOf("Primer cambio")).isLessThan(texto.indexOf("Corrección por análisis")); // cronológico
    }

    @Test
    void rechazaUnPagoInexistente() {
        UUID id = UUID.randomUUID();
        when(pagoRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generarPdf(id)).isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void elCodigoEsElInicioDelIdEnMayusculas() {
        assertThat(BoletaPagoService.codigo(pago.getId())).isEqualTo("A1B2C3D4");
    }

    private AjustePrecioPago ajuste(String anterior, String nuevo, String motivo, LocalDateTime cuando) {
        AjustePrecioPago a = new AjustePrecioPago();
        a.setPago(pago);
        a.setPrecioLitroAnterior(new BigDecimal(anterior));
        a.setPrecioLitroNuevo(new BigDecimal(nuevo));
        a.setMotivo(motivo);
        Usuario u = new Usuario();
        u.setNombre("Administrador");
        a.setUsuario(u);
        a.setCreadoEn(cuando);
        return a;
    }

    private String texto(byte[] pdf) throws IOException {
        PdfReader lector = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(lector);
            StringBuilder sb = new StringBuilder();
            for (int p = 1; p <= lector.getNumberOfPages(); p++) {
                sb.append(extractor.getTextFromPage(p)).append('\n');
            }
            return sb.toString().replaceAll("\\s+", " ");
        } finally {
            lector.close();
        }
    }


    // Fase C: la boleta suma litros x precio de cada entrega (S/ 590.00), lista el precio de calidad con su analisis y motivo, y muestra el desglose.
    @Test
    void conUnPrecioDeCalidadPorEntregaMuestraElAnalisisElMotivoElDesgloseYElTotalReconocido() throws IOException {
        pago.setLitrosTotales(new BigDecimal("600"));
        when(pagoRepository.findById(pago.getId())).thenReturn(Optional.of(pago));
        when(ajustePrecioRepository.findByPagoIdOrderByCreadoEnDescIdDesc(pago.getId())).thenReturn(List.of());

        List<LineaPago> lineas = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            LineaPago l = new LineaPago();
            l.setId(UUID.randomUUID());
            l.setPago(pago);
            RegistroAcopio r = new RegistroAcopio();
            r.setFechaHora(LocalDateTime.of(2026, 10, 15 + i, 6, 0));
            l.setRegistroAcopio(r);
            l.setLitrosLiquidados(new BigDecimal("100"));
            l.setPrecioBase(new BigDecimal("1.000"));
            lineas.add(l);
        }
        when(lineaPagoRepository.findByPagoIdOrderByCreadoEnAscIdAsc(pago.getId())).thenReturn(lineas);
        AnalisisCalidad analisis = new AnalisisCalidad();
        analisis.setId(UUID.randomUUID());
        analisis.setFolioMuestra("F-0042");
        analisis.setResultado(ResultadoCalidad.OBSERVADO);
        AjusteCalidadEntrega calidad = new AjusteCalidadEntrega();
        calidad.setPagoLinea(lineas.get(0));
        calidad.setPrecioAnterior(new BigDecimal("1.000"));
        calidad.setPrecioNuevo(new BigDecimal("0.900"));
        calidad.setAnalisisCalidad(analisis);
        calidad.setMotivo("Agua añadida detectada");
        when(ajusteCalidadRepository.findByPagoLineaPagoIdOrderByCreadoEnDescIdDesc(pago.getId())).thenReturn(List.of(calidad));

        String texto = texto(service.generarPdf(pago.getId()));

        assertThat(texto).contains("Precios de calidad por entrega", "15/10/2026", "100 L", "S/ 1.00 a S/ 0.90",
                "F-0042 (OBSERVADO)", "Agua añadida detectada");
        assertThat(texto).contains("Subtotal a precio del pagoS/ 600.00", "Ajuste por calidad de entregasS/ -10.00");
        assertThat(texto).contains("S/ 590.00");
    }
}
