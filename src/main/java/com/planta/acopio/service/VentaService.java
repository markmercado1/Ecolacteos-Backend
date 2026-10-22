package com.planta.acopio.service;

import com.planta.acopio.dto.CrearVentaCarritoRequest;
import com.planta.acopio.dto.DetalleVentaRequest;
import com.planta.acopio.dto.DetalleVentaResponse;
import com.planta.acopio.dto.VentaRequest;
import com.planta.acopio.dto.VentaResponse;
import com.planta.acopio.dto.VentaResumenResponse;
import com.planta.acopio.entity.TipoClienteVenta;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Reserva;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.entity.Venta;
import com.planta.acopio.entity.VentaDetalle;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoInactivoException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.repository.VentaDetalleRepository;
import com.planta.acopio.repository.VentaRepository;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// RF-VEN. mejora #1: capturable offline (decision aprobada §18 punto 2).
// Inventario y precio de queso (V7__inventario_precio_queso.sql): el precio que el cliente envia se conserva
// en VentaRequest por compatibilidad (contrato movil), pero deja de ser la fuente de verdad -- el backend
// siempre resuelve el precio vigente (PrecioQuesoService).
//
// Fase 3 (Inventario por presentacion): el stock ya no se controla por producto -- se elige una presentacion
// concreta (no "el producto", con PresentacionService.resolverUnicaActiva de por medio -- esa transicion
// minima de Fase 2 se retiro).
//
// Fase 4 (Venta multi-linea / carrito, V15__venta_multi_linea.sql): una venta pasa a tener una o mas lineas
// (VentaDetalle). El contrato movil (VentaRequest/VentaResponse, POST /api/ventas, ver SyncService) se
// CONSERVA sin cambios -- internamente ahora construye una venta con EXACTAMENTE una linea, a traves del
// mismo metodo compartido `crear()` que usa el carrito nuevo (CrearVentaCarritoRequest,
// registrarCarritoOIgnorarSiDuplicado). Idempotencia: mismo uuidCliente + mismo contenido -> se devuelve la
// venta existente (recuperacion); mismo uuidCliente + contenido distinto -> ConflictoNegocioException (409),
// la venta existente nunca se modifica (mismo criterio que LoteProduccionService).
@Service
public class VentaService {

    private final VentaRepository repository;
    private final VentaDetalleRepository detalleRepository;
    private final PresentacionRepository presentacionRepository;
    private final UsuarioRepository usuarioRepository;
    private final EntityManager entityManager;
    private final PrecioQuesoService precioQuesoService;
    private final InventarioService inventarioService;
    private final ReservaService reservaService;

    public VentaService(VentaRepository repository, VentaDetalleRepository detalleRepository,
                         PresentacionRepository presentacionRepository, UsuarioRepository usuarioRepository,
                         EntityManager entityManager, PrecioQuesoService precioQuesoService,
                         InventarioService inventarioService, ReservaService reservaService) {
        this.repository = repository;
        this.detalleRepository = detalleRepository;
        this.presentacionRepository = presentacionRepository;
        this.usuarioRepository = usuarioRepository;
        this.entityManager = entityManager;
        this.precioQuesoService = precioQuesoService;
        this.inventarioService = inventarioService;
        this.reservaService = reservaService;
    }

    // Contrato movil (SyncService.procesarLoteVentas y POST /api/ventas directo): sin cambios de forma. Se
    // traduce a una unica linea del modelo compartido.
    @Transactional
    public VentaResponse registrarOIgnorarSiDuplicado(VentaRequest dto) {
        List<DetalleVentaRequest> detalles = List.of(new DetalleVentaRequest(dto.presentacionId(), dto.cantidad()));
        Venta venta = crear(dto.uuidCliente(), dto.fecha(), dto.tipoCliente(), detalles);
        return toResponse(venta);
    }

