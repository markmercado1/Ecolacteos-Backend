package com.planta.acopio.service;

import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.DocumentoIngreso;
import com.planta.acopio.entity.LoteInsumo;
import com.planta.acopio.entity.ProveedorCompra;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.LoteInsumoRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Motor de lotes de insumo (V43): crea lotes y reparte consumos y salidas entre ellos. NO abre transaccion propia ni escribe
 * movimientos: lo usa InventarioInsumoService dentro de su transaccion, que ya tiene bloqueada la fila del insumo, y es quien
 * deja el movimiento del kardex por cada lote afectado (un movimiento por lote).
 *
 * Regla de reparto (FEFO): primero el lote que vence antes; los que no tienen vencimiento van despues, en FIFO por fecha de
 * ingreso. Un lote vencido NUNCA se consume en produccion; solo sale por una salida (primero los vencidos si la salida es por
 * vencimiento).
 */
@Service
public class LoteInsumoService {

    /** Cuales lotes y en que orden se usan. */
    public enum Prioridad {
        /** Produccion: solo lotes NO vencidos, FEFO. */
        CONSUMO,
        /** Salida por vencimiento: primero los vencidos, luego el resto en FEFO. */
        RETIRO_VENCIDOS_PRIMERO,
        /** Otras salidas y ajustes en contra: primero los vigentes en FEFO, luego los vencidos. */
        RETIRO_GENERAL
    }

    /** Parte de una operacion que sale de un lote. */
    public record Asignacion(LoteInsumo lote, BigDecimal cantidad) {}

    private static final DateTimeFormatter FECHA_CODIGO = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final LoteInsumoRepository repository;
    private final ReglaVencimiento reglaVencimiento;

    public LoteInsumoService(LoteInsumoRepository repository, ReglaVencimiento reglaVencimiento) {
        this.repository = repository;
        this.reglaVencimiento = reglaVencimiento;
    }

    /** Lote nuevo de un ingreso. `codigo` en blanco = se autogenera (insumo + fecha + secuencia); si se indica debe ser unico en el insumo. */
    public LoteInsumo crearLote(Insumo insumo, BigDecimal cantidad, String codigo, String codigoProveedor, LocalDate vencimiento) {
        return crearLote(insumo, cantidad, codigo, codigoProveedor, vencimiento, null, null);
    }

    /** Igual, con el proveedor y el documento de ingreso de origen (V44). Un ingreso nuevo no puede traer un vencimiento ya pasado. */
    public LoteInsumo crearLote(Insumo insumo, BigDecimal cantidad, String codigo, String codigoProveedor, LocalDate vencimiento,
                                ProveedorCompra proveedor, DocumentoIngreso documento) {
        if (cantidad == null || cantidad.signum() <= 0) {
            throw new ValidacionNegocioException("La cantidad de un lote debe ser mayor a 0.");
        }
        LocalDate hoy = reglaVencimiento.hoy();
        if (vencimiento != null && vencimiento.isBefore(hoy)) {
            throw new ValidacionNegocioException("El vencimiento del lote de " + insumo.getNombre() + " (" + vencimiento
                    + ") ya pasó: un ingreso no puede traer un lote vencido.");
        }
        String codigoFinal = codigo == null || codigo.isBlank() ? generarCodigo(insumo, hoy) : limpiarCodigo(codigo);
        if (repository.existsByInsumoIdAndCodigo(insumo.getId(), codigoFinal)) {
            throw new ConflictoNegocioException("Ya existe un lote «" + codigoFinal + "» de " + insumo.getNombre() + ". Usa otro código.");
        }
        LoteInsumo lote = new LoteInsumo();
        lote.setInsumo(insumo);
        lote.setCodigo(codigoFinal);
        lote.setCodigoProveedor(codigoProveedor == null || codigoProveedor.isBlank() ? null : codigoProveedor.trim());
        lote.setFechaIngreso(hoy);
        lote.setFechaVencimiento(vencimiento);
        lote.setProveedor(proveedor);
        lote.setDocumentoIngreso(documento);
        lote.setCantidadInicial(cantidad);
        lote.setCantidadDisponible(cantidad);
        lote.setOrigen(LoteInsumo.Origen.INGRESO);
        return repository.save(lote);
    }

    /** El lote de arrastre del insumo; se crea (en 0) si todavia no existe. Su cantidad la mueven los movimientos. */
    public LoteInsumo loteInicial(Insumo insumo) {
        return repository.findByInsumoIdAndCodigo(insumo.getId(), LoteInsumo.CODIGO_INICIAL).orElseGet(() -> {
            LoteInsumo lote = new LoteInsumo();
            lote.setInsumo(insumo);
            lote.setCodigo(LoteInsumo.CODIGO_INICIAL);
            lote.setFechaIngreso(reglaVencimiento.hoy());
            lote.setCantidadInicial(BigDecimal.ZERO);
            lote.setCantidadDisponible(BigDecimal.ZERO);
            lote.setOrigen(LoteInsumo.Origen.INICIAL);
            return repository.save(lote);
        });
    }

    /** Destino de un conteo a favor mientras el conteo no es por lote: el lote mas reciente, o el INICIAL si no hay ninguno. */
    public LoteInsumo loteParaEntradaGeneral(Insumo insumo) {
        return repository.masReciente(insumo.getId()).orElseGet(() -> loteInicial(insumo));
    }

