package com.planta.acopio.service;

import com.planta.acopio.dto.ControlVencimientoResponse;
import com.planta.acopio.dto.CorregirVencimientoRequest;
import com.planta.acopio.dto.ProductosConControlResponse;
import com.planta.acopio.dto.RegularizacionRequest;
import com.planta.acopio.dto.RegularizacionResponse;
import com.planta.acopio.dto.VencimientoLoteResponse;
import com.planta.acopio.entity.LoteProduccionDetalle;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.RegularizacionTrazabilidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.AsignacionLoteRepository;
import com.planta.acopio.repository.LoteProduccionDetalleRepository;
import com.planta.acopio.repository.MovimientoInventarioRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.RegularizacionTrazabilidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// Fase 1 (control de vencimiento y trazabilidad por lote). Activar/desactivar el control de un producto y
// corregir una fecha de vencimiento digitada incorrectamente. NO toca el libro de movimientos ni el kardex.
//
// Concurrencia: las tres operaciones toman el MISMO bloqueo de fila de Presentacion (en orden de id) que toman
// las ventas, los ajustes y las entradas de produccion (ver InventarioService), y despues leen el estado con
// consultas escalares. Mientras una de ellas mantiene el bloqueo, ningun escritor del libro puede intercalarse.
@Service
public class ControlVencimientoService {

    private final ProductoRepository productoRepository;
    private final PresentacionRepository presentacionRepository;
    private final MovimientoInventarioRepository movimientoRepository;
    private final AsignacionLoteRepository asignacionLoteRepository;
    private final LoteProduccionDetalleRepository detalleRepository;
    private final UsuarioRepository usuarioRepository;
    private final InventarioService inventarioService;
    private final RegularizacionTrazabilidadRepository regularizacionRepository;
    private final ReglaVencimiento reglaVencimiento;
    private final boolean activacionHabilitada;

    public ControlVencimientoService(ProductoRepository productoRepository, PresentacionRepository presentacionRepository,
                                      MovimientoInventarioRepository movimientoRepository,
                                      AsignacionLoteRepository asignacionLoteRepository,
                                      LoteProduccionDetalleRepository detalleRepository, UsuarioRepository usuarioRepository,
                                      InventarioService inventarioService,
                                      RegularizacionTrazabilidadRepository regularizacionRepository,
                                      ReglaVencimiento reglaVencimiento,
                                      @Value("${app.vencimiento.activacion-habilitada:false}") boolean activacionHabilitada) {
        this.productoRepository = productoRepository;
        this.presentacionRepository = presentacionRepository;
        this.movimientoRepository = movimientoRepository;
        this.asignacionLoteRepository = asignacionLoteRepository;
        this.detalleRepository = detalleRepository;
        this.usuarioRepository = usuarioRepository;
        this.inventarioService = inventarioService;
        this.regularizacionRepository = regularizacionRepository;
        this.reglaVencimiento = reglaVencimiento;
        this.activacionHabilitada = activacionHabilitada;
    }