    // Fase 4: carrito multi-linea, exclusivo de la web.
    @Transactional
    public VentaResumenResponse registrarCarritoOIgnorarSiDuplicado(CrearVentaCarritoRequest dto) {
        Venta venta = crear(dto.uuidCliente(), dto.fecha(), dto.tipoCliente(), dto.detalles(), dto.reservaId());
        return toResumenResponse(venta);
    }

    // Fase 4 (reservas): RETIRO de una reserva confirmada con todas sus lineas, por el flujo normal de ventas (FEFO, kardex,
    // precio vigente). La venta y el paso de la reserva a RETIRADA ocurren en esta MISMA transaccion: si la venta falla por
    // cualquier motivo, la reserva sigue CONFIRMADA y no queda nada escrito.
    @Transactional
    public VentaResumenResponse registrarRetiroDeReserva(UUID reservaId, String uuidCliente, LocalDate fecha) {
        Venta venta = crear(uuidCliente, fecha, TipoClienteVenta.PUBLICO.name(), reservaService.lineasParaRetiro(reservaId), reservaId);
        return toResumenResponse(venta);
    }

    private Venta crear(String uuidCliente, LocalDate fecha, String tipoClienteStr, List<DetalleVentaRequest> detallesSolicitados) {
        return crear(uuidCliente, fecha, tipoClienteStr, detallesSolicitados, null);
    }

    private Venta crear(String uuidCliente, LocalDate fecha, String tipoClienteStr, List<DetalleVentaRequest> detallesSolicitados,
                        UUID reservaId) {
        var existente = repository.findByUuidCliente(uuidCliente);
        if (existente.isPresent()) {
            Venta venta = existente.get();
            if (coincideConSolicitud(venta, fecha, tipoClienteStr, detallesSolicitados)) {
                return venta;
            }
            // Mismo uuidCliente, contenido distinto: conflicto explicito, la operacion existente NO se
            // modifica (nunca se reinterpreta un reintento con datos diferentes como una edicion).
            throw new ConflictoNegocioException(
                    "Ya existe una venta con este uuidCliente, pero con datos distintos a los enviados; la "
                            + "venta existente no se modifica. Usa un uuidCliente nuevo para una venta distinta.");
        }

        TipoClienteVenta modalidad = TipoClienteVenta.valueOf(tipoClienteStr);

        // Fase 4: retiro de una reserva. La reserva se bloquea y se pasa a RETIRADA ANTES de validar el stock: asi sus
        // unidades dejan de estar comprometidas y la venta solo se evalua contra las DEMAS reservas. Si algo falla mas
        // abajo, esta transaccion se revierte completa (la reserva sigue CONFIRMADA).
        Reserva reserva = null;
        if (reservaId != null) {
            if (modalidad != TipoClienteVenta.PUBLICO) {
                throw new ValidacionNegocioException("El retiro de una reserva se vende en el canal PUBLICO.");
            }
            reserva = reservaService.iniciarRetiro(reservaId, detallesSolicitados);
        }

        // Validar, resolver precio y bloquear/validar stock de cada linea ANTES de escribir nada. Orden
        // estable por presentacionId: dos ventas concurrentes que comparten presentaciones siempre las
        // bloquean en el mismo orden (evita deadlocks cruzados entre bloqueos pesimistas).
        List<DetalleResuelto> resueltos = resolverDetalles(detallesSolicitados, modalidad, fecha);
        if (reserva != null) {
            Map<UUID, BigDecimal> preciosDeLaVenta = new HashMap<>();
            resueltos.forEach(r -> preciosDeLaVenta.put(r.presentacion().getId(), r.precioUnitario()));
            reservaService.verificarPreciosDelRetiro(reserva, preciosDeLaVenta);
        }

        BigDecimal total = resueltos.stream()
                .map(r -> r.precioUnitario().multiply(BigDecimal.valueOf(r.cantidad())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Usuario vendedor = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));

        Venta venta = new Venta();
        venta.setUuidCliente(uuidCliente);
        venta.setFecha(fecha);
        venta.setTipoCliente(modalidad);
        venta.setTotal(total);
        venta.setVendedor(vendedor);

        try {
            // saveAndFlush(), NO save(): una violacion de UNIQUE(uuid_cliente) por carrera concurrente se
            // traduce a ConflictoNegocioException en vez de escapar sin capturar en el commit (mismo criterio
            // que LoteProduccionService).
            venta = repository.saveAndFlush(venta);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictoNegocioException(
                    "Ya existe una venta con este uuidCliente (creada concurrentemente); reintentar con el "
                            + "mismo uuidCliente para obtenerla.");
        }

        // Una salida de inventario POR LINEA, en la misma transaccion -- no puede existir una venta sin la
        // salida correspondiente a cada una de sus lineas; si cualquiera falla, toda la transaccion
        // (venta+lineas+salidas previas) se revierte junta.
        List<VentaDetalle> detallesGuardados = new ArrayList<>();
        for (DetalleResuelto r : resueltos) {
            VentaDetalle detalle = new VentaDetalle();
            detalle.setVenta(venta);
            detalle.setPresentacion(r.presentacion());
            detalle.setCantidad(r.cantidad());
            detalle.setPrecioUnitario(r.precioUnitario());
            detallesGuardados.add(detalleRepository.save(detalle));
        }
        entityManager.flush();
        for (VentaDetalle detalle : detallesGuardados) {
            entityManager.refresh(detalle); // subtotal es columna GENERADA por Postgres
            inventarioService.registrarSalidaVenta(detalle);
        }
        if (reserva != null) reservaService.completarRetiro(reserva, venta.getId());

        return venta;
    }

