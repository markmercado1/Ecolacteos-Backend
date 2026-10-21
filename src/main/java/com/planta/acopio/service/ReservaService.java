package com.planta.acopio.service;

import com.planta.acopio.dto.AceptacionPrecioRequest;
import com.planta.acopio.dto.ConsultaReservaRequest;
import com.planta.acopio.dto.CrearReservaRequest;
import com.planta.acopio.dto.DetalleVentaRequest;
import com.planta.acopio.dto.MotivoReservaRequest;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.dto.ReservaPublicaResponse;
import com.planta.acopio.dto.ReservaResponse;
import com.planta.acopio.dto.ReservaResumenResponse;
import com.planta.acopio.entity.EstadoReserva;
import com.planta.acopio.entity.PrecioQueso;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Reserva;
import com.planta.acopio.entity.ReservaDetalle;
import com.planta.acopio.entity.ReservaEvento;
import com.planta.acopio.entity.TipoClienteVenta;
import com.planta.acopio.entity.TipoEventoReserva;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoInactivoException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.PrecioQuesoRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ReservaDetalleRepository;
import com.planta.acopio.repository.ReservaEventoRepository;
import com.planta.acopio.repository.ReservaRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Fase 4 (reservas). Una reserva COMPROMETE unidades de una presentacion para una fecha de retiro, SIN reducir el stock
// fisico, sin venta y sin tocar el libro ni el kardex. Reglas (ver tambien CoberturaReservas, ReservaParametros):
//  - Solo se reservan presentaciones de productos que controlan vencimiento (lo que publica el catalogo).
//  - Una reserva se respalda unicamente con lotes VENDIBLES cuyo vencimiento llegue a la fecha de retiro; ni lo vencido
//    ni lo "sin trazabilidad" cuentan. Nunca se compromete mas que lo disponible.
//  - PENDIENTE retiene hasta expira_en; CONFIRMADA hasta el fin de fecha_retiro; VENCIDA se calcula (sin proceso programado).
//  - Orden de bloqueos: telefono -> reserva -> presentaciones (en orden de id). Los mismos bloqueos de presentacion que
//    usan ventas, ajustes y produccion, de modo que dos reservas, o una reserva y una venta, nunca comprometen las
//    mismas unidades.
//  - El retiro es una VENTA REAL por el flujo normal (FEFO + kardex), asociada a la reserva en la misma transaccion.
@Service
public class ReservaService {

    private static final Logger log = LoggerFactory.getLogger(ReservaService.class);
    private static final String ALFABETO_CODIGO = "ABCDEFGHJKMNPQRSTVWXYZ23456789";
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final ReservaRepository reservaRepository;
    private final ReservaDetalleRepository detalleRepository;
    private final ReservaEventoRepository eventoRepository;
    private final PresentacionRepository presentacionRepository;
    private final PrecioQuesoRepository precioRepository;
    private final UsuarioRepository usuarioRepository;
    private final InventarioService inventarioService;
    private final ReservaCompromisos compromisos;
    private final ReservaConfig config;
    private final ReglaVencimiento regla;

    public ReservaService(ReservaRepository reservaRepository, ReservaDetalleRepository detalleRepository,
                          ReservaEventoRepository eventoRepository, PresentacionRepository presentacionRepository,
                          PrecioQuesoRepository precioRepository, UsuarioRepository usuarioRepository,
                          InventarioService inventarioService, ReservaCompromisos compromisos, ReservaConfig config,
                          ReglaVencimiento regla) {
        this.reservaRepository = reservaRepository;
        this.detalleRepository = detalleRepository;
        this.eventoRepository = eventoRepository;
        this.presentacionRepository = presentacionRepository;
        this.precioRepository = precioRepository;
        this.usuarioRepository = usuarioRepository;
        this.inventarioService = inventarioService;
        this.compromisos = compromisos;
        this.config = config;
        this.regla = regla;
    }

    /** Fase 6: reglas de reserva que la web publica muestra (fechas validas ahora, tope de unidades, retencion de pendientes). */
    @Transactional(readOnly = true)
    public com.planta.acopio.dto.ReglasReservaPublicaResponse reglasPublicas() {
        ReservaParametros p = config.actuales();
        return new com.planta.acopio.dto.ReglasReservaPublicaResponse(p.fechasDisponibles(regla.ahora()), p.maxUnidades(), p.horasPendiente());
    }