    /**
     * Activa el control de vencimiento. El stock que cada presentacion tiene en este instante queda declarado
     * "sin trazabilidad" (saldo inicial): no se le atribuye ningun lote ni fecha. Idempotente.
     */
    @Transactional
    public ControlVencimientoResponse activar(UUID productoId) {
        if (!activacionHabilitada) {
            throw new ConflictoNegocioException("La activación del control de vencimiento está deshabilitada en este entorno.");
        }
        Producto producto = productoRepository.findById(productoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Producto no encontrado: " + productoId));
        List<Presentacion> presentaciones = presentacionRepository.findByProductoIdOrderByNombre(productoId);
        inventarioService.bloquearPresentaciones(presentaciones.stream().map(Presentacion::getId).toList());

        if (productoRepository.controlaVencimiento(productoId).orElse(false)) {
            return estado(producto, presentaciones);
        }
        for (Presentacion p : presentaciones) {
            // Stock del libro YA confirmado (consulta agregada, no una entidad) bajo el bloqueo de la fila.
            long stock = movimientoRepository.sumarCantidadPorPresentacion(p.getId());
            p.setSaldoSinTrazabilidadInicial(Math.toIntExact(stock));
        }
        producto.setControlaVencimiento(true);
        return estado(producto, presentaciones);
    }

    /**
     * Desactiva el control SOLO si no existe ninguna asignacion en el producto (ningun movimiento desde la
     * activacion) y el stock de cada presentacion sigue siendo igual a su saldo inicial: en ese caso el libro es
     * identico al de la activacion y revertir no falsea nada. Siempre permitido (aunque la activacion este
     * deshabilitada) para poder deshacer una activacion accidental. Idempotente.
     */
    @Transactional
    public ControlVencimientoResponse desactivar(UUID productoId) {
        Producto producto = productoRepository.findById(productoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Producto no encontrado: " + productoId));
        List<Presentacion> presentaciones = presentacionRepository.findByProductoIdOrderByNombre(productoId);
        inventarioService.bloquearPresentaciones(presentaciones.stream().map(Presentacion::getId).toList());

        if (!productoRepository.controlaVencimiento(productoId).orElse(false)) {
            return estado(producto, presentaciones);
        }
        if (asignacionLoteRepository.contarPorProducto(productoId) > 0) {
            throw new ConflictoNegocioException("El control de vencimiento no se puede desactivar: ya existen movimientos "
                    + "asignados a lotes de este producto.");
        }
        for (Presentacion p : presentaciones) {
            long stock = movimientoRepository.sumarCantidadPorPresentacion(p.getId());
            Integer inicial = presentacionRepository.saldoSinTrazabilidadInicialDe(p.getId()).orElse(null);
            long esperado = inicial == null ? 0 : inicial;
            if (stock != esperado) {
                throw new ConflictoNegocioException("El control de vencimiento no se puede desactivar: el stock de '" + p.getNombre()
                        + "' (" + stock + ") ya no coincide con su saldo inicial (" + esperado + ").");
            }
        }
        for (Presentacion p : presentaciones) {
            p.setSaldoSinTrazabilidadInicial(null);
        }
        producto.setControlaVencimiento(false);
        return estado(producto, presentaciones);
    }

    /**
     * Corrige la fecha de vencimiento de una salida de lote, SOLO mientras el lote no se haya usado (ninguna
     * salida asignada). Motivo obligatorio. Se conserva la fecha original (la primera vez) y de la ultima
     * correccion quien, cuando y por que.
     */
    @Transactional
    public VencimientoLoteResponse corregirVencimiento(UUID detalleId, CorregirVencimientoRequest dto) {
        UUID presentacionId = detalleRepository.presentacionIdDe(detalleId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Salida de lote no encontrada: " + detalleId));
        inventarioService.bloquearPresentaciones(List.of(presentacionId));
        if (!inventarioService.controlaVencimiento(presentacionId)) {
            throw new ValidacionNegocioException("El producto de esta salida no controla vencimiento.");
        }
        // Se carga DESPUES del bloqueo: otra correccion concurrente de la misma presentacion ya termino.
        LoteProduccionDetalle detalle = detalleRepository.findById(detalleId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Salida de lote no encontrada: " + detalleId));
        if (detalle.getFechaVencimiento() == null) {
            throw new ValidacionNegocioException("Esta salida no tiene fecha de vencimiento registrada (son existencias sin trazabilidad).");
        }
        if (asignacionLoteRepository.existsByLoteDetalleIdAndCantidadLessThan(detalleId, 0)) {
            throw new ConflictoNegocioException("El lote ya fue utilizado: su fecha de vencimiento ya no se puede corregir por esta vía.");
        }
        var nueva = dto.fechaVencimiento();
        if (nueva.isBefore(detalle.getLote().getFecha())) {
            throw new ValidacionNegocioException("El vencimiento no puede ser anterior a la fecha del lote (" + detalle.getLote().getFecha() + ").");
        }
        if (nueva.equals(detalle.getFechaVencimiento())) {
            throw new ValidacionNegocioException("La fecha de vencimiento indicada es la misma que ya está registrada.");
        }
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));

        if (detalle.getFechaVencimientoOriginal() == null) {
            detalle.setFechaVencimientoOriginal(detalle.getFechaVencimiento());
        }
        detalle.setFechaVencimiento(nueva);
        detalle.setVencimientoCorregidoPor(usuario.getId());
        detalle.setVencimientoCorregidoEn(LocalDateTime.now());
        detalle.setVencimientoMotivoCorreccion(dto.motivo().trim());
        return new VencimientoLoteResponse(detalle.getId(), detalle.getLote().getId(), presentacionId,
                detalle.getFechaVencimiento(), detalle.getFechaVencimientoOriginal(),
                detalle.getVencimientoCorregidoEn(), detalle.getVencimientoMotivoCorreccion());
    }

    /**
     * Fase 2. Reclasifica `cantidad` unidades del saldo "sin trazabilidad" de la presentacion a un lote con la fecha
     * de vencimiento de la etiqueta. SOLO ADMIN (lo exige el controlador). No escribe en el libro ni en el kardex: el
     * stock total no cambia, solo deja de ser "sin trazabilidad" y pasa a poder venderse (FEFO) mientras no venza.
     * Bajo el mismo bloqueo de presentacion que ventas y ajustes.
     */
    @Transactional
    public RegularizacionResponse regularizar(UUID presentacionId, RegularizacionRequest dto) {
        Presentacion presentacion = presentacionRepository.buscarConBloqueoParaActualizarStock(presentacionId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Presentación no encontrada: " + presentacionId));
        if (!inventarioService.controlaVencimiento(presentacionId)) {
            throw new ValidacionNegocioException("El producto de '" + presentacion.getNombre()
                    + "' no controla vencimiento: no hay existencias sin trazabilidad que regularizar.");
        }
        var existencias = inventarioService.calcularExistencias(presentacionId);
        if (dto.cantidad() > existencias.sinTrazabilidad()) {
            throw new ValidacionNegocioException("Solo hay " + existencias.sinTrazabilidad() + " unidades sin trazabilidad en '"
                    + presentacion.getNombre() + "'; no se pueden regularizar " + dto.cantidad() + ".");
        }
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));

        RegularizacionTrazabilidad r = new RegularizacionTrazabilidad();
        r.setPresentacion(presentacion);
        r.setCantidad(dto.cantidad());
        r.setFechaVencimiento(dto.fechaVencimiento());
        r.setMotivo(dto.motivo().trim());
        r.setUsuario(usuario);
        r = regularizacionRepository.save(r);
        return new RegularizacionResponse(r.getId(), presentacionId, r.getCantidad(), r.getFechaVencimiento(),
                reglaVencimiento.estaVencido(r.getFechaVencimiento()), r.getMotivo(), r.getCreadoEn(),
                existencias.sinTrazabilidad() - dto.cantidad());
    }

    @Transactional(readOnly = true)
    public ProductosConControlResponse productosConControl() {
        return new ProductosConControlResponse(productoRepository.idsConControlVencimiento());
    }

    private ControlVencimientoResponse estado(Producto producto, List<Presentacion> presentaciones) {
        return new ControlVencimientoResponse(producto.getId(), Boolean.TRUE.equals(producto.getControlaVencimiento()),
                presentaciones.stream()
                        .map(p -> new ControlVencimientoResponse.PresentacionControl(p.getId(), p.getNombre(),
                                p.getSaldoSinTrazabilidadInicial()))
                        .toList());
    }
}
