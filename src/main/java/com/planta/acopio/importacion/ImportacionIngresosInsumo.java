package com.planta.acopio.importacion;

import com.planta.acopio.dto.IngresoDocumentoRequest;
import com.planta.acopio.dto.ProveedorCompraRequest;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.ProveedorCompra;
import com.planta.acopio.entity.TipoDocumentoIngreso;
import com.planta.acopio.repository.DocumentoIngresoRepository;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.LoteInsumoRepository;
import com.planta.acopio.repository.ProveedorCompraRepository;
import com.planta.acopio.service.BusquedaTexto;
import com.planta.acopio.service.DocumentoIdentidad;
import com.planta.acopio.service.IngresoInsumoService;
import com.planta.acopio.service.ProveedorCompraService;
import com.planta.acopio.service.ReglaVencimiento;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Importacion de INGRESOS de insumos con lotes desde Excel (movimientos operativos: excepcion autorizada del importador de datos
 * maestros; solo ADMIN, siempre por el servicio de negocio, nunca SQL directo). Cada fila es la linea de un ingreso (un lote); las filas
 * con el mismo proveedor + tipo + numero de documento forman UN documento de ingreso. El stock cambia solo por movimientos INGRESO que
 * quedan en el kardex.
 *
 * Reglas: el insumo se busca por nombre sin mayusculas ni tildes y, si no existe, la fila es un ERROR (un ingreso no crea insumos);
 * el proveedor se busca por RUC y, si no existe, se crea con nombre + RUC en «Otros insumos» (se avisa en la vista previa); no se
 * importa a medias salvo confirmacion expresa, y un documento con una fila invalida queda fuera completo; volver a subir el mismo
 * archivo no duplica (el archivo ya importado se rechaza y cada documento lleva un identificador estable derivado del archivo).
 */
@Component
public class ImportacionIngresosInsumo implements TipoImportacion<ImportacionIngresosInsumo.Contexto> {

    public static final String CODIGO = "ingresos-insumo";
    private static final Pattern DECIMAL = Pattern.compile("\\d{1,10}(\\.\\d{1,2})?");

    private static final ColumnaImportacion INSUMO = new ColumnaImportacion("insumo", "Insumo", true, false, 30,
            "Nombre de un insumo que ya existe (no distingue mayúsculas ni tildes). Un ingreso no crea insumos.", "Azúcar");
    private static final ColumnaImportacion CANTIDAD = new ColumnaImportacion("cantidad", "Cantidad", true, false, 14,
            "Número mayor que 0, con hasta 2 decimales (usa coma o punto).", "100");
    private static final ColumnaImportacion UNIDAD = new ColumnaImportacion("unidad", "Unidad", true, false, 14,
            "Debe coincidir con la unidad del insumo.", "kg");
    private static final ColumnaImportacion LOTE = new ColumnaImportacion("lote", "Lote", false, true, 22,
            "Opcional: vacío = se genera solo. Único por insumo.", "AZU-2026-01");
    private static final ColumnaImportacion LOTE_PROVEEDOR = new ColumnaImportacion("lote_proveedor", "Lote del proveedor", false, true, 22,
            "Opcional: el código que puso el proveedor.", "PV-77");
    private static final ColumnaImportacion VENCIMIENTO = new ColumnaImportacion("vencimiento", "Fecha de vencimiento", false, false, 18,
            "Opcional. Formato dd/mm/aaaa o aaaa-mm-dd; no puede ser anterior a hoy.", "31/01/2027");
    private static final ColumnaImportacion PROVEEDOR = new ColumnaImportacion("proveedor", "Proveedor", false, false, 30,
            "Nombre del proveedor; solo se usa para crearlo si el RUC no existe.", "Distribuidora Andina SAC");
    private static final ColumnaImportacion RUC = new ColumnaImportacion("ruc", "RUC", false, true, 14,
            "11 dígitos. Si existe, se usa ese proveedor; si no, se crea con el nombre indicado en «Otros insumos».", "20100070970");
    private static final ColumnaImportacion TIPO_DOCUMENTO = new ColumnaImportacion("tipo_documento", "Tipo de documento", false, false, 18,
            "Guía, Factura, Boleta, Otro o Sin documento. Vacío = Sin documento.", "Factura");
    private static final ColumnaImportacion NUMERO_DOCUMENTO = new ColumnaImportacion("numero_documento", "Número de documento", false, true, 20,
            "Obligatorio si hay tipo de documento. Las filas con el mismo proveedor, tipo y número forman un solo documento.", "F001-0234");
    private static final ColumnaImportacion FECHA_DOCUMENTO = new ColumnaImportacion("fecha_documento", "Fecha del documento", false, false, 18,
            "Opcional. Formato dd/mm/aaaa o aaaa-mm-dd; no puede ser futura.", "28/10/2026");
    private static final ColumnaImportacion OBSERVACION = new ColumnaImportacion("observacion", "Observación", false, false, 36,
            "Opcional (hasta 500 caracteres).", "Compra semanal");

