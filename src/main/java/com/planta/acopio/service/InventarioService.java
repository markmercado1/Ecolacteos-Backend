package com.planta.acopio.service;

import com.planta.acopio.entity.SalidaInventario;
import com.planta.acopio.entity.MotivoSalida;
import com.planta.acopio.dto.ExistenciasPresentacionResponse;
import com.planta.acopio.dto.FilaStockResponse;
import com.planta.acopio.dto.LoteExistenciaResponse;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.dto.StockPresentacionResponse;
import com.planta.acopio.entity.AjusteInventario;
import com.planta.acopio.entity.AsignacionLote;
import com.planta.acopio.entity.IncidenciaCobertura;
import com.planta.acopio.entity.LoteProduccionDetalle;
import com.planta.acopio.entity.MovimientoInventario;
import com.planta.acopio.entity.TipoMovimientoInventario;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.VentaDetalle;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.AsignacionLoteRepository;
import com.planta.acopio.repository.IncidenciaCoberturaRepository;
import com.planta.acopio.repository.LoteProduccionDetalleRepository;
import com.planta.acopio.repository.MovimientoInventarioRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.RegularizacionTrazabilidadRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

// Libro mayor de movimientos de inventario (V7__inventario_precio_queso.sql). El stock nunca se escribe
// directamente por un endpoint: solo cambia como efecto de una produccion o una venta validas (ver
// LoteProduccionService.crear() y VentaService.registrarOIgnorarSiDuplicado()). Por eso los metodos de
// escritura de aqui NO declaran @Transactional propio -- deben ejecutarse SIEMPRE dentro de la
// transaccion ya abierta por quien los invoca (ver comentario historico completo en git).
//
// Fase 3 (Produccion con varias presentaciones de salida e Inventario por presentacion): el stock pasa de
// calcularse por PRODUCTO a calcularse por PRESENTACION. Una entrada de produccion ahora se registra POR
// DETALLE (un lote con 3 salidas genera 3 movimientos, cada uno ligado a su LoteProduccionDetalle, nunca al
// LoteProduccion directamente) -- eso es lo que permite que el indice unico admita varias entradas por lote
// sin duplicar la misma presentacion dos veces (ver V12__produccion_multi_presentacion.sql).
@Service
public class InventarioService {

    private final MovimientoInventarioRepository movimientoRepository;
    private final PresentacionRepository presentacionRepository;
    private final AsignacionLoteRepository asignacionLoteRepository;
    private final LoteProduccionDetalleRepository detalleRepository;
    private final RegularizacionTrazabilidadRepository regularizacionRepository;
    private final ReglaVencimiento reglaVencimiento;
    private final ReservaCompromisos reservaCompromisos;
    private final IncidenciaCoberturaRepository incidenciaRepository;

    public InventarioService(MovimientoInventarioRepository movimientoRepository, PresentacionRepository presentacionRepository,
                              AsignacionLoteRepository asignacionLoteRepository, LoteProduccionDetalleRepository detalleRepository,
                              RegularizacionTrazabilidadRepository regularizacionRepository, ReglaVencimiento reglaVencimiento,
                              ReservaCompromisos reservaCompromisos, IncidenciaCoberturaRepository incidenciaRepository) {
        this.movimientoRepository = movimientoRepository;
        this.presentacionRepository = presentacionRepository;
        this.asignacionLoteRepository = asignacionLoteRepository;
        this.detalleRepository = detalleRepository;
        this.regularizacionRepository = regularizacionRepository;
        this.reglaVencimiento = reglaVencimiento;
        this.reservaCompromisos = reservaCompromisos;
        this.incidenciaRepository = incidenciaRepository;
    }

