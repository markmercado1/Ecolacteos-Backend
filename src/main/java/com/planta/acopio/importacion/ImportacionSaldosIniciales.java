package com.planta.acopio.importacion;

import com.planta.acopio.dto.ProveedorCompraRequest;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.LoteInsumo;
import com.planta.acopio.entity.ProveedorCompra;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.LoteInsumoRepository;
import com.planta.acopio.repository.ProveedorCompraRepository;
import com.planta.acopio.service.BusquedaTexto;
import com.planta.acopio.service.DocumentoIdentidad;
import com.planta.acopio.service.InventarioInsumoService;
import com.planta.acopio.service.ProveedorCompraService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Importacion de SALDOS INICIALES por lote (modo aparte): carga lo que hay hoy en el almacen con sus vencimientos reales, REEMPLAZANDO el
 * lote INICIAL del insumo. Por cada insumo, la suma de sus filas debe ser EXACTAMENTE el stock del sistema (el stock total nunca cambia
 * por esta via; si no cuadra, primero un conteo fisico) y el INICIAL debe ser su unico lote con existencia. El traslado queda en el kardex
 * como dos movimientos TRANSFERENCIA_LOTE (sale del INICIAL, entra al lote real); el INICIAL queda en 0 con su historial. Un insumo se
 * importa completo o no se importa. Los vencimientos pasados SI se aceptan (es stock real que se conoce recien ahora).
 */
@Component
public class ImportacionSaldosIniciales implements TipoImportacion<ImportacionSaldosIniciales.Contexto> {

    public static final String CODIGO = "saldos-iniciales";

    private static final ColumnaImportacion INSUMO = new ColumnaImportacion("insumo", "Insumo", true, false, 30,
            "Nombre de un insumo que ya existe (no distingue mayúsculas ni tildes).", "Azúcar");
    private static final ColumnaImportacion UNIDAD = new ColumnaImportacion("unidad", "Unidad", true, false, 14, "Debe coincidir con la unidad del insumo.", "kg");
    private static final ColumnaImportacion CANTIDAD = new ColumnaImportacion("cantidad", "Cantidad", true, false, 14,
            "Lo que hay hoy en ese lote. La suma de las filas de un insumo debe ser igual a su stock en el sistema.", "70");
    private static final ColumnaImportacion LOTE = new ColumnaImportacion("lote", "Lote", false, true, 22, "Opcional: vacío = se genera solo. No puede ser INICIAL.", "AZU-2026-A");
    private static final ColumnaImportacion LOTE_PROVEEDOR = new ColumnaImportacion("lote_proveedor", "Lote del proveedor", false, true, 22, "Opcional.", "PV-77");
    private static final ColumnaImportacion VENCIMIENTO = new ColumnaImportacion("vencimiento", "Fecha de vencimiento", false, false, 18,
            "Opcional. Formato dd/mm/aaaa o aaaa-mm-dd. Se acepta una fecha ya pasada.", "31/01/2027");
    private static final ColumnaImportacion PROVEEDOR = new ColumnaImportacion("proveedor", "Proveedor", false, false, 30,
            "Solo se usa para crear el proveedor si el RUC no existe.", "Distribuidora Andina SAC");
    private static final ColumnaImportacion RUC = new ColumnaImportacion("ruc", "RUC", false, true, 14, "11 dígitos (opcional).", "20100070970");
    private static final ColumnaImportacion OBSERVACION = new ColumnaImportacion("observacion", "Observación", false, false, 36, "Opcional.", "Conteo de almacén");

    private final InsumoRepository insumoRepository;
    private final ProveedorCompraRepository proveedorRepository;
    private final LoteInsumoRepository loteRepository;
    private final ProveedorCompraService proveedorService;
    private final InventarioInsumoService inventarioInsumoService;