    private final InsumoRepository insumoRepository;
    private final ProveedorCompraRepository proveedorRepository;
    private final LoteInsumoRepository loteRepository;
    private final DocumentoIngresoRepository documentoRepository;
    private final ProveedorCompraService proveedorService;
    private final IngresoInsumoService ingresoService;
    private final ReglaVencimiento reglaVencimiento;

    public ImportacionIngresosInsumo(InsumoRepository insumoRepository, ProveedorCompraRepository proveedorRepository, LoteInsumoRepository loteRepository,
                                     DocumentoIngresoRepository documentoRepository, ProveedorCompraService proveedorService, IngresoInsumoService ingresoService,
                                     ReglaVencimiento reglaVencimiento) {
        this.insumoRepository = insumoRepository;
        this.proveedorRepository = proveedorRepository;
        this.loteRepository = loteRepository;
        this.documentoRepository = documentoRepository;
        this.proveedorService = proveedorService;
        this.ingresoService = ingresoService;
        this.reglaVencimiento = reglaVencimiento;
    }

    public record InsumoConocido(UUID id, String nombre, String unidad) {}

    public record ProveedorConocido(UUID id, String nombre, boolean activo) {}

    public record Contexto(Map<String, InsumoConocido> insumos, Map<String, ProveedorConocido> proveedores, Set<String> lotes) {}

    @Override public String codigo() { return CODIGO; }
    @Override public String titulo() { return "Ingresos de insumos con lotes"; }
    @Override public int version() { return 1; }
    @Override public boolean permiteImportacionParcial() { return false; }
    @Override public boolean rechazaArchivoRepetido() { return true; }

    @Override
    public List<ColumnaImportacion> columnas() {
        return List.of(INSUMO, CANTIDAD, UNIDAD, LOTE, LOTE_PROVEEDOR, VENCIMIENTO, PROVEEDOR, RUC, TIPO_DOCUMENTO, NUMERO_DOCUMENTO, FECHA_DOCUMENTO, OBSERVACION);
    }

    @Override
    public List<String> instrucciones() {
        return List.of(
                "INGRESOS DE INSUMOS CON LOTES",
                "• Cada fila es una línea de ingreso y crea un LOTE. Todo pasa por movimientos de INGRESO: queda en el kardex del insumo, nunca se cambia el stock directamente.",
                "• Las filas con el mismo proveedor (RUC), tipo y número de documento se agrupan en UN documento de ingreso.",
                "• El insumo debe existir: si no, la fila es un error (no se crea solo). El proveedor se busca por RUC; si no existe, se crea con el nombre indicado en «Otros insumos» y se avisa.",
                "• Si hay errores no se importa nada salvo que confirmes expresamente importar solo las filas válidas. Un documento con una fila inválida queda fuera completo.",
                "• Volver a subir el mismo archivo no duplica ingresos: un archivo ya importado se rechaza y un documento ya cargado se informa.");
    }

