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
import com.planta.acopio.dto.AvisoPrivacidadResponse;
import com.planta.acopio.dto.DetalleVentaResponse;
import com.planta.acopio.dto.VentaResumenResponse;
import com.planta.acopio.entity.TipoComprobante;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

// Comprobante de venta (nota de venta o boleta) en PDF: lo que el vendedor imprime o envia al comprador. Reune lo que el sistema ya
// guardo de la venta (numero, hora, vendedor, comprador, lineas con su codigo, IGV si se calculo) sin recalcular nada.
//
// Mientras no exista la integracion con SUNAT es un comprobante INTERNO y asi lo dice el pie del documento. Privacidad: el DNI y el
// telefono del comprador NO se imprimen (la venta solo conserva el nombre y, si lo tiene, el RUC usados en esa venta).
// Datos de la empresa: nombre comercial de app.empresa.nombre; razon social, RUC y domicilio de la configuracion del aviso de
// privacidad (app.privacidad.*) cuando existen -- si faltan, simplemente no se imprimen (no se inventa ninguno).
@Service
public class ComprobanteVentaPdfService {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final Color VERDE = new Color(0x24, 0x5C, 0x43);
    private static final Color SALVIA = new Color(0xEA, 0xF2, 0xE8);
    private static final Color FONDO = new Color(0xF4, 0xF6, 0xF2);
    private static final Color LINEA = new Color(0xE2, 0xDF, 0xD1);
    private static final Color TINTA = new Color(0x29, 0x33, 0x2D);
    private static final Color APAGADO = new Color(0x6B, 0x75, 0x6E);

    private final VentaService ventaService;
    private final PrivacidadService privacidadService;
    private final String empresa;

    public ComprobanteVentaPdfService(VentaService ventaService, PrivacidadService privacidadService,
                                      @Value("${app.empresa.nombre:EcoLácteos Huata}") String empresa) {
        this.ventaService = ventaService;
        this.privacidadService = privacidadService;
        this.empresa = empresa;
    }

    /** El PDF y el nombre con el que se ofrece (el numero del comprobante). */
    public record ComprobantePdf(String nombreArchivo, byte[] contenido) {}

    @Transactional(readOnly = true)
    public ComprobantePdf generarPdf(UUID ventaId) {
        VentaResumenResponse venta = ventaService.obtener(ventaId);
        return new ComprobantePdf(nombreArchivo(venta), generar(venta));
    }

    /** Nombre del archivo: el numero del comprobante (NV01-00000041) o, si no lo tuviera, el inicio del id. */
    public static String nombreArchivo(VentaResumenResponse v) {
        String base = v.comprobante() != null ? v.comprobante() : "venta-" + v.id().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        return base + ".pdf";
    }