    public ImportacionSaldosIniciales(InsumoRepository insumoRepository, ProveedorCompraRepository proveedorRepository, LoteInsumoRepository loteRepository,
                                      ProveedorCompraService proveedorService, InventarioInsumoService inventarioInsumoService) {
        this.insumoRepository = insumoRepository;
        this.proveedorRepository = proveedorRepository;
        this.loteRepository = loteRepository;
        this.proveedorService = proveedorService;
        this.inventarioInsumoService = inventarioInsumoService;
    }

    public record InsumoConocido(UUID id, String nombre, String unidad, BigDecimal stock, boolean regularizable, String motivoNoRegularizable) {}

    /** `sumas`: lo que suma el archivo por insumo; `lotes`: codigos ya existentes por insumo. */
    public record Contexto(Map<String, InsumoConocido> insumos, Map<UUID, BigDecimal> sumas, Map<String, UUID> proveedores, Set<String> lotes) {}

    @Override public String codigo() { return CODIGO; }
    @Override public String titulo() { return "Saldos iniciales por lote"; }
    @Override public int version() { return 1; }
    @Override public boolean permiteImportacionParcial() { return false; }
    @Override public boolean rechazaArchivoRepetido() { return true; }

    @Override
    public List<ColumnaImportacion> columnas() {
        return List.of(INSUMO, UNIDAD, CANTIDAD, LOTE, LOTE_PROVEEDOR, VENCIMIENTO, PROVEEDOR, RUC, OBSERVACION);
    }

    @Override
    public List<String> instrucciones() {
        return List.of(
                "SALDOS INICIALES POR LOTE",
                "• Sirve para cargar lo que hay HOY en el almacén, con sus lotes y vencimientos reales, reemplazando el lote INICIAL de cada insumo.",
                "• Por cada insumo, la suma de sus filas debe ser exactamente igual a su stock en el sistema: este modo no cambia el stock. Si no cuadra, corrige antes con un conteo físico.",
                "• El traslado queda en el kardex como movimientos de transferencia entre lotes; el lote INICIAL queda en 0 con su historial.",
                "• Un insumo con lotes de ingreso con existencia no se puede regularizar. Un insumo se importa completo o no se importa.");
    }

    @Override
    public Map<String, List<String>> listasDesplegables() {
        return Map.of();
    }

    @Override
    public Contexto preparar(List<FilaCruda> filas) {
        Map<String, InsumoConocido> insumos = new HashMap<>();
        for (Insumo i : insumoRepository.findAll()) {
            if (i.estaFusionado()) continue;
            String motivo = null;
            List<LoteInsumo> conExistencia = loteRepository.disponiblesFefo(i.getId());
            boolean soloInicial = conExistencia.stream().allMatch(l -> LoteInsumo.CODIGO_INICIAL.equals(l.getCodigo()));
            Optional<LoteInsumo> inicial = loteRepository.findByInsumoIdAndCodigo(i.getId(), LoteInsumo.CODIGO_INICIAL);
            if (inicial.isEmpty() || inicial.get().getCantidadDisponible().signum() == 0) {
                motivo = "no tiene saldo en el lote INICIAL que regularizar";
            } else if (!soloInicial) {
                motivo = "ya tiene lotes de ingreso con existencia; la regularización del saldo inicial no aplica";
            }
            insumos.put(BusquedaTexto.clave(i.getNombre()), new InsumoConocido(i.getId(), i.getNombre(), i.getUnidadMedida(), i.getStock(), motivo == null, motivo));
        }
        // Lo que suma el archivo por insumo (solo filas con insumo conocido y cantidad legible).
        Map<UUID, BigDecimal> sumas = new HashMap<>();
        for (FilaCruda fila : filas) {
            List<String> ignorar = new ArrayList<>();
            String nombre = ReglasCelda.texto(fila, INSUMO, ignorar);
            InsumoConocido i = nombre == null ? null : insumos.get(BusquedaTexto.clave(nombre));
            BigDecimal c = ImportacionIngresosInsumo.leerCantidad(fila, ignorar);
            if (i != null && c != null) sumas.merge(i.id(), c, BigDecimal::add);
        }
        Map<String, UUID> proveedores = new HashMap<>();
        for (ProveedorCompra p : proveedorRepository.findAll()) proveedores.put(p.getRuc(), p.getId());
        Set<String> lotes = new HashSet<>();
        loteRepository.findAll().forEach(l -> lotes.add(l.getInsumo().getId() + "|" + l.getCodigo().toLowerCase()));
        return new Contexto(insumos, sumas, proveedores, lotes);
    }

