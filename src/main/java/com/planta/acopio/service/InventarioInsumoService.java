package com.planta.acopio.service;

import com.planta.acopio.entity.SalidaInventario;
import com.planta.acopio.dto.AjusteInsumoRequest;
import com.planta.acopio.dto.IngresoInsumoRequest;
import com.planta.acopio.dto.InsumoResponse;
import com.planta.acopio.dto.MovimientoInsumoResponse;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.LoteInsumo;
import com.planta.acopio.entity.LoteProduccion;
import com.planta.acopio.entity.MotivoSalida;
import com.planta.acopio.entity.MovimientoInsumo;
import com.planta.acopio.entity.TipoMovimientoInsumo;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoInactivoException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.MovimientoInsumoRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Unico punto que modifica el stock de insumos (V19). Cada cambio escribe su movimiento en
 * movimiento_insumo en la MISMA transaccion que actualiza insumo.stock: invariante stock = SUM(movimientos).
 * Append-only: no hay metodos para editar ni borrar movimientos; un error se corrige con un ajuste nuevo.
 *
 * Los metodos que se usan dentro de otra operacion (salida por produccion, saldo inicial al crear el insumo)
 * no abren transaccion propia: deben ejecutarse dentro de la del llamador, para que el movimiento y el
 * efecto que lo origina se confirmen o reviertan juntos (mismo criterio que InventarioService).
 */
@Service
public class InventarioInsumoService {

    /** insumo.stock es NUMERIC(12,2). */
    private static final BigDecimal MAX_STOCK = new BigDecimal("9999999999.99");

    private final InsumoRepository insumoRepository;
    private final MovimientoInsumoRepository movimientoRepository;
    private final UsuarioRepository usuarioRepository;
    private final LoteInsumoService loteService;

    public InventarioInsumoService(InsumoRepository insumoRepository, MovimientoInsumoRepository movimientoRepository,
                                   UsuarioRepository usuarioRepository, LoteInsumoService loteService) {
        this.insumoRepository = insumoRepository;
        this.movimientoRepository = movimientoRepository;
        this.usuarioRepository = usuarioRepository;
        this.loteService = loteService;
    }

    /** Inventario de insumos: activos e inactivos que aun conservan stock. */
    @Transactional(readOnly = true)
    public List<InsumoResponse> listarParaInventario() {
        return insumoRepository.listarParaInventario().stream()
                .map(i -> new InsumoResponse(i.getId(), i.getNombre(), i.getUnidadMedida(), i.getActivo(), i.getStock()))
                .toList();
    }

    @Transactional
    public MovimientoInsumoResponse registrarIngreso(UUID insumoId, IngresoInsumoRequest dto) {
        Insumo insumo = buscar(insumoId);
        if (!Boolean.TRUE.equals(insumo.getActivo())) {
            throw new RecursoInactivoException("El insumo '" + insumo.getNombre() + "' está desactivado; reactívalo antes de registrar un ingreso.");
        }
        BigDecimal anterior = bloquearStock(insumoId);
        BigDecimal nuevo = anterior.add(dto.cantidad());
        if (nuevo.compareTo(MAX_STOCK) > 0) {
            throw new ValidacionNegocioException("El ingreso supera el stock máximo registrable para el insumo.");
        }
        insumoRepository.fijarStock(insumoId, nuevo);
        // V43: cada ingreso crea su lote (codigo autogenerado, sin vencimiento mientras el ingreso no lo informe).
        LoteInsumo lote = loteService.crearLote(insumo, dto.cantidad(), null, null, null);
        MovimientoInsumo m = nuevoMovimiento(insumo, TipoMovimientoInsumo.INGRESO, dto.cantidad(), usuarioAutenticado(), lote);
        m.setReferencia(normalizar(dto.referencia()));
        m.setMotivo(normalizar(dto.observacion()));
        return toResponse(movimientoRepository.save(m), anterior, nuevo);
    }