    @Override
    public Map<String, List<String>> listasDesplegables() {
        return Map.of(TIPO_DOCUMENTO.clave(), List.of("Guía", "Factura", "Boleta", "Otro", "Sin documento"));
    }

    @Override
    public Contexto preparar(List<FilaCruda> filas) {
        Map<String, InsumoConocido> insumos = new HashMap<>();
        for (Insumo i : insumoRepository.findAll()) {
            if (Boolean.TRUE.equals(i.getActivo()) && !i.estaFusionado()) {
                insumos.put(BusquedaTexto.clave(i.getNombre()), new InsumoConocido(i.getId(), i.getNombre(), i.getUnidadMedida()));
            }
        }
        Map<String, ProveedorConocido> proveedores = new HashMap<>();
        for (ProveedorCompra p : proveedorRepository.findAll()) {
            proveedores.put(p.getRuc(), new ProveedorConocido(p.getId(), p.getNombre(), Boolean.TRUE.equals(p.getActivo())));
        }
        Set<String> lotes = new HashSet<>();
        loteRepository.findAll().forEach(l -> lotes.add(l.getInsumo().getId() + "|" + l.getCodigo().toLowerCase()));
        return new Contexto(insumos, proveedores, lotes);
    }

    @Override
    public ResultadoFila validar(FilaCruda fila, Contexto ctx) {
        List<String> errores = new ArrayList<>();
        String insumoTxt = ReglasCelda.textoObligatorio(fila, INSUMO, 60, errores);
        BigDecimal cantidad = leerCantidad(fila, errores);
        String unidad = ReglasCelda.textoObligatorio(fila, UNIDAD, 20, errores);
        String lote = textoOpcional(fila, LOTE, 40, errores);
        String loteProveedor = textoOpcional(fila, LOTE_PROVEEDOR, 60, errores);
        LocalDate vence = leerFecha(fila, VENCIMIENTO, errores);
        String nombreProveedor = textoOpcional(fila, PROVEEDOR, 150, errores);
        String ruc = DocumentoIdentidad.limpiar(textoOpcional(fila, RUC, 20, errores));
        String tipoTxt = textoOpcional(fila, TIPO_DOCUMENTO, 30, errores);
        String numero = textoOpcional(fila, NUMERO_DOCUMENTO, 40, errores);
        LocalDate fechaDoc = leerFecha(fila, FECHA_DOCUMENTO, errores);
        String observacion = textoOpcional(fila, OBSERVACION, 500, errores);

        InsumoConocido insumo = null;
        if (insumoTxt != null) {
            insumo = ctx.insumos().get(BusquedaTexto.clave(insumoTxt));
            if (insumo == null) {
                errores.add("Insumo: «" + insumoTxt + "» no existe. Un ingreso no crea insumos: créalo antes en Inventario > Insumos.");
            } else if (unidad != null && !unidad.trim().equalsIgnoreCase(insumo.unidad().trim())) {
                errores.add("Unidad: «" + unidad + "» no coincide con la del insumo («" + insumo.unidad() + "»).");
            }
        }
        LocalDate hoy = reglaVencimiento.hoy();
        if (vence != null && vence.isBefore(hoy)) errores.add("Fecha de vencimiento: " + vence + " ya pasó; un ingreso no puede traer un lote vencido.");
        if (fechaDoc != null && fechaDoc.isAfter(hoy)) errores.add("Fecha del documento: no puede ser futura.");

        TipoDocumentoIngreso tipo = tipoDocumento(tipoTxt, errores);
        if (tipo != null && tipo != TipoDocumentoIngreso.SIN_DOCUMENTO && (numero == null || numero.isEmpty())) {
            errores.add("Número de documento: es obligatorio cuando hay tipo de documento (" + tipo.etiqueta() + ").");
        }
        if (tipo == TipoDocumentoIngreso.SIN_DOCUMENTO && numero != null && !numero.isEmpty()) {
            errores.add("Tipo de documento: indica si el número " + numero + " es una guía, factura, boleta u otro.");
        }

        ProveedorConocido proveedor = null;
        String aviso = null;
        if (ruc != null && !ruc.isEmpty()) {
            if (!ruc.matches("\\d{11}")) {
                errores.add("RUC: debe tener exactamente 11 dígitos.");
            } else if (!DocumentoIdentidad.rucValido(ruc)) {
                errores.add("RUC: el dígito verificador no coincide; revisa los 11 dígitos.");
            } else {
                proveedor = ctx.proveedores().get(ruc);
                if (proveedor != null && !proveedor.activo()) {
                    errores.add("Proveedor: «" + proveedor.nombre() + "» (RUC " + ruc + ") está desactivado.");
                } else if (proveedor == null && (nombreProveedor == null || nombreProveedor.isEmpty())) {
                    errores.add("Proveedor: el RUC " + ruc + " no existe; indica el nombre del proveedor para crearlo.");
                } else if (proveedor == null) {
                    aviso = "Se creará el proveedor «" + nombreProveedor + "» (RUC " + ruc + ") en «Otros insumos».";
                }
            }
        } else if (nombreProveedor != null && !nombreProveedor.isEmpty()) {
            errores.add("RUC: es obligatorio cuando indicas un proveedor (se busca por RUC).");
        }

        if (insumo != null && lote != null && !lote.isEmpty() && ctx.lotes().contains(insumo.id() + "|" + lote.toLowerCase())) {
            errores.add("Lote: «" + lote + "» ya existe para este insumo.");
        }
        if (!errores.isEmpty()) {
            return ResultadoFila.invalida(ReglasCelda.crudos(fila, columnas()), errores);
        }

        Map<String, String> v = new LinkedHashMap<>();
        v.put(INSUMO.clave(), insumo.nombre());
        v.put(CANTIDAD.clave(), cantidad.setScale(2).toPlainString());
        v.put(UNIDAD.clave(), insumo.unidad());
        v.put(LOTE.clave(), lote == null ? "" : lote);
        v.put(LOTE_PROVEEDOR.clave(), loteProveedor == null ? "" : loteProveedor);
        v.put(VENCIMIENTO.clave(), vence == null ? "" : vence.toString());
        v.put(PROVEEDOR.clave(), proveedor != null ? proveedor.nombre() : (nombreProveedor == null ? "" : nombreProveedor));
        v.put(RUC.clave(), ruc == null ? "" : ruc);
        v.put(TIPO_DOCUMENTO.clave(), tipo.etiqueta());
        v.put(NUMERO_DOCUMENTO.clave(), tipo == TipoDocumentoIngreso.SIN_DOCUMENTO || numero == null ? "" : numero);
        v.put(FECHA_DOCUMENTO.clave(), fechaDoc == null ? "" : fechaDoc.toString());
        v.put(OBSERVACION.clave(), observacion == null ? "" : observacion);
        v.put("_insumoId", insumo.id().toString());
        v.put("_fila", String.valueOf(fila.numero()));

        // Documento ya cargado: mismo proveedor, tipo y numero (idempotencia por documento ademas de por archivo).
        if (proveedor != null && !v.get(NUMERO_DOCUMENTO.clave()).isEmpty()
                && documentoRepository.existsByProveedorIdAndTipoAndNumeroIgnoreCase(proveedor.id(), tipo, v.get(NUMERO_DOCUMENTO.clave()))) {
            return ResultadoFila.duplicada(v, "El documento " + tipo.etiqueta() + " " + v.get(NUMERO_DOCUMENTO.clave()) + " de «" + proveedor.nombre() + "» ya fue cargado.");
        }
        return aviso == null ? ResultadoFila.valida(v) : new ResultadoFila(EstadoFila.VALIDA, v, List.of(aviso));
    }