    // No permite presentaciones inactivas, duplicadas dentro de la misma venta, ni con cantidad invalida
    // (esto ultimo ya lo garantiza @Min(1) en DetalleVentaRequest, pero se revalida aqui). A diferencia de
    // produccion, una venta puede combinar presentaciones de productos DISTINTOS (un cliente puede comprar
    // yogurt y queso en la misma venta) -- no hay una nocion de "producto de la venta" que las agrupe.
    private List<DetalleResuelto> resolverDetalles(List<DetalleVentaRequest> detalles, TipoClienteVenta modalidad, LocalDate fecha) {
        List<DetalleVentaRequest> ordenados = detalles.stream()
                .sorted(Comparator.comparing(DetalleVentaRequest::presentacionId))
                .toList();

        List<DetalleResuelto> resueltos = new ArrayList<>();
        Set<UUID> vistas = new HashSet<>();
        for (DetalleVentaRequest d : ordenados) {
            if (!vistas.add(d.presentacionId())) {
                throw new ValidacionNegocioException(
                        "La presentación " + d.presentacionId() + " aparece más de una vez en la misma venta.");
            }
            Presentacion presentacion = presentacionRepository.findById(d.presentacionId())
                    .orElseThrow(() -> new RecursoNoEncontradoException("Presentación no encontrada: " + d.presentacionId()));
            if (!Boolean.TRUE.equals(presentacion.getActivo()) || !Boolean.TRUE.equals(presentacion.getProducto().getActivo())) {
                throw new RecursoInactivoException(
                        "La presentación '" + presentacion.getNombre() + "' de '" + presentacion.getProducto().getNombre()
                                + "' está desactivada");
            }

            // Autoridad de precio: el backend siempre resuelve el precio vigente, con la fecha de la venta
            // (no "hoy") -- una venta capturada offline y sincronizada despues debe cobrar el precio vigente
            // el dia en que ocurrio.
            BigDecimal precioVigente = precioQuesoService.resolverPrecioVigente(presentacion, modalidad, fecha);

            // Bloqueo + validacion de stock de esta presentacion (evita sobreventa concurrente). El lock se
            // mantiene hasta el commit/rollback de esta transaccion.
            inventarioService.validarStockSuficiente(presentacion.getId(), d.cantidad());

            resueltos.add(new DetalleResuelto(presentacion, d.cantidad(), precioVigente));
        }
        return resueltos;
    }

