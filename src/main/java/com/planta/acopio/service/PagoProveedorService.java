package com.planta.acopio.service;

import com.planta.acopio.dto.AplicacionPagoRequest;
import com.planta.acopio.dto.AplicacionPagoResponse;
import com.planta.acopio.dto.AplicarPagoProveedorRequest;
import com.planta.acopio.dto.PagoProveedorRequest;
import com.planta.acopio.dto.PagoProveedorResponse;
import com.planta.acopio.dto.SaldoPagoResponse;
import com.planta.acopio.entity.EstadoPagoProveedor;
import com.planta.acopio.entity.ModalidadPagoProveedor;
import com.planta.acopio.entity.Pago;
import com.planta.acopio.entity.PagoProveedor;
import com.planta.acopio.entity.PagoProveedorAplicacion;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.PagoProveedorAplicacionRepository;
import com.planta.acopio.repository.PagoProveedorRepository;
import com.planta.acopio.repository.PagoRepository;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// Pagos efectivos: el dinero realmente entregado al proveedor. `pago` sigue siendo la liquidacion (la deuda); aqui se registra el
// desembolso (efectivo, transferencia o deposito) y se aplica a una o varias liquidaciones. Un desembolso sin aplicar es un adelanto.
//
//   * Idempotencia: reenviar el mismo `uuidCliente` (o la misma operacion bancaria con los mismos datos) devuelve el pago ya registrado,
//     no crea otro. Si dos solicitudes iguales llegan a la vez, la restriccion unica de la BD deja pasar una y la otra relee el resultado
//     (por eso `registrar` NO es transaccional: reintenta en una transaccion nueva tras la violacion).
//   * Concurrencia: aplicar o anular bloquea el desembolso; aplicar bloquea ademas las liquidaciones (en orden de id). Asi ninguna
//     aplicacion supera lo que queda del desembolso ni el saldo de la liquidacion, aunque lleguen dos a la vez.
//   * Anulacion: cambia el estado (motivo, usuario, fecha) y conserva la fila y sus aplicaciones, que dejan de contar en los saldos.
//   * Los importes no se editan. No se guardan cuentas completas: solo banco y numero de operacion.
@Service
public class PagoProveedorService {

    /** Resultado de registrar: `creado` = false cuando fue un reenvio y se devolvio el pago ya existente. */
    public record Resultado(PagoProveedorResponse pago, boolean creado) {}

    private final PagoProveedorRepository repository;
    private final PagoProveedorAplicacionRepository aplicacionRepository;
    private final PagoRepository pagoRepository;
    private final ProveedorRepository proveedorRepository;
    private final UsuarioRepository usuarioRepository;
    private final SaldoPagoService saldoService;
    private final TransactionTemplate transaccion;

    public PagoProveedorService(PagoProveedorRepository repository, PagoProveedorAplicacionRepository aplicacionRepository,
                                PagoRepository pagoRepository, ProveedorRepository proveedorRepository,
                                UsuarioRepository usuarioRepository, SaldoPagoService saldoService,
                                PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.aplicacionRepository = aplicacionRepository;
        this.pagoRepository = pagoRepository;
        this.proveedorRepository = proveedorRepository;
        this.usuarioRepository = usuarioRepository;
        this.saldoService = saldoService;
        this.transaccion = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ registrar

    public Resultado registrar(PagoProveedorRequest dto) {
        Datos datos = Datos.de(dto);
        datos.validar();
        try {
            return enTransaccion(() -> registrarEnTransaccion(dto, datos));
        } catch (DataIntegrityViolationException carrera) {
            // Otra solicitud igual (mismo uuidCliente u operacion bancaria) gano la carrera: se relee su resultado en una transaccion nueva.
            Resultado previo = enTransaccion(() -> existente(dto, datos));
            if (previo == null) {
                throw carrera;
            }
            return previo;
        }
    }

    private Resultado registrarEnTransaccion(PagoProveedorRequest dto, Datos datos) {
        Resultado previo = existente(dto, datos);
        if (previo != null) {
            return previo;
        }
        Proveedor proveedor = proveedorRepository.findById(dto.proveedorId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + dto.proveedorId()));
        Usuario usuario = usuarioActual();

        PagoProveedor pago = new PagoProveedor();
        pago.setUuidCliente(dto.uuidCliente().trim());
        pago.setProveedor(proveedor);
        pago.setModalidad(dto.modalidad());
        pago.setMonto(dto.monto());
        pago.setConcepto(datos.concepto);
        pago.setBanco(datos.banco);
        pago.setNroOperacion(datos.nroOperacion);
        pago.setNroRecibo(datos.nroRecibo);
        pago.setObservacion(datos.observacion);
        pago.setUsuario(usuario);
        pago = repository.saveAndFlush(pago);

        if (dto.aplicaciones() != null && !dto.aplicaciones().isEmpty()) {
            aplicarEnTransaccion(pago, dto.aplicaciones(), usuario);
        }
        return new Resultado(responder(pago), true);
    }