    byte[] generar(VentaResumenResponse v) {
        AvisoPrivacidadResponse aviso = privacidadService.avisos();
        TipoComprobante tipo = v.tipoComprobante() == null ? TipoComprobante.NOTA_VENTA : TipoComprobante.deTexto(v.tipoComprobante());

        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 40, 40);
        try {
            PdfWriter.getInstance(doc, salida);
            doc.addTitle(tipo.etiqueta() + " " + (v.comprobante() != null ? v.comprobante() : ""));
            doc.addAuthor(empresa);
            doc.open();

            Font marca = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, VERDE);
            Font razon = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, TINTA);
            Font apagado = FontFactory.getFont(FontFactory.HELVETICA, 9, APAGADO);
            Font normal = FontFactory.getFont(FontFactory.HELVETICA, 10, TINTA);
            Font negrita = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, TINTA);
            Font etiqueta = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, APAGADO);
            Font blanca = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.WHITE);
            Font numero = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, VERDE);
            Font totalBlanco = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, Color.WHITE);
            Font pie = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, Color.GRAY);

            // --- Cabecera: empresa a la izquierda, recuadro con tipo y numero a la derecha ---
            PdfPTable cabecera = new PdfPTable(new float[]{3f, 2.2f});
            cabecera.setWidthPercentage(100);
            PdfPCell izquierda = new PdfPCell();
            izquierda.setBorder(Rectangle.NO_BORDER);
            izquierda.addElement(new Paragraph(empresa, marca));
            if (presente(aviso.responsable()) && !aviso.responsable().equalsIgnoreCase(empresa)) {
                izquierda.addElement(new Paragraph(aviso.responsable().toUpperCase(Locale.ROOT), razon));
            }
            if (presente(aviso.domicilio())) izquierda.addElement(new Paragraph(aviso.domicilio(), apagado));
            cabecera.addCell(izquierda);

            PdfPTable recuadro = new PdfPTable(1);
            recuadro.setWidthPercentage(100);
            if (presente(aviso.ruc())) {
                recuadro.addCell(centrada("R.U.C. " + aviso.ruc(), negrita, null, 8));
            }
            recuadro.addCell(centrada(tipo.etiqueta().toUpperCase(Locale.ROOT), blanca, VERDE, 7));
            recuadro.addCell(centrada(v.comprobante() != null ? v.comprobante() : "—", numero, null, 9));
            PdfPCell derecha = new PdfPCell(recuadro);
            derecha.setBorder(Rectangle.NO_BORDER);
            derecha.setPaddingLeft(14);
            cabecera.addCell(derecha);
            doc.add(cabecera);
            doc.add(espacio(10));

            // --- Datos del comprador y de la venta ---
            List<String[]> izq = new ArrayList<>();
            String cliente = v.comprador() != null && presente(v.comprador().nombre()) ? v.comprador().nombre().toUpperCase(Locale.ROOT) : "CLIENTE VARIOS";
            izq.add(new String[]{"CLIENTE", cliente});
            if (v.comprador() != null && presente(v.comprador().ruc())) izq.add(new String[]{"RUC", v.comprador().ruc()});
            if (presente(v.vendedorNombre())) izq.add(new String[]{"VENDEDOR", v.vendedorNombre()});
            if (presente(v.reservaCodigo())) izq.add(new String[]{"RESERVA", v.reservaCodigo()});
            List<String[]> der = new ArrayList<>();
            der.add(new String[]{"FECHA DE EMISIÓN", v.fecha().format(FECHA)});
            der.add(new String[]{"HORA", v.creadoEn() != null ? v.creadoEn().format(HORA) : "—"});
            der.add(new String[]{"MONEDA", "SOLES (PEN)"});
            der.add(new String[]{"CONDICIÓN", "CONTADO"});

            PdfPTable datos = new PdfPTable(new float[]{1.1f, 3f, 1.4f, 2f});
            datos.setWidthPercentage(100);
            int filas = Math.max(izq.size(), der.size());
            for (int i = 0; i < filas; i++) {
                String[] a = i < izq.size() ? izq.get(i) : new String[]{"", ""};
                String[] b = i < der.size() ? der.get(i) : new String[]{"", ""};
                datos.addCell(panel(a[0], etiqueta));
                datos.addCell(panel(a[1], normal));
                datos.addCell(panel(b[0], etiqueta));
                datos.addCell(panel(b[1], normal));
            }
            doc.add(datos);
            doc.add(espacio(12));

            // --- Lineas ---
            PdfPTable tabla = new PdfPTable(new float[]{0.6f, 1.2f, 4f, 0.9f, 1.1f, 1.2f});
            tabla.setWidthPercentage(100);
            String[] titulos = {"ÍTEM", "CÓDIGO", "DESCRIPCIÓN", "CANT.", "P. UNIT.", "IMPORTE"};
            for (int i = 0; i < titulos.length; i++) {
                tabla.addCell(encabezado(titulos[i], i >= 3 ? Element.ALIGN_RIGHT : i == 0 ? Element.ALIGN_CENTER : Element.ALIGN_LEFT));
            }
            int item = 0;
            int articulos = 0;
            for (DetalleVentaResponse d : v.detalles()) {
                item++;
                articulos += d.cantidad();
                Color fondo = item % 2 == 0 ? FONDO : null;
                tabla.addCell(fila(String.valueOf(item), normal, Element.ALIGN_CENTER, fondo));
                tabla.addCell(fila(d.codigo() == null ? "" : d.codigo(), apagado, Element.ALIGN_LEFT, fondo));
                tabla.addCell(fila(d.productoNombre() + " - " + d.presentacionNombre(), normal, Element.ALIGN_LEFT, fondo));
                tabla.addCell(fila(String.valueOf(d.cantidad()), normal, Element.ALIGN_RIGHT, fondo));
                tabla.addCell(fila(numero(d.precioUnitario()), normal, Element.ALIGN_RIGHT, fondo));
                tabla.addCell(fila(numero(d.subtotal()), negrita, Element.ALIGN_RIGHT, fondo));
            }
            doc.add(tabla);
            doc.add(espacio(8));

            // --- Notas y totales ---
            VentaResumenResponse.ResumenIgvVenta igv = v.igv();
            boolean conIgv = igv != null && igv.incluyeIgv();
            PdfPTable pieTabla = new PdfPTable(new float[]{1.2f, 1f});
            pieTabla.setWidthPercentage(100);
            PdfPCell notas = new PdfPCell();
            notas.setBorder(Rectangle.NO_BORDER);
            if (conIgv) notas.addElement(new Paragraph("Los precios unitarios incluyen IGV.", apagado));
            notas.addElement(new Paragraph("Artículos: " + articulos, apagado));
            pieTabla.addCell(notas);

            PdfPTable totales = new PdfPTable(new float[]{1.4f, 1f});
            totales.setWidthPercentage(100);
            if (conIgv) {
                totales.addCell(desglose("OP. GRAVADA", negrita));
                totales.addCell(desgloseImporte(soles(igv.operacionesGravadas()), normal));
                if (igv.operacionesExoneradas().signum() > 0) {
                    totales.addCell(desglose("OP. EXONERADA", negrita));
                    totales.addCell(desgloseImporte(soles(igv.operacionesExoneradas()), normal));
                }
                if (igv.operacionesInafectas().signum() > 0) {
                    totales.addCell(desglose("OP. INAFECTA", negrita));
                    totales.addCell(desgloseImporte(soles(igv.operacionesInafectas()), normal));
                }
                totales.addCell(desglose(etiquetaIgv(v), negrita));
                totales.addCell(desgloseImporte(soles(igv.igvTotal()), normal));
            }
            PdfPCell tot = new PdfPCell(new Phrase("IMPORTE TOTAL", totalBlanco));
            PdfPCell imp = new PdfPCell(new Phrase(soles(v.total()), totalBlanco));
            for (PdfPCell c : List.of(tot, imp)) {
                c.setBackgroundColor(VERDE);
                c.setBorderColor(VERDE);
                c.setPadding(8);
                c.setVerticalAlignment(Element.ALIGN_MIDDLE);
            }
            imp.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totales.addCell(tot);
            totales.addCell(imp);
            PdfPCell celdaTotales = new PdfPCell(totales);
            celdaTotales.setBorder(Rectangle.NO_BORDER);
            pieTabla.addCell(celdaTotales);
            doc.add(pieTabla);
            doc.add(espacio(10));

            // --- Monto en letras ---
            PdfPTable son = new PdfPTable(1);
            son.setWidthPercentage(100);
            Phrase frase = new Phrase();
            frase.add(new com.lowagie.text.Chunk("SON: ", etiqueta));
            frase.add(new com.lowagie.text.Chunk(MontoEnLetras.soles(v.total()), negrita));
            PdfPCell celdaSon = new PdfPCell(frase);
            celdaSon.setBackgroundColor(SALVIA);
            celdaSon.setBorderColor(LINEA);
            celdaSon.setPadding(8);
            son.addCell(celdaSon);
            doc.add(son);

            doc.add(espacio(20));
            doc.add(new Paragraph("Comprobante interno emitido por el sistema de gestión de " + empresa
                    + ". Sin integración con SUNAT: no tiene validez tributaria.", pie));
        } catch (DocumentException e) {
            throw new IllegalStateException("No se pudo generar el comprobante de la venta " + v.id(), e);
        } finally {
            if (doc.isOpen()) doc.close();
        }
        return salida.toByteArray();
    }

    /** "IGV (18%)" con la tasa de la primera linea gravada; "IGV" si ninguna la trae. */
    private static String etiquetaIgv(VentaResumenResponse v) {
        for (DetalleVentaResponse d : v.detalles()) {
            if ("GRAVADO".equals(d.afectacionIgv()) && d.tasaIgv() != null) {
                return "IGV (" + d.tasaIgv().stripTrailingZeros().toPlainString() + "%)";
            }
        }
        return "IGV";
    }

    private static boolean presente(String s) {
        return s != null && !s.isBlank();
    }

    private static String numero(BigDecimal importe) {
        return importe.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String soles(BigDecimal importe) {
        return "S/ " + numero(importe);
    }

    private static Paragraph espacio(float puntos) {
        Paragraph p = new Paragraph(" ");
        p.setLeading(puntos);
        return p;
    }

    private static PdfPCell centrada(String texto, Font f, Color fondo, float relleno) {
        PdfPCell c = new PdfPCell(new Phrase(texto, f));
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setPadding(relleno);
        c.setBorderColor(VERDE);
        c.setBorderWidth(1.2f);
        if (fondo != null) c.setBackgroundColor(fondo);
        return c;
    }

    private static PdfPCell panel(String texto, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(texto, f));
        c.setBackgroundColor(FONDO);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPaddingTop(4);
        c.setPaddingBottom(4);
        c.setPaddingLeft(8);
        return c;
    }

    private static PdfPCell encabezado(String texto, int alineacion) {
        PdfPCell c = new PdfPCell(new Phrase(texto, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE)));
        c.setBackgroundColor(VERDE);
        c.setBorderColor(VERDE);
        c.setHorizontalAlignment(alineacion);
        c.setPadding(6);
        return c;
    }

    private static PdfPCell fila(String texto, Font f, int alineacion, Color fondo) {
        PdfPCell c = new PdfPCell(new Phrase(texto, f));
        c.setHorizontalAlignment(alineacion);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(LINEA);
        c.setPadding(6);
        if (fondo != null) c.setBackgroundColor(fondo);
        return c;
    }

    private static PdfPCell desglose(String texto, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(texto, f));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(LINEA);
        c.setPadding(6);
        return c;
    }

    private static PdfPCell desgloseImporte(String texto, Font f) {
        PdfPCell c = desglose(texto, f);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }
}
