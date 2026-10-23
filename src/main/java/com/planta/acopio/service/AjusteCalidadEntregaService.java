package com.planta.acopio.service;

import com.planta.acopio.dto.AjusteCalidadEntregaRequest;
import com.planta.acopio.dto.AjusteCalidadEntregaResponse;
import com.planta.acopio.entity.AjusteCalidadEntrega;
import com.planta.acopio.entity.AnalisisCalidad;
import com.planta.acopio.entity.LineaPago;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.AjusteCalidadEntregaRepository;
import com.planta.acopio.repository.AnalisisCalidadRepository;
import com.planta.acopio.repository.LineaPagoRepository;
import com.planta.acopio.repository.PagoRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// Fase C: precio de calidad de UNA entrega de un pago, aplicado a mano por ADMIN con motivo y el analisis de calidad de esa entrega.
// El pago y la linea siguen inmutables: se anexa una fila (append-only, sin update()/delete() aqui, mismo criterio que
// AjustePrecioPagoService). Afecta SOLO a esa entrega; "la entrega manda": su precio propio ignora el ajuste de precio del pago
// completo. No hay regla automatica de calidad a precio (Fase F): el precio lo decide quien lo registra.
@Service
public class AjusteCalidadEntregaService {

    private final AjusteCalidadEntregaRepository repository;
    private final LineaPagoRepository lineaPagoRepository;
    private final AnalisisCalidadRepository analisisRepository;
    private final PagoRepository pagoRepository;
    private final UsuarioRepository usuarioRepository;
    private final PrecioAplicado precioAplicado;

    public AjusteCalidadEntregaService(AjusteCalidadEntregaRepository repository, LineaPagoRepository lineaPagoRepository,
                                       AnalisisCalidadRepository analisisRepository, PagoRepository pagoRepository,
                                       UsuarioRepository usuarioRepository, PrecioAplicado precioAplicado) {
        this.repository = repository;
        this.lineaPagoRepository = lineaPagoRepository;
        this.analisisRepository = analisisRepository;
        this.pagoRepository = pagoRepository;
        this.usuarioRepository = usuarioRepository;
        this.precioAplicado = precioAplicado;
    }

    @Transactional
    public AjusteCalidadEntregaResponse crear(UUID pagoId, UUID registroAcopioId, AjusteCalidadEntregaRequest dto) {
        String motivo = dto.motivo() == null ? "" : dto.motivo().trim();
        if (motivo.isEmpty()) {
            throw new ValidacionNegocioException("El motivo del ajuste de calidad es obligatorio");
        }
        BigDecimal nuevo = dto.precioLitroNuevo();
        if (nuevo == null || nuevo.signum() <= 0) {
            throw new ValidacionNegocioException("El nuevo precio por litro debe ser mayor que cero");
        }

        // Se bloquea la linea: dos ajustes simultaneos sobre la misma entrega se aplican uno tras otro, y el "precio anterior" de cada
        // uno es el que dejo el anterior.
        LineaPago linea = lineaPagoRepository.findByPagoIdAndRegistroParaActualizar(pagoId, registroAcopioId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "La entrega " + registroAcopioId + " no esta liquidada en el pago " + pagoId));

        AnalisisCalidad analisis = analisisRepository.findById(dto.analisisCalidadId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Analisis de calidad no encontrado: " + dto.analisisCalidadId()));
        if (!analisis.getRegistroAcopio().getId().equals(registroAcopioId)) {
            throw new ValidacionNegocioException("El analisis de calidad indicado no corresponde a esta entrega");
        }

        // El precio "anterior" es el aplicado a la entrega justo antes de este ajuste (el propio anterior o el de su pago).
        BigDecimal anterior = precioAplicado.deLinea(linea);
        if (nuevo.compareTo(anterior) == 0) {
            throw new ValidacionNegocioException("El nuevo precio por litro (" + nuevo.stripTrailingZeros().toPlainString()
                    + ") es igual al precio actual de esta entrega; no hay nada que ajustar");
        }

        Usuario usuario = usuarioActual();

        AjusteCalidadEntrega ajuste = new AjusteCalidadEntrega();
        ajuste.setPagoLinea(linea);
        ajuste.setPrecioAnterior(anterior);
        ajuste.setPrecioNuevo(nuevo);
        ajuste.setAnalisisCalidad(analisis);
        ajuste.setMotivo(motivo);
        ajuste.setUsuario(usuario);
        return toResponse(repository.save(ajuste));
    }

    // Historial de los precios de calidad de un pago, del mas reciente al mas antiguo.
    @Transactional(readOnly = true)
    public List<AjusteCalidadEntregaResponse> listarPorPago(UUID pagoId) {
        if (!pagoRepository.existsById(pagoId)) {
            throw new RecursoNoEncontradoException("Pago no encontrado: " + pagoId);
        }
        return repository.findByPagoLineaPagoIdOrderByCreadoEnDescIdDesc(pagoId).stream().map(this::toResponse).toList();
    }

    private Usuario usuarioActual() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));
    }

    private AjusteCalidadEntregaResponse toResponse(AjusteCalidadEntrega a) {
        LineaPago linea = a.getPagoLinea();
        BigDecimal litros = linea.getLitrosLiquidados();
        return new AjusteCalidadEntregaResponse(
                a.getId(), linea.getPago().getId(), linea.getRegistroAcopio().getId(),
                a.getAnalisisCalidad().getId(), a.getAnalisisCalidad().getFolioMuestra(),
                a.getAnalisisCalidad().getResultado() != null ? a.getAnalisisCalidad().getResultado().name() : null,
                litros, linea.getPrecioBase(), a.getPrecioAnterior(), a.getPrecioNuevo(),
                PrecioAplicado.monto(litros, a.getPrecioNuevo().subtract(linea.getPrecioBase())),
                a.getMotivo(), a.getUsuario().getNombre(), a.getCreadoEn());
    }
}