    @Override
    public ResultadoFila validar(FilaCruda fila, Contexto ctx) {
        List<String> errores = new ArrayList<>();
        String insumoTxt = ReglasCelda.textoObligatorio(fila, INSUMO, 60, errores);
        String unidad = ReglasCelda.textoObligatorio(fila, UNIDAD, 20, errores);
        BigDecimal cantidad = ImportacionIngresosInsumo.leerCantidad(fila, errores);
        String lote = ImportacionIngresosInsumo.textoOpcional(fila, LOTE, 40, errores);
        String loteProveedor = ImportacionIngresosInsumo.textoOpcional(fila, LOTE_PROVEEDOR, 60, errores);
        LocalDate vence = ImportacionIngresosInsumo.leerFecha(fila, VENCIMIENTO, errores);
        String nombreProveedor = ImportacionIngresosInsumo.textoOpcional(fila, PROVEEDOR, 150, errores);
        String ruc = DocumentoIdentidad.limpiar(ImportacionIngresosInsumo.textoOpcional(fila, RUC, 20, errores));
        String observacion = ImportacionIngresosInsumo.textoOpcional(fila, OBSERVACION, 500, errores);

        InsumoConocido insumo = null;
        if (insumoTxt != null) {
            insumo = ctx.insumos().get(BusquedaTexto.clave(insumoTxt));
            if (insumo == null) {
                errores.add("Insumo: «" + insumoTxt + "» no existe.");
            } else {
                if (unidad != null && !unidad.trim().equalsIgnoreCase(insumo.unidad().trim())) {
                    errores.add("Unidad: «" + unidad + "» no coincide con la del insumo («" + insumo.unidad() + "»).");
                }
                if (!insumo.regularizable()) {
                    errores.add("Insumo: «" + insumo.nombre() + "» " + insumo.motivoNoRegularizable() + ".");
                } else {
                    BigDecimal suma = ctx.sumas().getOrDefault(insumo.id(), BigDecimal.ZERO);
                    if (suma.compareTo(insumo.stock()) != 0) {
                        errores.add("Insumo: las filas de «" + insumo.nombre() + "» suman " + suma.stripTrailingZeros().toPlainString() + " y el stock del sistema es "
                                + insumo.stock().stripTrailingZeros().toPlainString() + "; deben ser iguales (corrige antes con un conteo físico).");
                    }
                }
            }
        }
        if (lote != null && !lote.isEmpty()) {
            if (LoteInsumo.CODIGO_INICIAL.equalsIgnoreCase(lote)) errores.add("Lote: «INICIAL» es el lote que se reemplaza; usa otro código.");
            else if (insumo != null && ctx.lotes().contains(insumo.id() + "|" + lote.toLowerCase())) errores.add("Lote: «" + lote + "» ya existe para este insumo.");
        }
        String aviso = null;
        if (ruc != null && !ruc.isEmpty()) {
            if (!ruc.matches("\\d{11}") || !DocumentoIdentidad.rucValido(ruc)) {
                errores.add("RUC: no es válido (11 dígitos con dígito verificador correcto).");
            } else if (!ctx.proveedores().containsKey(ruc)) {
                if (nombreProveedor == null || nombreProveedor.isEmpty()) errores.add("Proveedor: el RUC " + ruc + " no existe; indica el nombre para crearlo.");
                else aviso = "Se creará el proveedor «" + nombreProveedor + "» (RUC " + ruc + ") en «Otros insumos».";
            }
        } else if (nombreProveedor != null && !nombreProveedor.isEmpty()) {
            errores.add("RUC: es obligatorio cuando indicas un proveedor.");
        }
        if (!errores.isEmpty()) return ResultadoFila.invalida(ReglasCelda.crudos(fila, columnas()), errores);

        Map<String, String> v = new LinkedHashMap<>();
        v.put(INSUMO.clave(), insumo.nombre());
        v.put(UNIDAD.clave(), insumo.unidad());
        v.put(CANTIDAD.clave(), cantidad.setScale(2).toPlainString());
        v.put(LOTE.clave(), lote == null ? "" : lote);
        v.put(LOTE_PROVEEDOR.clave(), loteProveedor == null ? "" : loteProveedor);
        v.put(VENCIMIENTO.clave(), vence == null ? "" : vence.toString());
        v.put(PROVEEDOR.clave(), nombreProveedor == null ? "" : nombreProveedor);
        v.put(RUC.clave(), ruc == null ? "" : ruc);
        v.put(OBSERVACION.clave(), observacion == null ? "" : observacion);
        v.put("_insumoId", insumo.id().toString());
        return aviso == null ? ResultadoFila.valida(v) : new ResultadoFila(EstadoFila.VALIDA, v, List.of(aviso));
    }