    /**
     * Ajuste por conteo fisico: registra la variacion (contado - sistema) con usuario, fecha y motivo. El
     * stock del sistema se lee bajo bloqueo, para que una produccion o ingreso concurrente nunca quede pisado.
     * Permitido tambien para insumos desactivados (para poder llevar a cero una existencia remanente).
     */
    @Transactional
    public MovimientoInsumoResponse ajustar(UUID insumoId, AjusteInsumoRequest dto) {
        Insumo insumo = buscar(insumoId);
        BigDecimal anterior = bloquearStock(insumoId);
        BigDecimal variacion = dto.cantidadFisica().subtract(anterior);
        if (variacion.signum() == 0) {
            throw new ValidacionNegocioException("El conteo físico coincide con el stock del sistema ("
                    + anterior.stripTrailingZeros().toPlainString() + "); no hay ninguna diferencia que ajustar.");
        }
        insumoRepository.fijarStock(insumoId, dto.cantidadFisica());
        Usuario usuario = usuarioAutenticado();
        String motivo = dto.motivo().trim();
        MovimientoInsumo ultimo = null;
        if (variacion.signum() < 0) {
            // Conteo en contra: sale de los lotes en FEFO (un movimiento por lote). El conteo por lote llega en el paso 7.
            for (LoteInsumoService.Asignacion a : loteService.asignar(insumo, variacion.negate(), LoteInsumoService.Prioridad.RETIRO_GENERAL)) {
                MovimientoInsumo m = nuevoMovimiento(insumo, TipoMovimientoInsumo.AJUSTE, a.cantidad().negate(), usuario, a.lote());
                m.setMotivo(motivo);
                ultimo = movimientoRepository.save(m);
            }
        } else {
            LoteInsumo lote = loteService.loteParaEntradaGeneral(insumo);
            loteService.sumar(lote, variacion);
            MovimientoInsumo m = nuevoMovimiento(insumo, TipoMovimientoInsumo.AJUSTE, variacion, usuario, lote);
            m.setMotivo(motivo);
            ultimo = movimientoRepository.save(m);
        }
        return toResponse(ultimo, anterior, dto.cantidadFisica());
    }

    /**
     * Consumo de insumos de un lote (iniciar una produccion planificada o registrar un lote directo).
     * Descuento atomico y condicionado (sin stock negativo), en orden de id para que dos producciones
     * concurrentes bloqueen las filas siempre en el mismo orden. Si un stock ya no alcanza, la excepcion
     * revierte TODA la transaccion del llamador (leche incluida).
     */
    public void registrarSalidasProduccion(LoteProduccion lote, Map<Insumo, BigDecimal> consumo) {
        if (consumo.isEmpty()) return;
        Usuario usuario = usuarioAutenticado();
        List<Map.Entry<Insumo, BigDecimal>> ordenados = new ArrayList<>(consumo.entrySet());
        ordenados.sort(Comparator.comparing(e -> e.getKey().getId()));
        for (Map.Entry<Insumo, BigDecimal> e : ordenados) {
            if (insumoRepository.descontarStock(e.getKey().getId(), e.getValue()) == 0) {
                throw new ValidacionNegocioException("Stock insuficiente de " + e.getKey().getNombre()
                        + ": cambió mientras se procesaba la producción. Revisa el stock y vuelve a intentarlo.");
            }
            // V43: el consumo sale de lotes concretos (FEFO, sin lotes vencidos): un movimiento por lote usado. El UPDATE de
            // arriba ya tiene bloqueada la fila del insumo, asi que nadie mas toca sus lotes mientras se reparte.
            for (LoteInsumoService.Asignacion a : loteService.asignar(e.getKey(), e.getValue(), LoteInsumoService.Prioridad.CONSUMO)) {
                MovimientoInsumo m = nuevoMovimiento(e.getKey(), TipoMovimientoInsumo.SALIDA_PRODUCCION,
                        a.cantidad().negate(), usuario, a.lote());
                m.setLoteProduccion(lote);
                movimientoRepository.save(m);
            }
        }
    }