    // ================================================================== creacion publica

    @Transactional
    public ReservaPublicaResponse crear(CrearReservaRequest dto) {
        ReservaParametros p = config.actuales();
        LocalDateTime ahora = regla.ahora();
        LocalDate hoy = ahora.toLocalDate();

        String telefono = TelefonoPeru.normalizar(dto.telefono()).orElseThrow(() -> new ValidacionNegocioException(
                "El teléfono no es válido: usa 9 dígitos que empiecen con 9 (con o sin +51)."));
        String nombre = dto.nombreCliente().trim();
        if (nombre.isEmpty()) throw new ValidacionNegocioException("Indica tu nombre.");
        String correo = dto.correo() == null || dto.correo().isBlank() ? null : dto.correo().trim();
        String nota = dto.nota() == null || dto.nota().isBlank() ? null : dto.nota().trim();

        Set<UUID> vistas = new HashSet<>();
        for (CrearReservaRequest.Linea l : dto.lineas()) {
            if (!vistas.add(l.presentacionId())) {
                throw new ValidacionNegocioException("La presentación " + l.presentacionId() + " aparece más de una vez en la reserva.");
            }
        }
        p.errorDeFecha(dto.fechaRetiro(), ahora).ifPresent(m -> { throw new ValidacionNegocioException(m); });

        Map<UUID, Presentacion> presentaciones = new LinkedHashMap<>();
        for (CrearReservaRequest.Linea l : dto.lineas().stream().sorted(Comparator.comparing(CrearReservaRequest.Linea::presentacionId)).toList()) {
            presentaciones.put(l.presentacionId(), presentacionRepository.findById(l.presentacionId())
                    .orElseThrow(() -> new RecursoNoEncontradoException("Presentación no encontrada: " + l.presentacionId())));
        }
        validarLimiteDeUnidades(p, dto.lineas(), presentaciones);

        // 1) Bloqueo por telefono: serializa las solicitudes de un mismo numero (limite de pendientes ante concurrencia).
        reservaRepository.bloquearTelefono(telefono);

        // Idempotencia: se comprueba DESPUES del bloqueo, para ver una solicitud identica que acaba de confirmar otra transaccion.
        Optional<Reserva> existente = reservaRepository.findByUuidCliente(dto.uuidCliente());
        if (existente.isPresent()) {
            if (coincide(existente.get(), dto, telefono)) return aPublica(existente.get(), ahora, hoy);
            throw new ConflictoNegocioException("Ya existe una reserva con este uuidCliente, pero con datos distintos a los enviados; "
                    + "usa un uuidCliente nuevo para otra reserva.");
        }
        if (reservaRepository.contarPendientesVigentes(telefono, ahora) >= p.maxPendientesTelefono()) {
            throw new ConflictoNegocioException("Este teléfono ya tiene " + p.maxPendientesTelefono()
                    + " reservas pendientes. Espera a que las atendamos (o venzan) para hacer otra.");
        }

        // 2) Bloqueo de presentaciones, en orden de id (el mismo de ventas, ajustes y produccion).
        inventarioService.bloquearPresentaciones(presentaciones.keySet());

        Map<UUID, BigDecimal> precios = new HashMap<>();
        for (CrearReservaRequest.Linea l : dto.lineas()) {
            Presentacion pr = presentaciones.get(l.presentacionId());
            if (!Boolean.TRUE.equals(pr.getActivo()) || !Boolean.TRUE.equals(pr.getProducto().getActivo())
                    || !inventarioService.controlaVencimiento(pr.getId())) {
                throw new RecursoInactivoException("La presentación '" + pr.getNombre() + "' de '" + pr.getProducto().getNombre()
                        + "' no está disponible para reservar.");
            }
            int disponible = disponibleParaReservar(pr.getId(), dto.fechaRetiro());
            if (l.cantidad() > disponible) {
                throw new ValidacionNegocioException(disponible == 0
                        ? "No hay unidades disponibles de '" + pr.getNombre() + "' para retirar el " + dto.fechaRetiro() + "."
                        : "Solo hay " + disponible + " " + pr.getUnidadConteo() + " disponibles de '" + pr.getNombre()
                                + "' para retirar el " + dto.fechaRetiro() + " (pediste " + l.cantidad() + ").");
            }
            precioRepository.findVigente(pr.getId(), TipoClienteVenta.PUBLICO, hoy).ifPresent(x -> precios.put(pr.getId(), x.getPrecio()));
        }

        Reserva r = new Reserva();
        r.setCodigo(nuevoCodigo());
        r.setUuidCliente(dto.uuidCliente());
        r.setEstado(EstadoReserva.PENDIENTE);
        r.setNombreCliente(nombre);
        r.setTelefono(telefono);
        r.setCorreo(correo);
        r.setNotaCliente(nota);
        r.setFechaRetiro(dto.fechaRetiro());
        r.setFechaMaximaRetiro(p.fechaMaxima(hoy));
        r.setCreadoEn(ahora);
        r.setExpiraEn(ahora.plusHours(p.horasPendiente()));
        boolean todosConPrecio = dto.lineas().stream().allMatch(l -> precios.containsKey(l.presentacionId()));
        r.setTotal(todosConPrecio ? total(dto.lineas().stream().map(l -> new Importe(l.cantidad(), precios.get(l.presentacionId()))).toList()) : null);
        r.setRequiereAceptacion(!todosConPrecio);
        try {
            r = reservaRepository.saveAndFlush(r);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictoNegocioException("No se pudo registrar la reserva (solicitud duplicada); reintenta con el mismo uuidCliente.");
        }
        for (CrearReservaRequest.Linea l : dto.lineas()) {
            ReservaDetalle d = new ReservaDetalle();
            d.setReserva(r);
            d.setPresentacion(presentaciones.get(l.presentacionId()));
            d.setCantidad(l.cantidad());
            d.setPrecioUnitario(precios.get(l.presentacionId()));
            detalleRepository.save(d);
        }
        eventoRepository.save(new ReservaEvento(r, TipoEventoReserva.CREADA, null, null, ahora));
        return aPublica(r, ahora, hoy);
    }

