package com.planta.acopio.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.planta.acopio.entity.AjusteCalidadEntrega;
import com.planta.acopio.entity.AjustePrecioPago;
import com.planta.acopio.entity.LineaPago;
import com.planta.acopio.entity.Pago;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.AjusteCalidadEntregaRepository;
import com.planta.acopio.repository.AjustePrecioPagoRepository;
import com.planta.acopio.repository.LineaPagoRepository;
import com.planta.acopio.repository.PagoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

// Boleta de pago a proveedor (liquidacion semanal) en PDF. Es un comprobante INTERNO de pago: no es una boleta ni una
// factura electronica (no tiene serie/RUC/envio a SUNAT) y asi lo dice el propio documento. Reune lo que el sistema
// ya sabe: litros, precio original, cada ajuste de precio de este pago (con motivo y usuario) y el total a pagar.
// El "codigo" es el inicio del id del pago (no hay correlativo fiscal que inventar).
@Service
public class BoletaPagoService {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final Color VERDE = new Color(0x24, 0x5C, 0x43);
    private static final Color SALVIA = new Color(0xEA, 0xF2, 0xE8);
    private static final Color LINEA = new Color(0xE2, 0xDF, 0xD1);
    private static final Color TINTA = new Color(0x29, 0x33, 0x2D);

    private final PagoRepository pagoRepository;
    private final AjustePrecioPagoRepository ajustePrecioRepository;
    private final LineaPagoRepository lineaPagoRepository;
    private final AjusteCalidadEntregaRepository ajusteCalidadRepository;
    private final String empresa;

    public BoletaPagoService(PagoRepository pagoRepository, AjustePrecioPagoRepository ajustePrecioRepository,
                              LineaPagoRepository lineaPagoRepository, AjusteCalidadEntregaRepository ajusteCalidadRepository,
                              @Value("${app.empresa.nombre:EcoLácteos Huata}") String empresa) {
        this.pagoRepository = pagoRepository;
        this.ajustePrecioRepository = ajustePrecioRepository;
        this.lineaPagoRepository = lineaPagoRepository;
        this.ajusteCalidadRepository = ajusteCalidadRepository;
        this.empresa = empresa;
    }