    // El pago ya registrado con ese uuidCliente o con esa operacion bancaria, o null si no hay. Un reenvio con otros datos es un conflicto.
    private Resultado existente(PagoProveedorRequest dto, Datos datos) {
        PagoProveedor porUuid = repository.findByUuidCliente(dto.uuidCliente().trim()).orElse(null);
        if (porUuid != null) {
            if (!mismoPago(porUuid, dto, datos)) {
                throw new ConflictoNegocioException("El identificador " + dto.uuidCliente().trim() + " ya se uso para otro pago con datos distintos");
            }
            return new Resultado(responder(porUuid), false);
        }
        if (datos.banco != null && datos.nroOperacion != null) {
            PagoProveedor porOperacion = repository.findOperacionVigente(datos.banco, datos.nroOperacion).orElse(null);
            if (porOperacion != null) {
                if (!mismoPago(porOperacion, dto, datos)) {
                    throw new ConflictoNegocioException("La operacion " + datos.nroOperacion + " de " + datos.banco
                            + " ya esta registrada en otro pago vigente");
                }
                return new Resultado(responder(porOperacion), false);
            }
        }
        return null;
    }

    private static boolean mismoPago(PagoProveedor p, PagoProveedorRequest dto, Datos d) {
        return p.getProveedor().getId().equals(dto.proveedorId())
                && p.getModalidad() == dto.modalidad()
                && p.getMonto().compareTo(dto.monto()) == 0
                && Datos.igual(p.getBanco(), d.banco) && Datos.igual(p.getNroOperacion(), d.nroOperacion)
                && Datos.igual(p.getNroRecibo(), d.nroRecibo) && Datos.igual(p.getConcepto(), d.concepto)
                && Datos.igual(p.getObservacion(), d.observacion);
    }

    // ------------------------------------------------------------------ aplicar

