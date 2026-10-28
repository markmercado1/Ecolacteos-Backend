package com.planta.acopio.service;

import com.planta.acopio.dto.CrearVentaCarritoRequest;
import com.planta.acopio.dto.DetalleVentaRequest;
import com.planta.acopio.dto.DetalleVentaResponse;
import com.planta.acopio.dto.VentaRequest;
import com.planta.acopio.dto.VentaResponse;
import com.planta.acopio.dto.VentaResumenResponse;
import com.planta.acopio.dto.ClienteDtos;
import com.planta.acopio.entity.AfectacionIgv;
import com.planta.acopio.entity.TipoComprador;
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
    private final ConfiguracionIgvService igvService;
    private final CompradorService compradorService;

    private final TipoPrecioService tipoPrecioService;

    public VentaService(VentaRepository repository, VentaDetalleRepository detalleRepository,
                         PresentacionRepository presentacionRepository, UsuarioRepository usuarioRepository,
                         EntityManager entityManager, PrecioQuesoService precioQuesoService,
                         InventarioService inventarioService, ReservaService reservaService,
                         ConfiguracionIgvService igvService, CompradorService compradorService,
                         TipoPrecioService tipoPrecioService) {
        this.tipoPrecioService = tipoPrecioService;
        this.repository = repository;
        this.detalleRepository = detalleRepository;
        this.presentacionRepository = presentacionRepository;
        this.usuarioRepository = usuarioRepository;
        this.entityManager = entityManager;
        this.precioQuesoService = precioQuesoService;
        this.inventarioService = inventarioService;
        this.reservaService = reservaService;
        this.igvService = igvService;
        this.compradorService = compradorService;
    }

    // Contrato movil (SyncService.procesarLoteVentas y POST /api/ventas directo): sin cambios de forma. Se
    // traduce a una unica linea del modelo compartido.
    // Fase H: la app movil no envia comprador; sus ventas siguen siendo anonimas en cualquier canal (las reglas de comprador por
    // canal son del panel, ver registrarCarritoOIgnorarSiDuplicado), para no romper las ventas que ya captura offline.
    @Transactional
    public VentaResponse registrarOIgnorarSiDuplicado(VentaRequest dto) {
        List<DetalleVentaRequest> detalles = List.of(new DetalleVentaRequest(dto.presentacionId(), dto.cantidad()));
        Venta venta = crear(dto.uuidCliente(), dto.fecha(), dto.tipoCliente(), detalles, null, null, false);
        return toResponse(venta);
    }

    // Fase 4: carrito multi-linea, exclusivo de la web.
    // Fase H: comprador opcional (cliente o proveedor) con las reglas por canal: PUBLICO opcional, MAYORISTA un cliente habilitado,
    // PROVEEDOR un proveedor del padron. En un retiro (reservaId) el comprador es el de la reserva.
    @Transactional
    public VentaResumenResponse registrarCarritoOIgnorarSiDuplicado(CrearVentaCarritoRequest dto) {
        Venta venta = crear(dto.uuidCliente(), dto.fecha(), dto.tipoCliente(), dto.detalles(), dto.reservaId(), dto.comprador(), true);
        return toResumenResponse(venta);
    }

    // Fase 4 (reservas): RETIRO de una reserva confirmada con todas sus lineas, por el flujo normal de ventas (FEFO, kardex,
    // precio vigente). La venta y el paso de la reserva a RETIRADA ocurren en esta MISMA transaccion: si la venta falla por
    // cualquier motivo, la reserva sigue CONFIRMADA y no queda nada escrito.
    @Transactional
    public VentaResumenResponse registrarRetiroDeReserva(UUID reservaId, String uuidCliente, LocalDate fecha) {
        Venta venta = crear(uuidCliente, fecha, TipoClienteVenta.PUBLICO.name(), reservaService.lineasParaRetiro(reservaId), reservaId, null, true);
        return toResumenResponse(venta);
    }

    private Venta crear(String uuidCliente, LocalDate fecha, String tipoClienteStr, List<DetalleVentaRequest> detallesSolicitados,
                        UUID reservaId, ClienteDtos.CompradorRef compradorRef, boolean desdePanel) {
        var existente = repository.findByUuidCliente(uuidCliente);
        if (existente.isPresent()) {
            Venta venta = existente.get();
            if (coincideConSolicitud(venta, fecha, tipoClienteStr, detallesSolicitados)
                    && java.util.Objects.equals(venta.getReservaId(), reservaId)
                    && (reservaId != null || mismoComprador(venta, compradorRef))) {
                return venta;
            }
            // Mismo uuidCliente, contenido distinto: conflicto explicito, la operacion existente NO se
            // modifica (nunca se reinterpreta un reintento con datos diferentes como una edicion).
            throw new ConflictoNegocioException(
                    "Ya existe una venta con este uuidCliente, pero con datos distintos a los enviados; la "
                            + "venta existente no se modifica. Usa un uuidCliente nuevo para una venta distinta.");
        }

        TipoClienteVenta modalidad = TipoClienteVenta.valueOf(tipoClienteStr);
        tipoPrecioService.exigirActivo(modalidad); // un tipo desactivado no vende; el reintento idempotente de arriba ya devolvio lo registrado

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

        // Fase H: quien compra. En un retiro, el comprador CONFIRMADO en la reserva (si lo hay): no se vuelven a pedir datos ni se crea
        // ninguna ficha; si la reserva no tiene comprador vinculado, la venta guarda solo el nombre que dio al reservar. Fuera de un
        // retiro, el comprador elegido en el panel, validado para el canal.
        CompradorService.Comprador comprador;
        if (reserva != null) {
            comprador = reserva.getCompradorTipo() == null ? null : compradorService.resolver(new ClienteDtos.CompradorRef(
                    reserva.getCompradorTipo().name(), reserva.getCompradorTipo() == TipoComprador.CLIENTE ? reserva.getClienteId() : reserva.getProveedorId()));
            if (compradorRef != null && (comprador == null || !comprador.id().equals(compradorRef.id()))) {
                throw new ValidacionNegocioException("El comprador de un retiro es el confirmado en la reserva; si es otra persona, cámbialo en la reserva.");
            }
        } else {
            comprador = compradorService.resolver(compradorRef);
            if (desdePanel) compradorService.validarParaCanal(modalidad, comprador);
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
        if (reserva != null) venta.setReservaId(reserva.getId());
        if (comprador != null) {
            // copia historica de lo usado en esta venta (no cambia si se edita la ficha); el DNI no se copia, el RUC solo si lo tiene
            venta.setCompradorTipo(comprador.tipo());
            venta.setClienteId(comprador.tipo() == TipoComprador.CLIENTE ? comprador.id() : null);
            venta.setProveedorId(comprador.tipo() == TipoComprador.PROVEEDOR ? comprador.id() : null);
            venta.setCompradorNombre(comprador.nombre());
            venta.setCompradorRuc(comprador.ruc());
        } else if (reserva != null) {
            venta.setCompradorNombre(reserva.getNombreCliente());
        }

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
            // Fase G: el precio ya incluye el IGV; si corresponde se desglosa DENTRO del subtotal (null = sin determinar,
            // siempre mientras el calculo este apagado). Mismo subtotal que la columna generada: cantidad x precio.
            CalculoIgv.Desglose igv = igvService.desglosar(r.presentacion().getId(), fecha,
                    r.precioUnitario().multiply(BigDecimal.valueOf(r.cantidad())));
            if (igv != null) detalle.setDesgloseIgv(igv.afectacion(), igv.tasa(), igv.valorVenta(), igv.igv());
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
    /** Reintento con el mismo uuidCliente: el comprador pedido debe ser el mismo que quedo (o ninguno en ambos). */
    private static boolean mismoComprador(Venta existente, ClienteDtos.CompradorRef ref) {
        if (ref == null) return existente.getCompradorTipo() == null;
        UUID id = existente.getCompradorTipo() == TipoComprador.CLIENTE ? existente.getClienteId() : existente.getProveedorId();
        return existente.getCompradorTipo() != null && existente.getCompradorTipo().name().equalsIgnoreCase(ref.tipo().trim()) && ref.id().equals(id);
    }

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
        List<VentaDetalle> lineas = detalleRepository.findByVentaId(v.getId());
        List<DetalleVentaResponse> detalles = lineas.stream()
                .map(d -> new DetalleVentaResponse(
                        d.getPresentacion().getId(), d.getPresentacion().getNombre(), d.getPresentacion().getProducto().getNombre(),
                        d.getCantidad(), d.getPrecioUnitario(), d.getSubtotal(),
                        d.getAfectacionIgv() != null ? d.getAfectacionIgv().name() : null, d.getTasaIgv(), d.getValorVenta(), d.getIgv()))
                .toList();
        UUID compradorId = v.getCompradorTipo() == TipoComprador.CLIENTE ? v.getClienteId() : v.getProveedorId();
        ClienteDtos.CompradorResumen comprador = v.getCompradorTipo() == null && v.getCompradorNombre() == null ? null
                : new ClienteDtos.CompradorResumen(v.getCompradorTipo() == null ? null : v.getCompradorTipo().name(), compradorId,
                        v.getCompradorNombre(), v.getCompradorRuc());
        return new VentaResumenResponse(v.getId(), v.getFecha(), v.getTipoCliente().name(), detalles, v.getTotal(), resumenIgv(lineas, v.getTotal()),
                comprador, v.getReservaId(), v.getReservaId() == null ? null : reservaService.codigoDe(v.getReservaId()));
    }

    // Fase G: suma de la foto tributaria de las lineas. Nada se recalcula: una linea sin foto queda "sin determinar".
    static VentaResumenResponse.ResumenIgvVenta resumenIgv(List<VentaDetalle> lineas, BigDecimal total) {
        BigDecimal gravadas = BigDecimal.ZERO, exoneradas = BigDecimal.ZERO, inafectas = BigDecimal.ZERO, igv = BigDecimal.ZERO;
        BigDecimal determinado = BigDecimal.ZERO;
        int conFoto = 0;
        for (VentaDetalle d : lineas) {
            if (d.getAfectacionIgv() == null) continue;
            conFoto++;
            switch (d.getAfectacionIgv()) {
                case GRAVADO -> { gravadas = gravadas.add(d.getValorVenta()); igv = igv.add(d.getIgv()); }
                case EXONERADO -> exoneradas = exoneradas.add(d.getValorVenta());
                case INAFECTO -> inafectas = inafectas.add(d.getValorVenta());
                default -> { }
            }
            determinado = determinado.add(d.getValorVenta()).add(d.getIgv());
        }
        String estado = conFoto == 0 ? "SIN_DETERMINAR" : conFoto == lineas.size() ? "DETERMINADO" : "PARCIAL";
        BigDecimal sinDeterminar = total == null ? null : total.subtract(determinado).setScale(2, RoundingMode.HALF_UP);
        boolean incluyeIgv = lineas.stream().anyMatch(d -> d.getAfectacionIgv() == AfectacionIgv.GRAVADO);
        return new VentaResumenResponse.ResumenIgvVenta(estado, incluyeIgv, escala(gravadas), escala(exoneradas), escala(inafectas),
                escala(igv), sinDeterminar);
    }

    private static BigDecimal escala(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private record DetalleResuelto(Presentacion presentacion, int cantidad, BigDecimal precioUnitario) {}
}
