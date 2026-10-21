package com.planta.acopio.service;

import com.planta.acopio.dto.AjustePrecioPagoRequest;
import com.planta.acopio.dto.AjustePrecioPagoResponse;
import com.planta.acopio.entity.AjustePrecioPago;
import com.planta.acopio.entity.Pago;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.AjustePrecioPagoRepository;
import com.planta.acopio.repository.PagoRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// Cambiar el precio por litro de UN pago (p. ej. leche de mala calidad: 1.00 -> 0.80, o un reconocimiento: 1.00 ->
// 1.20). El Pago sigue inmutable: se anexa una fila (append-only, sin update()/delete() aqui, mismo criterio que
// CorreccionRegistroService) con precio anterior, precio nuevo, motivo, usuario y fecha. Afecta SOLO a ese pago: el
// precio general de la leche (precio_litro) y los demas pagos no se tocan. Los AjustePago por correccion de litros
// siguen valorizados al precio del pago ORIGINAL (decision confirmada, no cambia).
@Service
public class AjustePrecioPagoService {

    private final AjustePrecioPagoRepository repository;
    private final PagoRepository pagoRepository;
    private final UsuarioRepository usuarioRepository;

    public AjustePrecioPagoService(AjustePrecioPagoRepository repository, PagoRepository pagoRepository,
                                    UsuarioRepository usuarioRepository) {
        this.repository = repository;
        this.pagoRepository = pagoRepository;
        this.usuarioRepository = usuarioRepository;
    }

    @Transactional
    public AjustePrecioPagoResponse crear(UUID pagoId, AjustePrecioPagoRequest dto) {
        Pago pago = pagoRepository.findById(pagoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Pago no encontrado: " + pagoId));

        String motivo = dto.motivo() == null ? "" : dto.motivo().trim();
        if (motivo.isEmpty()) {
            throw new ValidacionNegocioException("El motivo del ajuste de precio es obligatorio");
        }

        BigDecimal nuevo = dto.precioLitroNuevo();
        if (nuevo == null || nuevo.signum() <= 0) {
            throw new ValidacionNegocioException("El nuevo precio por litro debe ser mayor que cero");
        }

        // El precio "anterior" es el efectivo justo antes de este ajuste (el del pago original o el del ajuste previo).
        BigDecimal anterior = repository.findFirstByPagoIdOrderByCreadoEnDescIdDesc(pagoId)
                .map(AjustePrecioPago::getPrecioLitroNuevo)
                .orElse(pago.getPrecioLitro());
        if (nuevo.compareTo(anterior) == 0) {
            throw new ValidacionNegocioException("El nuevo precio por litro (" + nuevo.stripTrailingZeros().toPlainString()
                    + ") es igual al precio actual de este pago; no hay nada que ajustar");
        }

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));

        AjustePrecioPago ajuste = new AjustePrecioPago();
        ajuste.setPago(pago);
        ajuste.setPrecioLitroAnterior(anterior);
        ajuste.setPrecioLitroNuevo(nuevo);
        ajuste.setMotivo(motivo);
        ajuste.setUsuario(usuario);
        return toResponse(repository.save(ajuste));
    }

    @Transactional(readOnly = true)
    public List<AjustePrecioPagoResponse> listarPorPago(UUID pagoId) {
        if (!pagoRepository.existsById(pagoId)) {
            throw new RecursoNoEncontradoException("Pago no encontrado: " + pagoId);
        }
        return repository.findByPagoIdOrderByCreadoEnDescIdDesc(pagoId).stream().map(this::toResponse).toList();
    }

    private AjustePrecioPagoResponse toResponse(AjustePrecioPago a) {
        BigDecimal litros = a.getPago().getLitrosTotales();
        return new AjustePrecioPagoResponse(
                a.getId(), a.getPago().getId(), a.getPrecioLitroAnterior(), a.getPrecioLitroNuevo(),
                PagoService.calcularTotal(litros, a.getPrecioLitroAnterior()),
                PagoService.calcularTotal(litros, a.getPrecioLitroNuevo()),
                a.getMotivo(), a.getUsuario().getNombre(), a.getCreadoEn());
    }
}
