package com.planta.acopio.service;

import com.planta.acopio.dto.AjustePagoResponse;
import com.planta.acopio.entity.AjustePago;
import com.planta.acopio.entity.CorreccionRegistro;
import com.planta.acopio.entity.EstadoAjustePago;
import com.planta.acopio.entity.Pago;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.AjustePagoRepository;
import com.planta.acopio.repository.PagoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// Fase 3, decision de negocio 1 (opcion C, confirmada): Pago y RegistroAcopio
// siguen inmutables -- una correccion post-liquidacion genera un ajuste
// separado y auditable, nunca recalcula el pago original. Liquidacion manual
// por ADMIN (decision confirmada): PagoService.generar() no se toca.
@Service
public class AjustePagoService {

    private final AjustePagoRepository repository;
    private final PagoRepository pagoRepository;

    public AjustePagoService(AjustePagoRepository repository, PagoRepository pagoRepository) {
        this.repository = repository;
        this.pagoRepository = pagoRepository;
    }

    // Invocado desde CorreccionRegistroService.anexar() para CADA correccion
    // anexada. Si el registro no estaba liquidado (caso normal, sin pago
    // todavia), no genera nada -- no es un error, es el camino esperado.
    @Transactional
    public void generarSiCorrespondeAjuste(RegistroAcopio registro, CorreccionRegistro correccion) {
        Pago pago = pagoRepository.findByRegistroLiquidadoId(registro.getId()).orElse(null);
        if (pago == null) {
            return;
        }

        // Fix correcciones multiples (confirmado): CorreccionRegistro.litrosAnterior
        // NO se usa aqui a proposito -- siempre es el original inmutable (eso no
        // cambia, se preserva integro para el historial de CorreccionRegistro).
        // El baseline ECONOMICO para este ajuste es el ultimo AjustePago ya
        // generado para este registro (lo que ya quedo contemplado); si no hay
        // ninguno todavia, recien ahi cae al litro original. Esto evita que una
        // segunda correccion vuelva a contar, contra el original, un tramo que
        // ya quedo cubierto por el ajuste anterior -- sin tocar ni anular ese
        // ajuste anterior (queda con el mismo estado que ya tenia).
        BigDecimal baseline = repository.findFirstByRegistroAcopio_IdOrderByCreadoEnDesc(registro.getId())
                .map(ultimoAjuste -> ultimoAjuste.getCorreccionRegistro().getLitrosCorregido())
                .orElse(registro.getLitros());

        AjustePago ajuste = new AjustePago();
        ajuste.setPago(pago);
        ajuste.setRegistroAcopio(registro);
        ajuste.setCorreccionRegistro(correccion);
        ajuste.setLitrosDiferencia(correccion.getLitrosCorregido().subtract(baseline));
        ajuste.setPrecioLitro(pago.getPrecioLitro()); // decision confirmada: precio del pago original, no el vigente hoy
        repository.save(ajuste);
    }

    @Transactional(readOnly = true)
    public List<AjustePagoResponse> listarPorPago(UUID pagoId) {
        if (!pagoRepository.existsById(pagoId)) {
            throw new RecursoNoEncontradoException("Pago no encontrado: " + pagoId);
        }
        return repository.findByPagoIdOrderByCreadoEnDesc(pagoId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<AjustePagoResponse> listarPendientesPorProveedor(UUID proveedorId) {
        return repository.findByEstadoAndPago_Proveedor_IdOrderByCreadoEnDesc(EstadoAjustePago.PENDIENTE, proveedorId)
                .stream().map(this::toResponse).toList();
    }

    // Liquidacion manual (decision confirmada): ADMIN decide cuando y como se
    // salda (efectivo aparte, descontado del proximo pago a su criterio,
    // etc.) -- no hay vinculo automatico a un pago_liquidacion aqui.
    @Transactional
    public AjustePagoResponse liquidar(UUID id) {
        AjustePago ajuste = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Ajuste de pago no encontrado: " + id));
        if (ajuste.getEstado() == EstadoAjustePago.LIQUIDADO) {
            throw new ValidacionNegocioException("El ajuste " + id + " ya estaba liquidado");
        }
        ajuste.setEstado(EstadoAjustePago.LIQUIDADO);
        return toResponse(ajuste);
    }

    private AjustePagoResponse toResponse(AjustePago a) {
        return new AjustePagoResponse(
                a.getId(), a.getPago().getId(), a.getRegistroAcopio().getId(), a.getCorreccionRegistro().getId(),
                a.getLitrosDiferencia(), a.getPrecioLitro(), a.getMonto(), a.getEstado().name(), a.getCreadoEn());
    }
}