    /** Un lote (codigo) no puede repetirse dentro del archivo para el mismo insumo. */
    @Override
    public String claveUnica(Map<String, String> v) {
        String lote = v.get(LOTE.clave());
        return lote == null || lote.isEmpty() ? null : v.get("_insumoId") + "|" + lote.toLowerCase();
    }

    /**
     * Documento: proveedor (RUC) + tipo + numero. Funciona tambien con los valores crudos de una fila invalida (para que una fila con
     * error arrastre a las demas de su documento). Sin documento, cada fila va sola (null en una fila cruda).
     */
    @Override
    public String claveGrupo(Map<String, String> v) {
        String numero = v.get(NUMERO_DOCUMENTO.clave());
        if (numero == null || numero.trim().isEmpty()) {
            return v.containsKey("_fila") ? "FILA|" + v.get("_fila") : null;
        }
        String tipo = BusquedaTexto.clave(v.getOrDefault(TIPO_DOCUMENTO.clave(), ""));
        String ruc = v.getOrDefault(RUC.clave(), "").replaceAll("[^0-9]", "");
        return ruc + "|" + tipo + "|" + numero.trim().toUpperCase();
    }

    @Override
    public Optional<String> validarGrupo(List<Map<String, String>> filas) {
        Set<String> fechas = new HashSet<>();
        filas.forEach(f -> fechas.add(f.get(FECHA_DOCUMENTO.clave())));
        return fechas.size() > 1 ? Optional.of("Las filas de este documento tienen fechas de documento distintas; deben coincidir.") : Optional.empty();
    }