    /** Stock con el que nace un insumo nuevo (alta en Catalogos): primer movimiento de su kardex. */
    public void registrarSaldoInicial(Insumo insumo, BigDecimal cantidad) {
        if (cantidad == null || cantidad.signum() <= 0) return;
        LoteInsumo lote = loteService.loteInicial(insumo);
        loteService.sumar(lote, cantidad);
        lote.setCantidadInicial(lote.getCantidadInicial().add(cantidad));
        MovimientoInsumo m = nuevoMovimiento(insumo, TipoMovimientoInsumo.SALDO_INICIAL, cantidad, usuarioAutenticado(), lote);
        m.setMotivo("Stock inicial al crear el insumo");
        movimientoRepository.save(m);
    }

    /** Retiro manual sobre el mismo libro, dentro de la transacción de SalidaInventarioService. */
    public UUID registrarRetiro(SalidaInventario salida) {
        UUID id = salida.getInsumo().getId();
        BigDecimal anterior = bloquearStock(id);
        if (salida.getCantidad().compareTo(anterior) > 0) {
            throw new ValidacionNegocioException("Stock insuficiente para registrar la salida del insumo.");
        }
        insumoRepository.fijarStock(id, anterior.subtract(salida.getCantidad()));
        // V43: la salida sale de lotes (un movimiento por lote). Por vencimiento: primero los lotes vencidos.
        LoteInsumoService.Prioridad prioridad = salida.getTipo() == MotivoSalida.VENCIMIENTO
                ? LoteInsumoService.Prioridad.RETIRO_VENCIDOS_PRIMERO : LoteInsumoService.Prioridad.RETIRO_GENERAL;
        UUID primero = null;
        for (LoteInsumoService.Asignacion a : loteService.asignar(salida.getInsumo(), salida.getCantidad(), prioridad)) {
            MovimientoInsumo m = nuevoMovimiento(salida.getInsumo(),
                    TipoMovimientoInsumo.valueOf("SALIDA_" + salida.getTipo().name()), a.cantidad().negate(), salida.getUsuario(), a.lote());
            m.setMotivo(salida.getMotivo());
            m.setSalida(salida);
            UUID guardado = movimientoRepository.save(m).getId();
            if (primero == null) primero = guardado;
        }
        return primero;
    }

    private Insumo buscar(UUID id) {
        Insumo insumo = insumoRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Insumo no encontrado: " + id));
        if (insumo.estaFusionado()) {
            throw new ConflictoNegocioException("El insumo «" + insumo.getNombre() + "» fue fusionado en otro insumo: registra el movimiento en el insumo que lo absorbió.");
        }
        return insumo;
    }

    private BigDecimal bloquearStock(UUID id) {
        return insumoRepository.bloquearStock(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Insumo no encontrado: " + id));
    }

    private static MovimientoInsumo nuevoMovimiento(Insumo insumo, TipoMovimientoInsumo tipo, BigDecimal cantidad, Usuario usuario,
                                                    LoteInsumo lote) {
        MovimientoInsumo m = new MovimientoInsumo();
        m.setInsumo(insumo);
        m.setLoteInsumo(lote);
        m.setTipoMovimiento(tipo);
        m.setCantidad(cantidad);
        m.setUsuario(usuario);
        return m;
    }

    private Usuario usuarioAutenticado() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));
    }

    private static String normalizar(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }

    private static MovimientoInsumoResponse toResponse(MovimientoInsumo m, BigDecimal anterior, BigDecimal resultante) {
        Insumo i = m.getInsumo();
        return new MovimientoInsumoResponse(m.getId(), i.getId(), i.getNombre(), i.getUnidadMedida(),
                m.getTipoMovimiento().name(), m.getCantidad(), anterior, resultante, m.getReferencia(), m.getMotivo(),
                m.getUsuario() != null ? m.getUsuario().getNombre() : null, m.getCreadoEn());
    }
}