    /** Suma al disponible de un lote (entrada). */
    public void sumar(LoteInsumo lote, BigDecimal cantidad) {
        lote.setCantidadDisponible(lote.getCantidadDisponible().add(cantidad));
    }

    /** Resta del disponible de UN lote concreto. */
    public void descontar(LoteInsumo lote, BigDecimal cantidad) {
        if (lote.getCantidadDisponible().compareTo(cantidad) < 0) {
            throw new ValidacionNegocioException("El lote «" + lote.getCodigo() + "» solo tiene " + plano(lote.getCantidadDisponible()) + " disponible.");
        }
        lote.setCantidadDisponible(lote.getCantidadDisponible().subtract(cantidad));
    }

    /**
     * Reparte `cantidad` entre los lotes segun la prioridad y DESCUENTA de cada uno lo asignado. Si no alcanza lanza una
     * excepcion (la transaccion del llamador revierte todo). Debe llamarse con la fila del insumo ya bloqueada.
     */
    public List<Asignacion> asignar(Insumo insumo, BigDecimal cantidad, Prioridad prioridad) {
        LocalDate hoy = reglaVencimiento.hoy();
        List<LoteInsumo> fefo = repository.disponiblesFefo(insumo.getId());
        List<LoteInsumo> vigentes = fefo.stream().filter(l -> !estaVencido(l, hoy)).toList();
        List<LoteInsumo> vencidos = fefo.stream().filter(l -> estaVencido(l, hoy)).toList();
        List<LoteInsumo> candidatos = new ArrayList<>();
        switch (prioridad) {
            case CONSUMO -> candidatos.addAll(vigentes);
            case RETIRO_VENCIDOS_PRIMERO -> { candidatos.addAll(vencidos); candidatos.addAll(vigentes); }
            case RETIRO_GENERAL -> { candidatos.addAll(vigentes); candidatos.addAll(vencidos); }
        }
        List<Asignacion> asignaciones = new ArrayList<>();
        BigDecimal restante = cantidad;
        for (LoteInsumo lote : candidatos) {
            if (restante.signum() == 0) break;
            BigDecimal toma = restante.min(lote.getCantidadDisponible());
            lote.setCantidadDisponible(lote.getCantidadDisponible().subtract(toma));
            asignaciones.add(new Asignacion(lote, toma));
            restante = restante.subtract(toma);
        }
        if (restante.signum() > 0) {
            BigDecimal disponible = candidatos.stream().map(LoteInsumo::getCantidadDisponible).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .add(asignaciones.stream().map(Asignacion::cantidad).reduce(BigDecimal.ZERO, BigDecimal::add));
            String vencido = prioridad == Prioridad.CONSUMO && !vencidos.isEmpty()
                    ? " (hay " + plano(vencidos.stream().map(LoteInsumo::getCantidadDisponible).reduce(BigDecimal.ZERO, BigDecimal::add))
                    + " " + insumo.getUnidadMedida() + " en lotes vencidos, que no se consumen)" : "";
            throw new ValidacionNegocioException("Stock insuficiente de " + insumo.getNombre() + ": se necesitan " + plano(cantidad)
                    + " " + insumo.getUnidadMedida() + " y solo hay " + plano(disponible) + " disponible" + vencido
                    + ". Revisa el stock y vuelve a intentarlo.");
        }
        return asignaciones;
    }

    /** Stock que se puede consumir en produccion: lotes con existencia que no estan vencidos. */
    public BigDecimal stockConsumible(Insumo insumo) {
        LocalDate hoy = reglaVencimiento.hoy();
        return repository.disponiblesFefo(insumo.getId()).stream().filter(l -> !estaVencido(l, hoy))
                .map(LoteInsumo::getCantidadDisponible).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Vencido = su ultimo dia valido ya paso (ReglaVencimiento). Sin fecha de vencimiento nunca vence. */
    public boolean estaVencido(LoteInsumo lote, LocalDate hoy) {
        return lote.getFechaVencimiento() != null && lote.getFechaVencimiento().isBefore(hoy);
    }

    // ------------------------------------------------------------------------------------------------ codigos

    /** "AZU-20261029-01": tres letras del insumo, fecha de ingreso y secuencia del dia. Editable por quien registra el ingreso. */
    String generarCodigo(Insumo insumo, LocalDate fecha) {
        String letras = BusquedaTexto.clave(insumo.getNombre()).replaceAll("[^a-z]", "").toUpperCase();
        String prefijo = letras.isEmpty() ? "INS" : letras.substring(0, Math.min(3, letras.length()));
        for (int n = 1; n < 1000; n++) {
            String candidato = prefijo + "-" + FECHA_CODIGO.format(fecha) + "-" + String.format("%02d", n);
            if (!repository.existsByInsumoIdAndCodigo(insumo.getId(), candidato)) {
                return candidato;
            }
        }
        throw new ConflictoNegocioException("No se pudo generar un código de lote; indícalo manualmente.");
    }

    private static String limpiarCodigo(String codigo) {
        String limpio = codigo.trim().replaceAll("\\s+", " ");
        if (limpio.length() > 40) {
            throw new ValidacionNegocioException("El código de lote admite hasta 40 caracteres.");
        }
        return limpio;
    }

    private static String plano(BigDecimal valor) {
        return valor.stripTrailingZeros().toPlainString();
    }
}