    @Transactional
    public PagoProveedorResponse aplicar(UUID pagoProveedorId, AplicarPagoProveedorRequest dto) {
        PagoProveedor pago = repository.findByIdParaActualizar(pagoProveedorId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Pago a proveedor no encontrado: " + pagoProveedorId));
        aplicarEnTransaccion(pago, dto.aplicaciones(), usuarioActual());
        return responder(pago);
    }

    // El desembolso ya esta bloqueado (o es nuevo). Bloquea las liquidaciones en orden de id y comprueba los topes ya con los saldos
    // vigentes: lo que queda del desembolso y el saldo de cada liquidacion.
    private void aplicarEnTransaccion(PagoProveedor pago, List<AplicacionPagoRequest> aplicaciones, Usuario usuario) {
        if (pago.getEstado() != EstadoPagoProveedor.REGISTRADO) {
            throw new ConflictoNegocioException("El pago esta anulado: no se puede aplicar");
        }
        Set<UUID> vistos = new HashSet<>();
        BigDecimal solicitado = BigDecimal.ZERO;
        for (AplicacionPagoRequest a : aplicaciones) {
            if (!vistos.add(a.pagoId())) {
                throw new ValidacionNegocioException("La liquidacion " + a.pagoId() + " aparece dos veces en las aplicaciones");
            }
            solicitado = solicitado.add(a.monto());
        }
        List<Pago> pagos = pagoRepository.findByIdInParaActualizar(vistos);
        if (pagos.size() != vistos.size()) {
            throw new RecursoNoEncontradoException("Alguna de las liquidaciones indicadas no existe");
        }
        for (Pago p : pagos) {
            if (!p.getProveedor().getId().equals(pago.getProveedor().getId())) {
                throw new ValidacionNegocioException("La liquidacion " + p.getId() + " no es del proveedor del pago");
            }
        }
        BigDecimal disponible = pago.getMonto().subtract(aplicacionRepository.sumaDelDesembolso(pago.getId()));
        if (solicitado.compareTo(disponible) > 0) {
            throw new ConflictoNegocioException("El monto a aplicar (" + solicitado.toPlainString() + ") supera lo que le queda al pago ("
                    + disponible.toPlainString() + ")");
        }
        Map<UUID, SaldoPagoResponse> saldos = saldoService.calcular(pagos).stream()
                .collect(Collectors.toMap(SaldoPagoResponse::pagoId, s -> s));
        Map<UUID, Pago> porId = pagos.stream().collect(Collectors.toMap(Pago::getId, p -> p));
        List<PagoProveedorAplicacion> nuevas = new ArrayList<>();
        for (AplicacionPagoRequest a : aplicaciones) {
            BigDecimal saldo = saldos.get(a.pagoId()).saldo();
            if (a.monto().compareTo(saldo) > 0) {
                throw new ConflictoNegocioException("El monto a aplicar (" + a.monto().toPlainString() + ") supera el saldo de la liquidacion "
                        + a.pagoId() + " (" + saldo.max(BigDecimal.ZERO).toPlainString() + ")");
            }
            PagoProveedorAplicacion nueva = new PagoProveedorAplicacion();
            nueva.setPagoProveedor(pago);
            nueva.setPago(porId.get(a.pagoId()));
            nueva.setMonto(a.monto());
            nueva.setUsuario(usuario);
            nuevas.add(nueva);
        }
        aplicacionRepository.saveAll(nuevas);
    }

    // ------------------------------------------------------------------ anular

    @Transactional
    public PagoProveedorResponse anular(UUID pagoProveedorId, String motivo) {
        String texto = motivo == null ? "" : motivo.trim();
        if (texto.isEmpty()) {
            throw new ValidacionNegocioException("El motivo de la anulacion es obligatorio");
        }
        PagoProveedor pago = repository.findByIdParaActualizar(pagoProveedorId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Pago a proveedor no encontrado: " + pagoProveedorId));
        if (pago.getEstado() == EstadoPagoProveedor.ANULADO) {
            throw new ConflictoNegocioException("El pago ya estaba anulado");
        }
        pago.setEstado(EstadoPagoProveedor.ANULADO);
        pago.setAnuladoMotivo(texto);
        pago.setAnuladoPor(usuarioActual());
        pago.setAnuladoEn(LocalDateTime.now());
        return responder(pago);
    }

    // ------------------------------------------------------------------ consultas

    @Transactional(readOnly = true)
    public PagoProveedorResponse obtener(UUID id) {
        return responder(repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Pago a proveedor no encontrado: " + id)));
    }

    @Transactional(readOnly = true)
    public Page<PagoProveedorResponse> listar(UUID proveedorId, String estado, String busqueda, Pageable pageable) {
        String estadoFiltro = null;
        if (estado != null && !estado.isBlank()) {
            try {
                estadoFiltro = EstadoPagoProveedor.valueOf(estado.trim().toUpperCase()).name();
            } catch (IllegalArgumentException e) {
                throw new ValidacionNegocioException("Estado desconocido: " + estado + " (REGISTRADO o ANULADO)");
            }
        }
        Page<PagoProveedor> pagina = repository.buscar(proveedorId, estadoFiltro, BusquedaTexto.patronLike(busqueda), pageable);
        Map<UUID, List<PagoProveedorAplicacion>> aplicaciones = pagina.getContent().isEmpty() ? Map.of()
                : aplicacionRepository.findByPagoProveedorIdInOrderByCreadoEnAscIdAsc(pagina.getContent().stream().map(PagoProveedor::getId).toList())
                        .stream().collect(Collectors.groupingBy(a -> a.getPagoProveedor().getId()));
        return pagina.map(p -> responder(p, aplicaciones.getOrDefault(p.getId(), List.of())));
    }

    // ------------------------------------------------------------------ internos

    private PagoProveedorResponse responder(PagoProveedor p) {
        return responder(p, aplicacionRepository.findByPagoProveedorIdInOrderByCreadoEnAscIdAsc(List.of(p.getId())));
    }

    private PagoProveedorResponse responder(PagoProveedor p, List<PagoProveedorAplicacion> aplicaciones) {
        BigDecimal aplicado = aplicaciones.stream().map(PagoProveedorAplicacion::getMonto).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sinAplicar = p.getEstado() == EstadoPagoProveedor.REGISTRADO ? p.getMonto().subtract(aplicado) : BigDecimal.ZERO;
        List<AplicacionPagoResponse> filas = aplicaciones.stream().map(a -> new AplicacionPagoResponse(
                a.getId(), a.getPago().getId(), a.getPago().getSemanaInicio(), a.getPago().getSemanaFin(), a.getMonto(),
                a.getUsuario().getNombre(), a.getCreadoEn())).toList();
        return new PagoProveedorResponse(
                p.getId(), p.getUuidCliente(), p.getProveedor().getId(), p.getProveedor().getNombre(), p.getFechaHora(),
                p.getModalidad().name(), p.getMonto(), p.getConcepto(), p.getBanco(), p.getNroOperacion(), p.getNroRecibo(),
                p.getObservacion(), p.getUsuario().getNombre(), p.getEstado().name(), p.getAnuladoMotivo(),
                p.getAnuladoPor() != null ? p.getAnuladoPor().getNombre() : null, p.getAnuladoEn(), aplicado, sinAplicar, filas);
    }

    private <T> T enTransaccion(Supplier<T> accion) {
        return transaccion.execute(estado -> accion.get());
    }

    private Usuario usuarioActual() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));
    }