    // ------------------------------------------------------------------------------------------------
    // Fase 1 (control de vencimiento, V21) y Fase 2 (FEFO, V22). TODO escritor del libro de producto terminado
    // pasa por este servicio (solo hay tres: entrada de produccion, salida de venta y ajuste). Para una
    // presentacion cuyo producto CONTROLA vencimiento, cada movimiento se reparte en movimiento_inventario_lote
    // dentro de la misma transaccion, de modo que  stock del libro = saldo inicial + SUM(reparto):
    //   - entrada de produccion: la cantidad completa a su lote (Fase 1);
    //   - venta: FEFO sobre los lotes VIGENTES (vence antes, sale antes); lo vencido y lo "sin trazabilidad" no se vende;
    //   - ajuste positivo: a "sin trazabilidad"; ajuste negativo: primero lo vencido, luego "sin trazabilidad" y
    //     al final los lotes vigentes en FEFO (se da de baja antes lo que ya no se puede vender);
    //   - la entrada por el camino legado (sin fecha) se RECHAZA.
    // Para un producto sin control (el unico caso real mientras la activacion este deshabilitada) nada de esto
    // cambia.
    //
    // El orden importa: PRIMERO el bloqueo de la fila de Presentacion y DESPUES la lectura del indicador y de las
    // existencias, y la lectura del indicador es ESCALAR (no la de una entidad posiblemente desactualizada). Asi
    // la activacion/desactivacion del control y la regularizacion, que toman el mismo bloqueo, no pueden
    // intercalarse con una escritura del libro.
    // ------------------------------------------------------------------------------------------------

    /** Bloquea las presentaciones EN ORDEN DE ID (mismo orden que VentaService) para evitar interbloqueos. */
    public void bloquearPresentaciones(Collection<UUID> presentacionIds) {
        presentacionIds.stream().distinct().sorted()
                .forEach(presentacionRepository::buscarConBloqueoParaActualizarStock);
    }

    /** Lectura escalar y confirmada; llamar DESPUES de bloquear la presentacion. */
    public boolean controlaVencimiento(UUID presentacionId) {
        return presentacionRepository.controlaVencimientoDe(presentacionId).orElse(false);
    }

    private void exigirNoControlada(UUID presentacionId, String operacion) {
        presentacionRepository.buscarConBloqueoParaActualizarStock(presentacionId);
        if (controlaVencimiento(presentacionId)) {
            throw new ValidacionNegocioException(operacion + " todavía no está habilitado para productos con control de "
                    + "vencimiento. No se registró ningún movimiento.");
        }
    }

    public void registrarEntradaProduccion(LoteProduccionDetalle detalle) {
        exigirNoControlada(detalle.getPresentacion().getId(), "El ingreso de producción por este camino");
        crearEntrada(detalle);
    }