    // ================================================================== consulta publica

    /** El cliente consulta SU reserva (codigo + telefono). Cualquier desajuste responde igual que un codigo inexistente. */
    @Transactional(readOnly = true)
    public ReservaPublicaResponse consultar(ConsultaReservaRequest dto) {
        RecursoNoEncontradoException noEncontrada = new RecursoNoEncontradoException("Reserva no encontrada.");
        String telefono = TelefonoPeru.normalizar(dto.telefono()).orElseThrow(() -> noEncontrada);
        Reserva r = reservaRepository.findByCodigo(dto.codigo().trim().toUpperCase()).orElseThrow(() -> noEncontrada);
        if (!r.getTelefono().equals(telefono)) throw noEncontrada;
        LocalDateTime ahora = regla.ahora();
        return aPublica(r, ahora, ahora.toLocalDate());
    }

    // ================================================================== personal: consulta

    @Transactional(readOnly = true)
    public PaginaResponse<ReservaResumenResponse> listar(String estado, LocalDate fechaRetiro, String telefono, String codigo, Pageable pageable) {
        String estadoNormalizado = null;
        if (estado != null && !estado.isBlank()) {
            try {
                estadoNormalizado = EstadoReserva.valueOf(estado.trim().toUpperCase()).name();
            } catch (IllegalArgumentException e) {
                throw new ValidacionNegocioException("Estado inválido: " + estado + " (usa PENDIENTE, CONFIRMADA, RECHAZADA, CANCELADA, VENCIDA o RETIRADA).");
            }
        }
        String telefonoNormalizado = null;
        if (telefono != null && !telefono.isBlank()) {
            telefonoNormalizado = TelefonoPeru.normalizar(telefono).orElseThrow(() -> new ValidacionNegocioException("El teléfono del filtro no es válido."));
        }
        String codigoNormalizado = (codigo == null || codigo.isBlank()) ? null : codigo.trim().toUpperCase();
        LocalDateTime ahora = regla.ahora();
        LocalDate hoy = ahora.toLocalDate();
        var pagina = reservaRepository.buscar(estadoNormalizado, fechaRetiro, telefonoNormalizado, codigoNormalizado, ahora, hoy, pageable);
        Map<UUID, List<ReservaDetalle>> lineas = new HashMap<>();
        if (!pagina.isEmpty()) {
            for (ReservaDetalle d : detalleRepository.findByReservaIdIn(pagina.getContent().stream().map(Reserva::getId).toList())) {
                lineas.computeIfAbsent(d.getReserva().getId(), k -> new ArrayList<>()).add(d);
            }
        }
        return PaginaResponse.de(pagina.map(r -> {
            List<ReservaDetalle> ls = lineas.getOrDefault(r.getId(), List.of());
            EstadoReserva efectivo = r.estadoEfectivo(ahora, hoy);
            return new ReservaResumenResponse(r.getId(), r.getCodigo(), efectivo, r.getNombreCliente(), r.getTelefono(),
                    r.getFechaRetiro(), r.getCreadoEn(), r.getExpiraEn(), ls.stream().mapToInt(ReservaDetalle::getCantidad).sum(), r.getTotal(),
                    ls.stream().anyMatch(d -> d.getPrecioUnitario() == null), r.isRequiereAceptacion(), r.getTotalAceptado() != null,
                    comprometida(efectivo) && sinCobertura(r, ls));
        }));
    }