    // Texto ya normalizado (sin espacios en los extremos; en blanco = ausente) y validaciones que dependen de la modalidad.
    private static final class Datos {
        private final ModalidadPagoProveedor modalidad;
        private final String concepto;
        private final String banco;
        private final String nroOperacion;
        private final String nroRecibo;
        private final String observacion;

        private Datos(PagoProveedorRequest dto) {
            this.modalidad = dto.modalidad();
            this.concepto = limpio(dto.concepto());
            this.banco = limpio(dto.banco());
            this.nroOperacion = limpio(dto.nroOperacion());
            this.nroRecibo = limpio(dto.nroRecibo());
            this.observacion = limpio(dto.observacion());
        }

        static Datos de(PagoProveedorRequest dto) {
            return new Datos(dto);
        }

        void validar() {
            if (modalidad != ModalidadPagoProveedor.EFECTIVO && (banco == null || nroOperacion == null)) {
                throw new ValidacionNegocioException("Una " + modalidad.name().toLowerCase() + " exige el banco y el numero de operacion");
            }
            if ((banco == null) != (nroOperacion == null)) {
                throw new ValidacionNegocioException("El banco y el numero de operacion se indican juntos");
            }
        }

        static String limpio(String texto) {
            if (texto == null) {
                return null;
            }
            String t = texto.trim();
            return t.isEmpty() ? null : t;
        }

        static boolean igual(String a, String b) {
            return a == null ? b == null : b != null && a.trim().equalsIgnoreCase(b.trim());
        }
    }
}