    /**
     * Entrada de produccion de un producto CON control de vencimiento: el movimiento y su reparto en el lote
     * (cantidad completa a ese lote) se escriben juntos, en la transaccion del llamador. La fecha de
     * vencimiento ya debe estar registrada en el detalle por quien lo creo.
     */
    public void registrarEntradaProduccionTrazada(LoteProduccionDetalle detalle) {
        UUID presentacionId = detalle.getPresentacion().getId();
        presentacionRepository.buscarConBloqueoParaActualizarStock(presentacionId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Presentacion no encontrada: " + presentacionId));
        if (!controlaVencimiento(presentacionId)) {
            throw new ValidacionNegocioException("La presentación '" + detalle.getPresentacion().getNombre()
                    + "' no pertenece a un producto con control de vencimiento.");
        }
        if (detalle.getFechaVencimiento() == null) {
            throw new ValidacionNegocioException("Falta la fecha de vencimiento de '" + detalle.getPresentacion().getNombre() + "'.");
        }
        if (presentacionRepository.saldoSinTrazabilidadInicialDe(presentacionId).isEmpty()) {
            throw new ValidacionNegocioException("La presentación '" + detalle.getPresentacion().getNombre()
                    + "' no tiene saldo inicial de trazabilidad. Desactiva y vuelve a activar el control de vencimiento del producto.");
        }
        MovimientoInventario movimiento = crearEntrada(detalle);
        asignacionLoteRepository.save(new AsignacionLote(movimiento, detalle, detalle.getCantidadObtenida()));
    }

    private MovimientoInventario crearEntrada(LoteProduccionDetalle detalle) {
        MovimientoInventario movimiento = new MovimientoInventario();
        movimiento.setPresentacion(detalle.getPresentacion());
        movimiento.setTipoMovimiento(TipoMovimientoInventario.ENTRADA_PRODUCCION);
        movimiento.setCantidad(detalle.getCantidadObtenida());
        movimiento.setLoteProduccionDetalle(detalle);
        movimientoRepository.save(movimiento);
        return movimiento;
    }

    // Bloqueo pesimista (SELECT ... FOR UPDATE) sobre la fila de Presentacion: mientras esta transaccion no
    // termine (commit o rollback), cualquier otra venta concurrente de la MISMA presentacion queda
    // bloqueada en este mismo punto -- al desbloquearse, vuelve a calcular el stock ya con el descuento de
    // la primera venta aplicado (READ COMMITTED). Con 10 unidades y dos ventas simultaneas de 7: una se
    // ejecuta, la otra ve 3 disponibles y es rechazada -- nunca -4, nunca dos ventas consumiendo las mismas
    // unidades. Dos presentaciones DISTINTAS del mismo producto nunca se bloquean entre si (stock
    // independiente por tamaño, ver PresentacionRepository.buscarConBloqueoParaActualizarStock).
    //
    // Fase 2: para una presentacion CONTROLADA lo que se compara es el stock VENDIBLE (lotes vigentes), no el
    // fisico: lo vencido y lo "sin trazabilidad" no se pueden vender.
    public void validarStockSuficiente(UUID presentacionId, int cantidadSolicitada) {
        Presentacion presentacion = presentacionRepository.buscarConBloqueoParaActualizarStock(presentacionId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Presentacion no encontrada: " + presentacionId));
        if (controlaVencimiento(presentacionId)) {
            Existencias e = calcularExistencias(presentacionId);
            if (cantidadSolicitada > e.vendible()) {
                throw new ValidacionNegocioException(
                        "Stock vendible insuficiente de " + presentacion.getNombre() + ": vendibles " + e.vendible()
                                + ", solicitadas " + cantidadSolicitada + " (en inventario hay " + e.fisico()
                                + "; no vendibles: " + e.vencido() + " vencidas y " + e.sinTrazabilidad() + " sin trazabilidad)");
            }
            exigirCoberturaDeReservas(presentacion, e, cantidadSolicitada);
            return;
        }
        long stockActual = movimientoRepository.sumarCantidadPorPresentacion(presentacionId);
        if (cantidadSolicitada > stockActual) {
            throw new ValidacionNegocioException(
                    "Stock insuficiente de " + presentacion.getNombre() + ": disponibles " + stockActual
                            + ", solicitadas " + cantidadSolicitada);
        }
    }

    // Fase 4 (Venta multi-linea / carrito): una venta con N lineas genera N movimientos, cada uno ligado a
    // su VentaDetalle (nunca a la Venta directamente) -- mismo criterio exacto que registrarEntradaProduccion
    // con LoteProduccionDetalle.
    //
    // Fase 2: en una presentacion controlada el movimiento se reparte FEFO entre los lotes vigentes.
    public void registrarSalidaVenta(VentaDetalle detalle) {
        UUID presentacionId = detalle.getPresentacion().getId();
        presentacionRepository.buscarConBloqueoParaActualizarStock(presentacionId);
        if (!controlaVencimiento(presentacionId)) {
            movimientoRepository.save(nuevoMovimiento(detalle.getPresentacion(), TipoMovimientoInventario.SALIDA_VENTA,
                    -detalle.getCantidad(), m -> m.setVentaDetalle(detalle)));
            return;
        }
        // Las existencias se leen ANTES de crear el movimiento (el movimiento aun no existe en el libro) y bajo
        // el bloqueo de la presentacion; se recalculan aqui aunque validarStockSuficiente ya lo hizo, porque esta
        // es la autoridad que reparte el movimiento.
        Existencias e = calcularExistencias(presentacionId);
        int cantidad = detalle.getCantidad();
        if (cantidad > e.vendible()) {
            throw new ValidacionNegocioException("Stock vendible insuficiente de " + detalle.getPresentacion().getNombre()
                    + ": vendibles " + e.vendible() + ", solicitadas " + cantidad + ". No se registró ningún movimiento.");
        }
        exigirCoberturaDeReservas(detalle.getPresentacion(), e, cantidad);
        MovimientoInventario movimiento = movimientoRepository.save(nuevoMovimiento(detalle.getPresentacion(),
                TipoMovimientoInventario.SALIDA_VENTA, -cantidad, m -> m.setVentaDetalle(detalle)));
        List<Cupo> vigentes = e.lotes().stream().filter(l -> !l.vencido()).map(InventarioService::cupoDe).toList();
        repartirSalida(movimiento, vigentes, cantidad);
    }

    // Inventario: accion "Ajustar stock" (V14__ajuste_inventario.sql). El delta ya viene calculado por
    // AjusteInventarioService (cantidadCorregida - cantidadAnterior, bajo el mismo bloqueo pesimista que usa
    // validarStockSuficiente) -- este metodo solo escribe el movimiento compensatorio, nunca reinterpreta ni
    // recalcula nada. Nunca ligado a un lote ni a una venta (ver chk_movimiento_ajuste).
    //
    // Fase 2: en una presentacion controlada el delta se reparte (ver la cabecera de la seccion).
    public void registrarAjuste(AjusteInventario ajuste, int delta) {
        UUID presentacionId = ajuste.getPresentacion().getId();
        presentacionRepository.buscarConBloqueoParaActualizarStock(presentacionId);
        if (!controlaVencimiento(presentacionId)) {
            movimientoRepository.save(nuevoMovimiento(ajuste.getPresentacion(), TipoMovimientoInventario.AJUSTE,
                    delta, m -> m.setAjusteInventario(ajuste)));
            return;
        }
        Existencias e = calcularExistencias(presentacionId); // antes de crear el movimiento
        if (delta < 0 && -delta > e.fisico()) {
            throw new ValidacionNegocioException("El ajuste descuenta " + (-delta) + " unidades pero solo hay " + e.fisico()
                    + " en inventario. No se registró ningún movimiento.");
        }
        MovimientoInventario movimiento = movimientoRepository.save(nuevoMovimiento(ajuste.getPresentacion(),
                TipoMovimientoInventario.AJUSTE, delta, m -> m.setAjusteInventario(ajuste)));
        if (delta > 0) {
            // Un sobrante hallado al contar no tiene fecha de vencimiento conocida: entra como "sin trazabilidad"
            // (no vendible hasta regularizarlo).
            asignacionLoteRepository.save(AsignacionLote.sinTrazabilidad(movimiento, delta));
            return;
        }
        // Una baja se descuenta primero de lo que ya no se puede vender: lotes vencidos, luego lo sin
        // trazabilidad, y solo al final los lotes vigentes en FEFO.
        List<Cupo> orden = new ArrayList<>();
        e.lotes().stream().filter(Existencias.Lote::vencido).map(InventarioService::cupoDe).forEach(orden::add);
        if (e.sinTrazabilidad() > 0) orden.add(new Cupo(null, null, e.sinTrazabilidad()));
        e.lotes().stream().filter(l -> !l.vencido()).map(InventarioService::cupoDe).forEach(orden::add);
        repartirSalida(movimiento, orden, -delta);
        registrarIncidenciaSiQuedaronReservasSinCobertura(ajuste, presentacionId);
    }

    /** Retiro explícito: la transacción y el bloqueo de presentación pertenecen a SalidaInventarioService. */
    public UUID registrarRetiro(SalidaInventario salida) {
        UUID id = salida.getPresentacion().getId();
        int cantidad;
        try { cantidad = salida.getCantidad().intValueExact(); }
        catch (ArithmeticException ex) { throw new ValidacionNegocioException("Las unidades de producto deben ser enteras."); }
        List<Cupo> cupos = new ArrayList<>();
        if (controlaVencimiento(id)) {
            Existencias e = calcularExistencias(id);
            if (salida.getLoteId() == null) {
                throw new ValidacionNegocioException("Selecciona el lote del que se retiran las unidades.");
            }
            Existencias.Lote lote = e.lotes().stream()
                    .filter(l -> l.id().equals(salida.getLoteId()) && l.origen().name().equals(salida.getOrigenLote()))
                    .findFirst().orElseThrow(() -> new ValidacionNegocioException("El lote no pertenece a esta presentación o ya no tiene saldo."));
            if (cantidad > lote.saldo()) throw new ValidacionNegocioException("La salida supera las unidades disponibles del lote.");
            if (salida.getTipo() == MotivoSalida.VENCIMIENTO && !lote.vencido()) {
                throw new ValidacionNegocioException("El lote todavía no está vencido.");
            }
            salida.setFechaVencimiento(lote.fechaVencimiento());
            cupos.add(cupoDe(lote));
        } else {
            if (salida.getLoteId() != null || salida.getOrigenLote() != null) {
                throw new ValidacionNegocioException("Esta presentación no tiene control de lotes habilitado.");
            }
            if (salida.getTipo() == MotivoSalida.VENCIMIENTO) {
                throw new ValidacionNegocioException("No se puede verificar vencimiento sin un lote con fecha registrada.");
            }
            if (cantidad > movimientoRepository.sumarCantidadPorPresentacion(id)) {
                throw new ValidacionNegocioException("Stock insuficiente para registrar la salida.");
            }
        }
        MovimientoInventario m = movimientoRepository.save(nuevoMovimiento(salida.getPresentacion(),
                TipoMovimientoInventario.valueOf("SALIDA_" + salida.getTipo().name()), -cantidad, mov -> mov.setSalida(salida)));
        if (!cupos.isEmpty()) {
            repartirSalida(m, cupos, cantidad);
            var demandas = reservaCompromisos.demandasDe(id);
            int faltante = CoberturaReservas.faltante(CoberturaReservas.lotesVendibles(calcularExistencias(id)), demandas);
            if (faltante > 0) incidenciaRepository.save(new IncidenciaCobertura(salida.getPresentacion(), null, faltante, reglaVencimiento.ahora()));
        }
        return m.getId();
    }

    // ------------------------------------------------------------------------------------------------
    // Fase 4: reservas. Una reserva compromete unidades SIN reducir el stock fisico ni tocar el libro. Una venta directa
    // no puede dejar sin cobertura a reservas validas; un ajuste por perdida fisica real SI se registra siempre (no se
    // puede ocultar un faltante), pero deja una incidencia para resolver las reservas afectadas.
    // ------------------------------------------------------------------------------------------------

    /** Rechaza la venta si, tras consumirla en FEFO, alguna reserva vigente quedaria sin lotes que la respalden. */
    private void exigirCoberturaDeReservas(Presentacion presentacion, Existencias e, int cantidad) {
        List<CoberturaReservas.Demanda> demandas = reservaCompromisos.demandasDe(presentacion.getId());
        if (demandas.isEmpty()) return;
        if (!CoberturaReservas.cubreTrasVender(CoberturaReservas.lotesVendibles(e), demandas, cantidad)) {
            int comprometidas = demandas.stream().mapToInt(CoberturaReservas.Demanda::cantidad).sum();
            throw new ValidacionNegocioException("Stock comprometido por reservas de " + presentacion.getNombre() + ": vendibles "
                    + e.vendible() + ", reservadas " + comprometidas + ", solicitadas " + cantidad
                    + ". Esas unidades están separadas para clientes con reserva vigente. No se registró ningún movimiento.");
        }
    }

    private void registrarIncidenciaSiQuedaronReservasSinCobertura(AjusteInventario ajuste, UUID presentacionId) {
        List<CoberturaReservas.Demanda> demandas = reservaCompromisos.demandasDe(presentacionId);
        if (demandas.isEmpty()) return;
        int faltante = CoberturaReservas.faltante(CoberturaReservas.lotesVendibles(calcularExistencias(presentacionId)), demandas);
        if (faltante > 0) {
            incidenciaRepository.save(new IncidenciaCobertura(ajuste.getPresentacion(), ajuste, faltante, reglaVencimiento.ahora()));
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Fase 2: existencias por lote y reparto de salidas.
    // ------------------------------------------------------------------------------------------------

    /** Un cajon del que se puede descontar: un lote (origen LOTE/REGULARIZACION + id) o "sin trazabilidad" (origen null). */
    private record Cupo(Existencias.Origen origen, UUID id, int saldo) {}

    private static Cupo cupoDe(Existencias.Lote l) {
        return new Cupo(l.origen(), l.id(), l.saldo());
    }

    private MovimientoInventario nuevoMovimiento(Presentacion presentacion, TipoMovimientoInventario tipo, int cantidad,
                                                  Consumer<MovimientoInventario> vinculo) {
        MovimientoInventario movimiento = new MovimientoInventario();
        movimiento.setPresentacion(presentacion);
        movimiento.setTipoMovimiento(tipo);
        movimiento.setCantidad(cantidad);
        vinculo.accept(movimiento);
        return movimiento;
    }

    /** Reparte una salida (cantidad positiva) entre los cupos, en el orden dado: una asignacion negativa por cupo usado. */
    private void repartirSalida(MovimientoInventario movimiento, List<Cupo> orden, int cantidad) {
        int restante = cantidad;
        for (Cupo cupo : orden) {
            if (restante == 0) break;
            int tomar = Math.min(cupo.saldo(), restante);
            if (tomar <= 0) continue;
            AsignacionLote asignacion;
            if (cupo.origen() == null) {
                asignacion = AsignacionLote.sinTrazabilidad(movimiento, -tomar);
            } else if (cupo.origen() == Existencias.Origen.LOTE) {
                asignacion = new AsignacionLote(movimiento, detalleRepository.getReferenceById(cupo.id()), -tomar);
            } else {
                asignacion = new AsignacionLote(movimiento, regularizacionRepository.getReferenceById(cupo.id()), -tomar);
            }
            asignacionLoteRepository.save(asignacion);
            restante -= tomar;
        }
        if (restante != 0) {
            // Ya se valido contra el total disponible; llegar aqui es una inconsistencia, nunca un caso de negocio.
            throw new IllegalStateException("No se pudo repartir la salida del movimiento " + movimiento.getId()
                    + ": faltaron " + restante + " unidades de " + cantidad + ".");
        }
    }

    /**
     * Desglose de las existencias de una presentacion CONTROLADA. Para decidir una escritura debe llamarse DESPUES
     * de bloquear la fila de la presentacion. Verifica la invariante del reparto y falla cerrado si no cuadra.
     */
    public Existencias calcularExistencias(UUID presentacionId) {
        long fisico = movimientoRepository.sumarCantidadPorPresentacion(presentacionId);
        Integer inicial = presentacionRepository.saldoSinTrazabilidadInicialDe(presentacionId).orElse(null);
        if (inicial == null) {
            throw new IllegalStateException("La presentación " + presentacionId + " controla vencimiento pero no tiene saldo inicial de trazabilidad.");
        }
        long sinTrazabilidad = inicial + asignacionLoteRepository.sumarSinTrazabilidadPorPresentacion(presentacionId)
                - regularizacionRepository.sumarPorPresentacion(presentacionId);

        LocalDate hoy = reglaVencimiento.hoy();
        List<Existencias.Lote> lotes = new ArrayList<>();
        long sumaLotes = 0;
        long vencido = 0;
        long vendible = 0;
        for (Object[] fila : asignacionLoteRepository.listarExistenciasPorLote(presentacionId)) {
            Existencias.Origen origen = Existencias.Origen.valueOf((String) fila[0]);
            LocalDate fechaVencimiento = aFecha(fila[4]);
            int saldo = ((Number) fila[5]).intValue();
            if (fechaVencimiento == null || saldo < 0) {
                throw new IllegalStateException("Lote inconsistente en la presentación " + presentacionId + ": " + origen + " " + fila[1]
                        + " con saldo " + saldo + " y vencimiento " + fechaVencimiento + ".");
            }
            boolean estaVencido = fechaVencimiento.isBefore(hoy); // el dia de vencimiento es inclusive
            lotes.add(new Existencias.Lote(origen, (UUID) fila[1], (UUID) fila[2], aFecha(fila[3]), fechaVencimiento, saldo, estaVencido));
            sumaLotes += saldo;
            if (estaVencido) vencido += saldo; else vendible += saldo;
        }
        if (sinTrazabilidad < 0 || fisico != sinTrazabilidad + sumaLotes) {
            throw new IllegalStateException("El reparto por lote de la presentación " + presentacionId + " no cuadra con el libro: libro "
                    + fisico + ", sin trazabilidad " + sinTrazabilidad + ", lotes " + sumaLotes + ".");
        }
        return new Existencias(Math.toIntExact(fisico), Math.toIntExact(sinTrazabilidad), Math.toIntExact(vencido),
                Math.toIntExact(vendible), List.copyOf(lotes));
    }

    private static LocalDate aFecha(Object valor) {
        if (valor == null) return null;
        if (valor instanceof LocalDate f) return f;
        if (valor instanceof java.sql.Date d) return d.toLocalDate();
        if (valor instanceof java.util.Date d) return new java.sql.Date(d.getTime()).toLocalDate();
        throw new IllegalStateException("Tipo de fecha inesperado: " + valor.getClass());
    }

    /**
     * Existencias de una presentacion para la web (solo lectura, sin bloqueo). Instantanea consistente (REPEATABLE
     * READ): las varias consultas ven el mismo estado aunque una venta confirme en medio.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExistenciasPresentacionResponse obtenerExistencias(UUID presentacionId) {
        if (!presentacionRepository.existsById(presentacionId)) {
            throw new RecursoNoEncontradoException("Presentacion no encontrada: " + presentacionId);
        }
        LocalDate hoy = reglaVencimiento.hoy();
        if (!controlaVencimiento(presentacionId)) {
            int fisico = (int) movimientoRepository.sumarCantidadPorPresentacion(presentacionId);
            return new ExistenciasPresentacionResponse(presentacionId, false, fisico, fisico, 0, 0, hoy, List.of());
        }
        Existencias e = calcularExistencias(presentacionId);
        List<LoteExistenciaResponse> lotes = e.lotes().stream()
                .map(l -> new LoteExistenciaResponse(l.origen().name(), l.id(), l.loteProduccionId(), l.fechaLote(),
                        l.fechaVencimiento(), l.saldo(), l.vencido(), ChronoUnit.DAYS.between(hoy, l.fechaVencimiento())))
                .toList();
        return new ExistenciasPresentacionResponse(presentacionId, true, e.fisico(), e.vendible(), e.vencido(),
                e.sinTrazabilidad(), hoy, lotes);
    }

    // Consulta puntual usada por VentaFormDialog al seleccionar una presentacion (sin bloqueo, informativa
    // -- la autoridad real es validarStockSuficiente al confirmar). Nunca lanza si la presentacion no
    // existe: devuelve stock 0 (ese caso ya se rechaza al resolver la presentacion en otro punto del flujo).
    public StockPresentacionResponse obtenerStockDePresentacion(UUID presentacionId) {
        return new StockPresentacionResponse(presentacionId, (int) movimientoRepository.sumarCantidadPorPresentacion(presentacionId));
    }

    // Fase 3 (ampliacion: pantalla /inventario reorganizada por familia con paginacion real, mismo patron
    // que PrecioQuesoService.listarPaginado de Fase 2). familiaId/busqueda se aplican en el WHERE de la
    // query nativa, antes de paginar -- nunca se descarga el catalogo completo para filtrar en el cliente.
    //
    // Fase 2: cada fila lleva ademas si el producto controla vencimiento y su stock VENDIBLE (para un producto
    // sin control, igual al fisico). Instantanea consistente: el vendible se calcula con varias consultas.
    // Fase 5: y, para los productos con control, lo COMPROMETIDO por reservas vigentes y lo LIBRE para una venta directa
    // (la misma regla de cobertura que aplica la venta; el panel solo lo muestra).
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PaginaResponse<FilaStockResponse> listarStockPaginado(UUID familiaId, String busqueda, Pageable pageable) {
        Page<Object[]> filas = presentacionRepository.buscarFilasStock(familiaId, BusquedaTexto.patronLike(busqueda), pageable);
        return PaginaResponse.de(filas.map(fila -> {
            UUID presentacionId = (UUID) fila[5];
            int stock = ((Number) fila[9]).intValue();
            boolean controla = (boolean) fila[10];
            int vendible = stock;
            int comprometido = 0;
            int libre = stock;
            if (controla) {
                // Solo los productos con control de vencimiento pueden reservarse (Fase 4).
                Existencias e = calcularExistencias(presentacionId);
                var demandas = reservaCompromisos.demandasDe(presentacionId);
                vendible = e.vendible();
                comprometido = demandas.stream().mapToInt(CoberturaReservas.Demanda::cantidad).sum();
                libre = CoberturaReservas.maximoVendible(CoberturaReservas.lotesVendibles(e), demandas);
            }
            return new FilaStockResponse(
                    (UUID) fila[0], (String) fila[1],
                    (UUID) fila[2], (String) fila[3], (boolean) fila[4],
                    presentacionId, (String) fila[6], (String) fila[7], (boolean) fila[8],
                    stock, controla, vendible, comprometido, libre);
        }));
    }
}