    // Instantanea consistente (REPEATABLE READ): varias consultas ven el mismo estado aunque algo confirme en medio.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReservaResponse obtener(UUID id) {
        Reserva r = reservaRepository.findById(id).orElseThrow(() -> new RecursoNoEncontradoException("Reserva no encontrada: " + id));
        return aDetalle(r);
    }

    // ================================================================== personal: precio

    /**
     * Vuelve a cotizar TODAS las lineas con el precio publico vigente hoy (por ejemplo despues de configurar el precio de una
     * presentacion que estaba "a confirmar"). Si algun precio cambia, el cliente debe aceptar de nuevo el importe.
     */
    @Transactional
    public ReservaResponse recotizar(UUID id) {
        LocalDateTime ahora = regla.ahora();
        LocalDate hoy = ahora.toLocalDate();
        Reserva r = bloquear(id);
        exigirEstado(r, ahora, hoy, "recotizar", EstadoReserva.PENDIENTE);
        List<ReservaDetalle> lineas = detalleRepository.findByReservaIdOrderByPresentacionId(id);
        boolean cambio = false;
        for (ReservaDetalle d : lineas) {
            BigDecimal nuevo = precioRepository.findVigente(d.getPresentacion().getId(), TipoClienteVenta.PUBLICO, hoy)
                    .map(PrecioQueso::getPrecio).orElse(null);
            if (!iguales(d.getPrecioUnitario(), nuevo)) {
                d.setPrecioUnitario(nuevo);
                cambio = true;
            }
        }
        r.setTotal(totalDe(lineas));
        if (cambio) {
            r.setRequiereAceptacion(true);
            r.setTotalAceptado(null);
            r.setAceptadoEn(null);
            r.setAceptadoPor(null);
            r.setAceptadoMedio(null);
            r.setAceptadoNota(null);
        }
        eventoRepository.save(new ReservaEvento(r, TipoEventoReserva.PRECIOS_RECOTIZADOS, usuarioActual(), cambio ? "Precios actualizados" : "Sin cambios", ahora));
        return aDetalle(r);
    }

    /** Deja constancia de que el cliente acepto el importe vigente (y por que medio). Queda con usuario, fecha e importe. */
    @Transactional
    public ReservaResponse registrarAceptacionDePrecio(UUID id, AceptacionPrecioRequest dto) {
        LocalDateTime ahora = regla.ahora();
        Reserva r = bloquear(id);
        exigirEstado(r, ahora, ahora.toLocalDate(), "registrar la aceptación del precio de", EstadoReserva.PENDIENTE);
        if (!r.isRequiereAceptacion()) throw new ValidacionNegocioException("Esta reserva no requiere aceptación de precio.");
        List<ReservaDetalle> lineas = detalleRepository.findByReservaIdOrderByPresentacionId(id);
        BigDecimal total = totalDe(lineas);
        if (total == null) {
            throw new ConflictoNegocioException("Todas las líneas deben tener precio antes de registrar la aceptación: recotiza la reserva.");
        }
        Usuario usuario = usuarioActual();
        r.setTotal(total);
        r.setTotalAceptado(total);
        r.setAceptadoEn(ahora);
        r.setAceptadoPor(usuario == null ? null : usuario.getId());
        r.setAceptadoMedio(dto.medio());
        r.setAceptadoNota(dto.nota() == null || dto.nota().isBlank() ? null : dto.nota().trim());
        eventoRepository.save(new ReservaEvento(r, TipoEventoReserva.PRECIO_ACEPTADO, usuario,
                dto.medio() + " · S/ " + total.toPlainString(), ahora));
        return aDetalle(r);
    }

    // ================================================================== personal: estados

    @Transactional
    public ReservaResponse confirmar(UUID id) {
        LocalDateTime ahora = regla.ahora();
        LocalDate hoy = ahora.toLocalDate();
        Reserva r = bloquear(id);                                              // 2) reserva
        exigirEstado(r, ahora, hoy, "confirmar", EstadoReserva.PENDIENTE);
        if (r.getFechaRetiro().isBefore(hoy)) {
            throw new ConflictoNegocioException("La fecha de retiro (" + r.getFechaRetiro() + ") ya pasó: no se puede confirmar.");
        }
        List<ReservaDetalle> lineas = detalleRepository.findByReservaIdOrderByPresentacionId(id);
        inventarioService.bloquearPresentaciones(lineas.stream().map(d -> d.getPresentacion().getId()).toList()); // 3) presentaciones

        // Precio: todas las lineas con precio, igual al vigente hoy, y aceptado por el cliente cuando corresponde.
        for (ReservaDetalle d : lineas) {
            if (d.getPrecioUnitario() == null) {
                throw new ConflictoNegocioException("'" + d.getPresentacion().getNombre() + "' sigue con «Precio a confirmar»: configura su precio "
                        + "público, recotiza la reserva y registra la aceptación del cliente antes de confirmar.");
            }
            BigDecimal vigente = precioRepository.findVigente(d.getPresentacion().getId(), TipoClienteVenta.PUBLICO, hoy)
                    .map(PrecioQueso::getPrecio).orElse(null);
            if (!iguales(d.getPrecioUnitario(), vigente)) {
                throw new ConflictoNegocioException("El precio público de '" + d.getPresentacion().getNombre() + "' cambió desde la cotización: "
                        + "recotiza la reserva y registra la aceptación del cliente.");
            }
        }
        if (r.isRequiereAceptacion() && (r.getTotalAceptado() == null || r.getTotal() == null
                || r.getTotalAceptado().compareTo(r.getTotal()) != 0)) {
            throw new ConflictoNegocioException("Falta la aceptación del importe por parte del cliente (o el importe cambió después de aceptarlo).");
        }
        // Cobertura: con las demas reservas vigentes (esta incluida: PENDIENTE retiene), los lotes vendibles que llegan a cada fecha deben alcanzar.
        for (ReservaDetalle d : lineas) {
            UUID presentacionId = d.getPresentacion().getId();
            var lotes = CoberturaReservas.lotesVendibles(inventarioService.calcularExistencias(presentacionId));
            if (!CoberturaReservas.cubre(lotes, compromisos.demandasDe(presentacionId))) {
                throw new ConflictoNegocioException("Ya no hay unidades suficientes de '" + d.getPresentacion().getNombre()
                        + "' para respaldar esta reserva junto con las demás (lotes vencidos, ajustes o ventas). Recházala o cancélala.");
            }
        }
        r.setEstado(EstadoReserva.CONFIRMADA);
        eventoRepository.save(new ReservaEvento(r, TipoEventoReserva.CONFIRMADA, usuarioActual(), null, ahora));
        return aDetalle(r);
    }

    @Transactional
    public ReservaResponse rechazar(UUID id, MotivoReservaRequest dto) {
        LocalDateTime ahora = regla.ahora();
        Reserva r = bloquear(id);
        exigirEstado(r, ahora, ahora.toLocalDate(), "rechazar", EstadoReserva.PENDIENTE);
        r.setEstado(EstadoReserva.RECHAZADA);
        eventoRepository.save(new ReservaEvento(r, TipoEventoReserva.RECHAZADA, usuarioActual(), dto.motivo().trim(), ahora));
        return aDetalle(r);
    }

    @Transactional
    public ReservaResponse cancelar(UUID id, MotivoReservaRequest dto) {
        LocalDateTime ahora = regla.ahora();
        Reserva r = bloquear(id);
        exigirEstado(r, ahora, ahora.toLocalDate(), "cancelar", EstadoReserva.PENDIENTE, EstadoReserva.CONFIRMADA);
        r.setEstado(EstadoReserva.CANCELADA);
        eventoRepository.save(new ReservaEvento(r, TipoEventoReserva.CANCELADA, usuarioActual(), dto.motivo().trim(), ahora));
        return aDetalle(r);
    }

    // ================================================================== retiro (dentro de la transaccion de la venta)

    /** Las lineas de la reserva como lineas de venta: el retiro completo. */
    @Transactional(readOnly = true)
    public List<DetalleVentaRequest> lineasParaRetiro(UUID reservaId) {
        reservaRepository.findById(reservaId).orElseThrow(() -> new RecursoNoEncontradoException("Reserva no encontrada: " + reservaId));
        return detalleRepository.findByReservaIdOrderByPresentacionId(reservaId).stream()
                .map(d -> new DetalleVentaRequest(d.getPresentacion().getId(), d.getCantidad())).toList();
    }

    /**
     * PASO 1 del retiro, dentro de la transaccion de VentaService: bloquea la reserva (antes que las presentaciones), exige que
     * este CONFIRMADA y vigente, valida que las lineas de la venta sean lineas de la reserva (con cantidad no mayor) y la pasa
     * a RETIRADA. Al liberar sus unidades ANTES de validar la venta, esta se evalua contra las DEMAS reservas. Si la venta
     * falla, la transaccion completa se revierte y la reserva sigue CONFIRMADA.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Reserva iniciarRetiro(UUID reservaId, List<DetalleVentaRequest> detalles) {
        LocalDateTime ahora = regla.ahora();
        Reserva r = bloquear(reservaId);
        exigirEstado(r, ahora, ahora.toLocalDate(), "retirar", EstadoReserva.CONFIRMADA);
        Map<UUID, ReservaDetalle> lineas = new HashMap<>();
        for (ReservaDetalle d : detalleRepository.findByReservaIdOrderByPresentacionId(reservaId)) lineas.put(d.getPresentacion().getId(), d);
        for (DetalleVentaRequest d : detalles) {
            ReservaDetalle linea = lineas.get(d.presentacionId());
            if (linea == null) {
                throw new ValidacionNegocioException("La presentación " + d.presentacionId() + " no forma parte de la reserva " + r.getCodigo() + ".");
            }
            if (d.cantidad() > linea.getCantidad()) {
                throw new ValidacionNegocioException("Se intenta retirar " + d.cantidad() + " de '" + linea.getPresentacion().getNombre()
                        + "' pero la reserva es de " + linea.getCantidad() + ".");
            }
        }
        r.setEstado(EstadoReserva.RETIRADA);
        return reservaRepository.saveAndFlush(r);
    }

    /** El precio de cada linea de la venta debe ser el cotizado en la reserva: el cliente paga lo acordado, no otro importe. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void verificarPreciosDelRetiro(Reserva r, Map<UUID, BigDecimal> preciosDeLaVenta) {
        Map<UUID, ReservaDetalle> lineas = new HashMap<>();
        for (ReservaDetalle d : detalleRepository.findByReservaIdOrderByPresentacionId(r.getId())) lineas.put(d.getPresentacion().getId(), d);
        preciosDeLaVenta.forEach((presentacionId, precio) -> {
            ReservaDetalle linea = lineas.get(presentacionId);
            if (linea == null || !iguales(linea.getPrecioUnitario(), precio)) {
                throw new ConflictoNegocioException("El precio público vigente de '" + (linea == null ? presentacionId : linea.getPresentacion().getNombre())
                        + "' (S/ " + precio.toPlainString() + ") no coincide con el cotizado en la reserva"
                        + (linea == null || linea.getPrecioUnitario() == null ? "" : " (S/ " + linea.getPrecioUnitario().toPlainString() + ")")
                        + ". No se retiró nada: resuelve el precio antes de entregar.");
            }
        });
    }

    /** PASO 2 del retiro: enlaza la venta real con la reserva y deja constancia en su historial. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completarRetiro(Reserva r, UUID ventaId) {
        r.setVentaId(ventaId);
        eventoRepository.save(new ReservaEvento(r, TipoEventoReserva.RETIRADA, usuarioActual(), "Venta " + ventaId, regla.ahora()));
    }

    // ================================================================== utilidades

    /** Cuantas unidades se pueden reservar hoy de la presentacion para esa fecha (bajo el bloqueo de la presentacion). */
    private int disponibleParaReservar(UUID presentacionId, LocalDate fechaRetiro) {
        var lotes = CoberturaReservas.lotesVendibles(inventarioService.calcularExistencias(presentacionId));
        return CoberturaReservas.disponible(lotes, compromisos.demandasDe(presentacionId), fechaRetiro);
    }

    private Reserva bloquear(UUID id) {
        return reservaRepository.bloquearPorId(id).orElseThrow(() -> new RecursoNoEncontradoException("Reserva no encontrada: " + id));
    }

    private void exigirEstado(Reserva r, LocalDateTime ahora, LocalDate hoy, String accion, EstadoReserva... permitidos) {
        EstadoReserva actual = r.estadoEfectivo(ahora, hoy);
        for (EstadoReserva e : permitidos) if (e == actual) return;
        throw new ConflictoNegocioException("No se puede " + accion + " una reserva en estado " + actual
                + (actual == EstadoReserva.VENCIDA ? " (venció y ya no retiene unidades)" : "") + ".");
    }

    private Usuario usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) return null;
        return usuarioRepository.findByEmail(auth.getName()).orElse(null);
    }

    /** Limite de unidades: las unidades discretas se suman entre si; las medidas (kg, g, L, ml) se limitan por separado, por unidad. */
    private void validarLimiteDeUnidades(ReservaParametros p, List<CrearReservaRequest.Linea> lineas, Map<UUID, Presentacion> presentaciones) {
        int discretas = 0;
        Map<String, Integer> medidas = new HashMap<>();
        for (CrearReservaRequest.Linea l : lineas) {
            String unidad = presentaciones.get(l.presentacionId()).getUnidadConteo();
            if (ReservaParametros.esMedida(unidad)) medidas.merge(ReservaParametros.clave(unidad), l.cantidad(), Integer::sum);
            else discretas += l.cantidad();
        }
        if (discretas > p.maxUnidades()) {
            throw new ValidacionNegocioException("Una reserva admite como máximo " + p.maxUnidades() + " unidades en total (pediste " + discretas + ").");
        }
        medidas.forEach((unidad, total) -> {
            if (total > p.maxUnidades()) {
                throw new ValidacionNegocioException("Una reserva admite como máximo " + p.maxUnidades() + " " + unidad + " (pediste " + total + ").");
            }
        });
    }

    private boolean coincide(Reserva existente, CrearReservaRequest dto, String telefono) {
        if (!existente.getTelefono().equals(telefono) || !existente.getFechaRetiro().equals(dto.fechaRetiro())) return false;
        Map<UUID, Integer> guardadas = new HashMap<>();
        for (ReservaDetalle d : detalleRepository.findByReservaIdOrderByPresentacionId(existente.getId())) {
            guardadas.put(d.getPresentacion().getId(), d.getCantidad());
        }
        Map<UUID, Integer> pedidas = new HashMap<>();
        for (CrearReservaRequest.Linea l : dto.lineas()) pedidas.put(l.presentacionId(), l.cantidad());
        return guardadas.equals(pedidas);
    }

    private String nuevoCodigo() {
        for (int intento = 0; intento < 8; intento++) {
            StringBuilder sb = new StringBuilder(10);
            for (int i = 0; i < 10; i++) sb.append(ALFABETO_CODIGO.charAt(ALEATORIO.nextInt(ALFABETO_CODIGO.length())));
            String codigo = sb.toString();
            if (!reservaRepository.existsByCodigo(codigo)) return codigo;
        }
        throw new IllegalStateException("No se pudo generar un código de reserva único.");
    }

    private record Importe(int cantidad, BigDecimal precio) {}

    private static BigDecimal total(List<Importe> importes) {
        return importes.stream().map(i -> i.precio().multiply(BigDecimal.valueOf(i.cantidad())))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    /** Total de las lineas, o null si alguna no tiene precio (nunca se inventa un total). */
    private static BigDecimal totalDe(List<ReservaDetalle> lineas) {
        if (lineas.stream().anyMatch(d -> d.getPrecioUnitario() == null)) return null;
        return total(lineas.stream().map(d -> new Importe(d.getCantidad(), d.getPrecioUnitario())).toList());
    }

    private static boolean iguales(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return a == null && b == null;
        return a.compareTo(b) == 0;
    }

    private ReservaPublicaResponse aPublica(Reserva r, LocalDateTime ahora, LocalDate hoy) {
        List<ReservaDetalle> lineas = detalleRepository.findByReservaIdOrderByPresentacionId(r.getId());
        return new ReservaPublicaResponse(r.getCodigo(), r.estadoEfectivo(ahora, hoy), r.getFechaRetiro(), r.getFechaMaximaRetiro(),
                r.getExpiraEn(), lineas.stream().anyMatch(d -> d.getPrecioUnitario() == null), totalDe(lineas),
                lineas.stream().map(d -> new ReservaPublicaResponse.Linea(d.getPresentacion().getId(), d.getPresentacion().getProducto().getNombre(),
                        d.getPresentacion().getNombre(), d.getPresentacion().getUnidadConteo(), d.getCantidad(), d.getPrecioUnitario(),
                        subtotal(d))).toList());
    }

    private static BigDecimal subtotal(ReservaDetalle d) {
        return d.getPrecioUnitario() == null ? null
                : d.getPrecioUnitario().multiply(BigDecimal.valueOf(d.getCantidad())).setScale(2, RoundingMode.HALF_UP);
    }

    private ReservaResponse aDetalle(Reserva r) {
        LocalDateTime ahora = regla.ahora();
        LocalDate hoy = ahora.toLocalDate();
        List<ReservaDetalle> lineas = detalleRepository.findByReservaIdOrderByPresentacionId(r.getId());
        EstadoReserva efectivo = r.estadoEfectivo(ahora, hoy);
        ReservaResponse.Aceptacion aceptacion = r.getTotalAceptado() == null ? new ReservaResponse.Aceptacion(null, null, null, null, null)
                : new ReservaResponse.Aceptacion(r.getTotalAceptado(), r.getAceptadoEn(), r.getAceptadoMedio(), r.getAceptadoNota(),
                        r.getAceptadoPor() == null ? null : usuarioRepository.findById(r.getAceptadoPor()).map(Usuario::getNombre).orElse(null));
        List<ReservaResponse.Evento> eventos = eventoRepository.findByReservaIdOrderByCreadoEnAscIdAsc(r.getId()).stream()
                .map(e -> new ReservaResponse.Evento(e.getTipo(), e.getUsuario() == null ? null : e.getUsuario().getNombre(), e.getMotivo(), e.getCreadoEn()))
                .toList();
        return new ReservaResponse(r.getId(), r.getCodigo(), efectivo, r.getNombreCliente(), r.getTelefono(), r.getCorreo(), r.getNotaCliente(),
                r.getFechaRetiro(), r.getFechaMaximaRetiro(), r.getCreadoEn(), r.getExpiraEn(), totalDe(lineas),
                lineas.stream().anyMatch(d -> d.getPrecioUnitario() == null), r.isRequiereAceptacion(), aceptacion,
                comprometida(efectivo) && sinCobertura(r, lineas), r.getVentaId(),
                lineas.stream().map(d -> new ReservaResponse.Linea(d.getPresentacion().getId(), d.getPresentacion().getProducto().getNombre(),
                        d.getPresentacion().getNombre(), d.getPresentacion().getUnidadConteo(), d.getCantidad(), d.getPrecioUnitario(), subtotal(d))).toList(),
                eventos);
    }

    private static boolean comprometida(EstadoReserva efectivo) {
        return efectivo == EstadoReserva.PENDIENTE || efectivo == EstadoReserva.CONFIRMADA;
    }

    /** ¿Alguna linea de esta reserva ya no tiene lotes vendibles que la respalden (por antiguedad, junto a las demas)? */
    private boolean sinCobertura(Reserva r, List<ReservaDetalle> lineas) {
        for (ReservaDetalle d : lineas) {
            UUID presentacionId = d.getPresentacion().getId();
            try {
                var lotes = CoberturaReservas.lotesVendibles(inventarioService.calcularExistencias(presentacionId));
                if (CoberturaReservas.sinCobertura(lotes, compromisos.compromisosDe(presentacionId)).contains(r.getId())) return true;
            } catch (IllegalStateException e) {
                log.error("Reserva {}: existencias inconsistentes en {}; se marca sin cobertura. {}", r.getCodigo(), presentacionId, e.getMessage());
                return true;
            }
        }
        return false;
    }
}