    @Override
    public Optional<String> conflictoAlConfirmar(Map<String, String> v) {
        String lote = v.get(LOTE.clave());
        UUID insumoId = UUID.fromString(v.get("_insumoId"));
        if (lote != null && !lote.isEmpty() && loteRepository.existsByInsumoIdAndCodigo(insumoId, lote)) {
            return Optional.of("El lote «" + lote + "» se registró después de la vista previa.");
        }
        String ruc = v.get(RUC.clave());
        String numero = v.get(NUMERO_DOCUMENTO.clave());
        if (ruc != null && !ruc.isEmpty() && numero != null && !numero.isEmpty()) {
            Optional<ProveedorCompra> p = proveedorRepository.findByRuc(ruc);
            TipoDocumentoIngreso tipo = tipoDocumento(v.get(TIPO_DOCUMENTO.clave()), new ArrayList<>());
            if (p.isPresent() && tipo != null && documentoRepository.existsByProveedorIdAndTipoAndNumeroIgnoreCase(p.get().getId(), tipo, numero)) {
                return Optional.of("El documento " + numero + " se cargó después de la vista previa.");
            }
        }
        return Optional.empty();
    }

    @Override
    public void crear(Map<String, String> v) {
        crearGrupo("FILA|" + v.get("_fila"), List.of(v), "sin-archivo");
    }

    @Override
    public void crearGrupo(String clave, List<Map<String, String>> filas, String huella) {
        Map<String, String> primera = filas.get(0);
        UUID proveedorId = null;
        String ruc = primera.get(RUC.clave());
        if (ruc != null && !ruc.isEmpty()) {
            proveedorId = proveedorRepository.findByRuc(ruc).map(ProveedorCompra::getId)
                    .orElseGet(() -> proveedorService.crear(new ProveedorCompraRequest(primera.get(PROVEEDOR.clave()), ruc)).id());
        }
        TipoDocumentoIngreso tipo = tipoDocumento(primera.get(TIPO_DOCUMENTO.clave()), new ArrayList<>());
        String numero = primera.get(NUMERO_DOCUMENTO.clave());
        String fecha = primera.get(FECHA_DOCUMENTO.clave());
        String observacion = filas.stream().map(f -> f.get(OBSERVACION.clave())).filter(o -> o != null && !o.isEmpty()).findFirst().orElse(null);
        List<IngresoDocumentoRequest.Linea> lineas = filas.stream().map(f -> new IngresoDocumentoRequest.Linea(
                UUID.fromString(f.get("_insumoId")), new BigDecimal(f.get(CANTIDAD.clave())), vacioANulo(f.get(LOTE.clave())),
                vacioANulo(f.get(LOTE_PROVEEDOR.clave())), f.get(VENCIMIENTO.clave()).isEmpty() ? null : LocalDate.parse(f.get(VENCIMIENTO.clave())))).toList();
        // Identificador estable: el mismo archivo + el mismo documento dan siempre el mismo uuid, asi que repetir la importacion no duplica.
        UUID uuid = UUID.nameUUIDFromBytes((huella + "|" + clave).getBytes(StandardCharsets.UTF_8));
        ingresoService.registrar(new IngresoDocumentoRequest(uuid, proveedorId, tipo, vacioANulo(numero), vacioANulo(fecha) == null ? null : LocalDate.parse(fecha),
                observacion, lineas));
    }