    // Mismo uuidCliente: recupera si el contenido coincide EXACTAMENTE (fecha, canal y el conjunto
    // presentacion->cantidad de las lineas); si difiere en cualquier campo, es un conflicto explicito (ver
    // crear()) -- nunca se reinterpreta como una correccion silenciosa de la venta existente. Comparacion de
    // tipoCliente por texto (no se parsea a enum aqui): un reintento con el mismo uuidCliente de una venta ya
    // confirmada nunca debe fallar por un tipoCliente invalido que ni siquiera hace falta volver a validar.
    private boolean coincideConSolicitud(Venta existente, LocalDate fecha, String tipoClienteStr, List<DetalleVentaRequest> detallesSolicitados) {
        if (!existente.getFecha().equals(fecha)) return false;
        if (!existente.getTipoCliente().name().equals(tipoClienteStr)) return false;

        Map<UUID, Integer> detallesExistentes = new HashMap<>();
        for (VentaDetalle d : detalleRepository.findByVentaId(existente.getId())) {
            detallesExistentes.put(d.getPresentacion().getId(), d.getCantidad());
        }
        Map<UUID, Integer> detallesNuevos = new HashMap<>();
        for (DetalleVentaRequest d : detallesSolicitados) {
            detallesNuevos.put(d.presentacionId(), d.cantidad());
        }
        return detallesExistentes.equals(detallesNuevos);
    }

    // Bloque 5 (confirmado): filtros opcionales combinables. Sin parametros,
    // mismo resultado que antes (no rompe al frontend actual). Se valida que
    // tipoCliente sea un valor legal del enum antes de pasarlo a la query
    // nativa (que lo recibe como String -- ver comentario en VentaRepository).
    public Page<VentaResumenResponse> buscar(LocalDate fecha, String tipoCliente, String busqueda, Pageable pageable) {
        String tipoNormalizado = tipoCliente != null ? TipoClienteVenta.valueOf(tipoCliente).name() : null;
        return repository.buscarConFiltros(fecha, tipoNormalizado, BusquedaTexto.patronLike(busqueda), pageable)
                .map(this::toResumenResponse);
    }

    public VentaResumenResponse obtener(UUID id) {
        return toResumenResponse(repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Venta no encontrada: " + id)));
    }

    // Forma plana del contrato movil (POST /api/ventas): una venta creada por ese endpoint siempre tiene
    // exactamente una linea (ver crear()); si alguna vez no la tuviera, es un error de invariante, no un caso
    // de negocio a representar en silencio.
    private VentaResponse toResponse(Venta v) {
        List<VentaDetalle> detalles = detalleRepository.findByVentaId(v.getId());
        if (detalles.size() != 1) {
            throw new IllegalStateException(
                    "La venta " + v.getId() + " tiene " + detalles.size() + " línea(s); POST /api/ventas "
                            + "(contrato móvil) solo representa ventas de una sola línea.");
        }
        VentaDetalle d = detalles.get(0);
        return new VentaResponse(v.getId(), v.getFecha(), v.getTipoCliente().name(),
                d.getPresentacion().getId(), d.getPresentacion().getNombre(), d.getPresentacion().getProducto().getNombre(),
                d.getCantidad(), d.getPrecioUnitario(), v.getTotal());
    }

    private VentaResumenResponse toResumenResponse(Venta v) {
        List<DetalleVentaResponse> detalles = detalleRepository.findByVentaId(v.getId()).stream()
                .map(d -> new DetalleVentaResponse(
                        d.getPresentacion().getId(), d.getPresentacion().getNombre(), d.getPresentacion().getProducto().getNombre(),
                        d.getCantidad(), d.getPrecioUnitario(), d.getSubtotal()))
                .toList();
        return new VentaResumenResponse(v.getId(), v.getFecha(), v.getTipoCliente().name(), detalles, v.getTotal());
    }

    private record DetalleResuelto(Presentacion presentacion, int cantidad, BigDecimal precioUnitario) {}
}