    /** Codigo corto y estable del pago para citarlo en la boleta y en el nombre del archivo. */
    public static String codigo(UUID pagoId) {
        return pagoId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    @Transactional(readOnly = true)
    public byte[] generarPdf(UUID pagoId) {
        Pago pago = pagoRepository.findById(pagoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Pago no encontrado: " + pagoId));
        // Cronologico: del primer cambio de precio al ultimo.
        List<AjustePrecioPago> ajustes = new ArrayList<>(ajustePrecioRepository.findByPagoIdOrderByCreadoEnDescIdDesc(pagoId));
        Collections.reverse(ajustes);

        BigDecimal precioEfectivo = ajustes.isEmpty() ? pago.getPrecioLitro() : ajustes.get(ajustes.size() - 1).getPrecioLitroNuevo();
        BigDecimal totalOriginal = PagoService.calcularTotal(pago.getLitrosTotales(), pago.getPrecioLitro());
        BigDecimal totalSubtotal = PagoService.calcularTotal(pago.getLitrosTotales(), precioEfectivo);

        // Fase C: precios de calidad de entregas concretas (cronologico). El total a pagar suma litros x precio de cada entrega y se
        // redondea una sola vez; una entrega con precio propio manda sobre el ajuste de precio del pago completo.
        List<LineaPago> lineas = lineaPagoRepository.findByPagoIdOrderByCreadoEnAscIdAsc(pagoId);
        List<AjusteCalidadEntrega> calidad = lineas.isEmpty() ? new ArrayList<>()
                : new ArrayList<>(ajusteCalidadRepository.findByPagoLineaPagoIdOrderByCreadoEnDescIdDesc(pagoId));
        Collections.reverse(calidad);
        BigDecimal totalAPagar = totalSubtotal;
        if (!calidad.isEmpty()) {
            Map<UUID, BigDecimal> propios = new HashMap<>();
            for (AjusteCalidadEntrega a : calidad) {
                propios.put(a.getPagoLinea().getId(), a.getPrecioNuevo());
            }
            Map<UUID, BigDecimal> precios = new HashMap<>();
            for (LineaPago l : lineas) {
                precios.put(l.getId(), propios.getOrDefault(l.getId(), precioEfectivo));
            }
            totalAPagar = PrecioAplicado.totalReconocido(lineas, precios);
        }

        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 48, 48, 48, 48);
        try {
            PdfWriter.getInstance(doc, salida);
            doc.addTitle("Boleta de pago " + codigo(pagoId));
            doc.addAuthor(empresa);
            doc.open();

            Font titulo = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, VERDE);
            Font subtitulo = FontFactory.getFont(FontFactory.HELVETICA, 10, TINTA);
            Font seccion = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, VERDE);
            Font normal = FontFactory.getFont(FontFactory.HELVETICA, 10, TINTA);
            Font negrita = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, TINTA);
            Font total = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, VERDE);
            Font pie = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, Color.GRAY);

            Paragraph cabecera = new Paragraph(empresa, titulo);
            doc.add(cabecera);
            doc.add(new Paragraph("Boleta de pago a proveedor", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, TINTA)));
            doc.add(new Paragraph("Código " + codigo(pagoId) + "  ·  Emitida el "
                    + LocalDateTime.now(LIMA).format(FECHA_HORA), subtitulo));
            doc.add(espacio(10));

            // --- Datos del pago ---
            PdfPTable datos = tabla(new float[]{1.2f, 2f});
            // Fase G: una empresa se identifica con su razon social y RUC; una persona natural con su DNI y, si lo tiene, su RUC.
            Proveedor proveedor = pago.getProveedor();
            fila(datos, "Proveedor", proveedor.getNombre(), normal, negrita);
            if (presente(proveedor.getRazonSocial()) && !proveedor.getRazonSocial().equals(proveedor.getNombre())) {
                fila(datos, "Razón social", proveedor.getRazonSocial(), normal, negrita);
            }
            if (presente(proveedor.getDni())) {
                fila(datos, "DNI", proveedor.getDni(), normal, negrita);
            }
            if (presente(proveedor.getRuc())) {
                fila(datos, "RUC", proveedor.getRuc(), normal, negrita);
            }
            fila(datos, "Semana", fecha(pago.getSemanaInicio()) + " al " + fecha(pago.getSemanaFin()), normal, negrita);
            fila(datos, "Registros de acopio", String.valueOf(pago.getRegistrosLiquidados().size()), normal, negrita);
            fila(datos, "Litros liquidados", litros(pago.getLitrosTotales()), normal, negrita);
            doc.add(datos);
            doc.add(espacio(12));

            // --- Precio y total originales ---
            doc.add(new Paragraph("Liquidación original", seccion));
            PdfPTable original = tabla(new float[]{1.2f, 2f});
            fila(original, "Precio por litro", precio(pago.getPrecioLitro()), normal, negrita);
            fila(original, "Total", soles(totalOriginal, 2), normal, negrita);
            doc.add(original);

            // --- Ajustes de precio (solo si los hay) ---
            if (!ajustes.isEmpty()) {
                doc.add(espacio(12));
                doc.add(new Paragraph("Ajustes de precio de este pago", seccion));
                PdfPTable t = new PdfPTable(new float[]{1.6f, 1.7f, 3f, 1.4f});
                t.setWidthPercentage(100);
                t.setSpacingBefore(4);
                for (String h : new String[]{"Fecha", "Precio por litro", "Motivo", "Registrado por"}) {
                    encabezado(t, h);
                }
                for (AjustePrecioPago a : ajustes) {
                    celda(t, a.getCreadoEn().format(FECHA_HORA), normal);
                    celda(t, precio(a.getPrecioLitroAnterior()) + " a " + precio(a.getPrecioLitroNuevo()), normal);
                    celda(t, a.getMotivo(), normal);
                    celda(t, a.getUsuario().getNombre(), normal);
                }
                doc.add(t);
            }

            // --- Precios de calidad por entrega (solo si los hay) ---
            if (!calidad.isEmpty()) {
                doc.add(espacio(12));
                doc.add(new Paragraph("Precios de calidad por entrega", seccion));
                PdfPTable t = new PdfPTable(new float[]{1.3f, 1.1f, 1.7f, 1.8f, 2.6f});
                t.setWidthPercentage(100);
                t.setSpacingBefore(4);
                for (String h : new String[]{"Entrega", "Litros", "Precio por litro", "Análisis de calidad", "Motivo"}) {
                    encabezado(t, h);
                }
                for (AjusteCalidadEntrega a : calidad) {
                    celda(t, a.getPagoLinea().getRegistroAcopio().getFechaHora().format(FECHA), normal);
                    celda(t, litros(a.getPagoLinea().getLitrosLiquidados()), normal);
                    celda(t, precio(a.getPrecioAnterior()) + " a " + precio(a.getPrecioNuevo()), normal);
                    celda(t, a.getAnalisisCalidad().getFolioMuestra()
                            + (a.getAnalisisCalidad().getResultado() != null ? " (" + a.getAnalisisCalidad().getResultado().name() + ")" : ""), normal);
                    celda(t, a.getMotivo(), normal);
                }
                doc.add(t);
                doc.add(espacio(8));
                PdfPTable desglose = tabla(new float[]{2f, 1.4f});
                fila(desglose, "Subtotal a precio del pago", soles(totalSubtotal, 2), normal, negrita);
                fila(desglose, "Ajuste por calidad de entregas", soles(totalAPagar.subtract(totalSubtotal), 2), normal, negrita);
                doc.add(desglose);
            }

            // --- Total a pagar ---
            doc.add(espacio(14));
            PdfPTable resumen = new PdfPTable(new float[]{2f, 1.4f});
            resumen.setWidthPercentage(100);
            PdfPCell etiqueta = new PdfPCell(new Phrase("TOTAL A PAGAR"
                    + (ajustes.isEmpty() || !calidad.isEmpty() ? "" : "  (" + litros(pago.getLitrosTotales()) + " × " + precio(precioEfectivo) + ")"), total));
            PdfPCell importe = new PdfPCell(new Phrase(soles(totalAPagar, 2), total));
            for (PdfPCell c : List.of(etiqueta, importe)) {
                c.setBackgroundColor(SALVIA);
                c.setBorderColor(VERDE);
                c.setPadding(10);
                c.setVerticalAlignment(Element.ALIGN_MIDDLE);
            }
            importe.setHorizontalAlignment(Element.ALIGN_RIGHT);
            resumen.addCell(etiqueta);
            resumen.addCell(importe);
            doc.add(resumen);

            doc.add(espacio(24));
            Paragraph nota = new Paragraph("Documento interno de liquidación de acopio. No es una boleta ni una factura "
                    + "electrónica y no tiene valor tributario.", pie);
            doc.add(nota);
        } catch (DocumentException e) {
            throw new IllegalStateException("No se pudo generar la boleta del pago " + pagoId, e);
        } finally {
            if (doc.isOpen()) {
                doc.close();
            }
        }
        return salida.toByteArray();
    }

    private static Paragraph espacio(float puntos) {
        Paragraph p = new Paragraph(" ");
        p.setLeading(puntos);
        return p;
    }

    private static PdfPTable tabla(float[] anchos) {
        PdfPTable t = new PdfPTable(anchos);
        t.setWidthPercentage(100);
        t.setSpacingBefore(4);
        return t;
    }

    private static void fila(PdfPTable t, String etiqueta, String valor, Font normal, Font negrita) {
        PdfPCell a = new PdfPCell(new Phrase(etiqueta, normal));
        PdfPCell b = new PdfPCell(new Phrase(valor, negrita));
        for (PdfPCell c : List.of(a, b)) {
            c.setBorder(Rectangle.BOTTOM);
            c.setBorderColor(LINEA);
            c.setPaddingTop(5);
            c.setPaddingBottom(5);
        }
        t.addCell(a);
        t.addCell(b);
    }

    private static void encabezado(PdfPTable t, String texto) {
        PdfPCell c = new PdfPCell(new Phrase(texto, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE)));
        c.setBackgroundColor(VERDE);
        c.setBorderColor(VERDE);
        c.setPadding(5);
        t.addCell(c);
    }

    private static void celda(PdfPTable t, String texto, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(texto == null ? "" : texto, f));
        c.setBorderColor(LINEA);
        c.setPadding(5);
        t.addCell(c);
    }

    private static String fecha(LocalDate f) {
        return f.format(FECHA);
    }

    private static String litros(BigDecimal l) {
        return l.stripTrailingZeros().toPlainString() + " L";
    }

    // Precio por litro: al menos 2 decimales (S/ 1.00) y hasta 3 si hacen falta (S/ 1.255).
    private static String precio(BigDecimal p) {
        BigDecimal limpio = p.stripTrailingZeros();
        return soles(limpio.scale() < 2 ? limpio.setScale(2) : limpio, Math.max(2, limpio.scale()));
    }

    // Importes con punto decimal y separador de miles estable (no depende del idioma del servidor).
    private static String soles(BigDecimal importe, int decimales) {
        return String.format(Locale.US, "S/ %,." + decimales + "f", importe);
    }

    private static boolean presente(String texto) {
        return texto != null && !texto.isBlank();
    }
}