    // ------------------------------------------------------------------------------------------------------------ lectura

    private static String vacioANulo(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    static String textoOpcional(FilaCruda fila, ColumnaImportacion c, int max, List<String> errores) {
        String t = ReglasCelda.texto(fila, c, errores);
        if (t != null && t.length() > max) {
            errores.add(c.encabezado() + ": tiene " + t.length() + " caracteres y el máximo es " + max + ".");
            return null;
        }
        return t;
    }

    static BigDecimal leerCantidad(FilaCruda fila, List<String> errores) {
        String t = ReglasCelda.texto(fila, CANTIDAD, errores);
        if (t == null) return null;
        if (t.isEmpty()) {
            errores.add("Cantidad: es obligatoria.");
            return null;
        }
        String compacto = t.replace(" ", "");
        if (compacto.contains(",") && compacto.contains(".")) {
            errores.add("Cantidad: «" + t + "» tiene coma y punto; escribe solo un separador decimal.");
            return null;
        }
        String n = compacto.replace(',', '.');
        if (!DECIMAL.matcher(n).matches() || new BigDecimal(n).signum() <= 0) {
            errores.add("Cantidad: «" + t + "» no es válida; debe ser mayor que 0, con hasta 2 decimales.");
            return null;
        }
        return new BigDecimal(n);
    }

    /** Acepta aaaa-mm-dd (las celdas de fecha de Excel llegan asi) y dd/mm/aaaa. */
    static LocalDate leerFecha(FilaCruda fila, ColumnaImportacion c, List<String> errores) {
        String t = ReglasCelda.texto(fila, c, errores);
        if (t == null || t.isEmpty()) return null;
        // STRICT: 31/02/2027 no es "el ultimo dia de febrero", es una fecha invalida.
        for (DateTimeFormatter f : new DateTimeFormatter[]{DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT),
                DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)}) {
            try {
                return LocalDate.parse(t, f);
            } catch (DateTimeParseException ignorar) {
                // se prueba el siguiente formato
            }
        }
        errores.add(c.encabezado() + ": «" + t + "» no es una fecha válida (usa dd/mm/aaaa o aaaa-mm-dd).");
        return null;
    }

    private static TipoDocumentoIngreso tipoDocumento(String texto, List<String> errores) {
        if (texto == null || texto.isEmpty()) return TipoDocumentoIngreso.SIN_DOCUMENTO;
        return switch (BusquedaTexto.clave(texto)) {
            case "guia" -> TipoDocumentoIngreso.GUIA;
            case "factura" -> TipoDocumentoIngreso.FACTURA;
            case "boleta" -> TipoDocumentoIngreso.BOLETA;
            case "otro", "otro documento", "documento" -> TipoDocumentoIngreso.OTRO;
            case "sin documento" -> TipoDocumentoIngreso.SIN_DOCUMENTO;
            default -> {
                errores.add("Tipo de documento: «" + texto + "» no es válido (Guía, Factura, Boleta, Otro o Sin documento).");
                yield null;
            }
        };
    }
}