    @Override
    public String claveUnica(Map<String, String> v) {
        String lote = v.get(LOTE.clave());
        return lote == null || lote.isEmpty() ? null : v.get("_insumoId") + "|" + lote.toLowerCase();
    }

    /** Todas las filas de un insumo se crean juntas (o ninguna). */
    @Override
    public String claveGrupo(Map<String, String> v) {
        // Por nombre normalizado: sirve igual para una fila valida (nombre canonico) y para una invalida (nombre tal como se escribio).
        return "INSUMO|" + BusquedaTexto.clave(v.getOrDefault(INSUMO.clave(), ""));
    }

    @Override
    public Optional<String> conflictoAlConfirmar(Map<String, String> v) {
        UUID id = UUID.fromString(v.get("_insumoId"));
        Insumo i = insumoRepository.findById(id).orElse(null);
        if (i == null) return Optional.of("El insumo ya no existe.");
        List<LoteInsumo> conExistencia = loteRepository.disponiblesFefo(id);
        if (conExistencia.stream().anyMatch(l -> !LoteInsumo.CODIGO_INICIAL.equals(l.getCodigo()))) {
            return Optional.of("«" + i.getNombre() + "» recibió lotes de ingreso después de la vista previa.");
        }
        return Optional.empty();
    }

    @Override
    public void crear(Map<String, String> v) {
        crearGrupo("INSUMO|" + v.get("_insumoId"), List.of(v), "sin-archivo");
    }

    @Override
    public void crearGrupo(String clave, List<Map<String, String>> filas, String huella) {
        UUID insumoId = UUID.fromString(filas.get(0).get("_insumoId"));
        List<InventarioInsumoService.LineaSaldo> lineas = new ArrayList<>();
        for (Map<String, String> f : filas) {
            ProveedorCompra proveedor = null;
            String ruc = f.get(RUC.clave());
            if (ruc != null && !ruc.isEmpty()) {
                proveedor = proveedorRepository.findByRuc(ruc).orElseGet(() -> {
                    var nuevo = proveedorService.crear(new ProveedorCompraRequest(f.get(PROVEEDOR.clave()), ruc));
                    return proveedorRepository.findById(nuevo.id()).orElseThrow();
                });
            }
            String vence = f.get(VENCIMIENTO.clave());
            lineas.add(new InventarioInsumoService.LineaSaldo(new BigDecimal(f.get(CANTIDAD.clave())), vacio(f.get(LOTE.clave())), vacio(f.get(LOTE_PROVEEDOR.clave())),
                    vence.isEmpty() ? null : LocalDate.parse(vence), proveedor));
        }
        String observacion = filas.stream().map(f -> f.get(OBSERVACION.clave())).filter(o -> o != null && !o.isEmpty()).findFirst().orElse(null);
        inventarioInsumoService.regularizarSaldoInicial(insumoId, lineas, observacion);
    }

    private static String vacio(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
