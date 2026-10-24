package com.planta.acopio.service;

import com.planta.acopio.dto.AjustePagoResponse;
import com.planta.acopio.entity.AjustePago;
import com.planta.acopio.entity.CorreccionRegistro;
import com.planta.acopio.entity.EstadoAjustePago;
import com.planta.acopio.entity.LineaPago;
import com.planta.acopio.entity.Pago;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.AjustePagoRepository;
import com.planta.acopio.repository.LineaPagoRepository;
import com.planta.acopio.repository.PagoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Fase 3, decision de negocio 1 (opcion C, confirmada): Pago y RegistroAcopio
// siguen inmutables -- una correccion post-liquidacion genera un ajuste
// separado y auditable, nunca recalcula el pago original. Liquidacion manual
// por ADMIN (decision confirmada): PagoService.generar() no se toca.
//
// Fase B: el ajuste ya no parte del "ultimo valor corregido" deducido de otro ajuste, sino de los litros CONTABILIZADOS de la
// entrega (los que se liquidaron + lo que ya sumaron sus ajustes), y se valora al precio efectivamente aplicado, incluidos los
// ajustes de calidad del pago (PrecioAplicado). Mientras esta PENDIENTE el precio sigue al del pago; al liquidarlo se congela.
//
// Fase C: el precio es el de LA ENTREGA (su ajuste de calidad propio si lo tiene; si no, el del pago), no solo el del pago.
@Service
public class AjustePagoService {

    private final AjustePagoRepository repository;
    private final PagoRepository pagoRepository;
    private final LineaPagoRepository lineaPagoRepository;
    private final PrecioAplicado precioAplicado;

    public AjustePagoService(AjustePagoRepository repository, PagoRepository pagoRepository,
                              LineaPagoRepository lineaPagoRepository, PrecioAplicado precioAplicado) {
        this.repository = repository;
        this.pagoRepository = pagoRepository;
        this.lineaPagoRepository = lineaPagoRepository;
        this.precioAplicado = precioAplicado;
    }

    // Invocado desde CorreccionRegistroService.anexar() para CADA correccion
    // anexada. Si el registro no estaba liquidado (caso normal, sin pago
    // todavia), no genera nada -- no es un error, es el camino esperado: la
    // correccion se aplicara cuando se liquide (PagoService.generar).
    @Transactional
    public void generarSiCorrespondeAjuste(RegistroAcopio registro, CorreccionRegistro correccion) {
        Pago pago = pagoRepository.findByRegistroLiquidadoId(registro.getId()).orElse(null);
        if (pago == null) {
            return;
        }

        Optional<LineaPago> linea = lineaPagoRepository.findByRegistroAcopioId(registro.getId());
        BigDecimal contabilizados = litrosContabilizados(registro, linea);
        BigDecimal diferencia = correccion.getLitrosCorregido().subtract(contabilizados);
        if (diferencia.signum() == 0) {
            return; // ya estaba contabilizado: nada que ajustar
        }

        AjustePago ajuste = new AjustePago();
        ajuste.setPago(pago);
        ajuste.setRegistroAcopio(registro);
        ajuste.setCorreccionRegistro(correccion);
        ajuste.setLitrosContabilizadosAntes(contabilizados);
        ajuste.setLitrosDiferencia(diferencia);
        // Precio efectivamente aplicado a la entrega AHORA (su ajuste de calidad o, si no tiene, el del pago con sus ajustes).
        ajuste.setPrecioLitro(precioDeLaEntrega(pago, linea));
        repository.save(ajuste);
    }

    // Litros que el pago ya tiene en cuenta para esta entrega: los liquidados mas la suma de sus ajustes. Pagos anteriores a V28
    // sin linea no existen tras la migracion; el respaldo conserva el criterio anterior por si faltara alguna.
    private BigDecimal litrosContabilizados(RegistroAcopio registro, Optional<LineaPago> linea) {
        return linea
                .map(l -> l.getLitrosLiquidados().add(repository.sumaLitrosDiferenciaDelRegistro(registro.getId())))
                .orElseGet(() -> repository.findFirstByRegistroAcopio_IdOrderByCreadoEnDesc(registro.getId())
                        .map(ultimo -> ultimo.getCorreccionRegistro().getLitrosCorregido())
                        .orElse(registro.getLitros()));
    }

    private BigDecimal precioDeLaEntrega(Pago pago, Optional<LineaPago> linea) {
        return linea.map(precioAplicado::deLinea).orElseGet(() -> precioAplicado.efectivoDelPago(pago));
    }

    @Transactional(readOnly = true)
    public List<AjustePagoResponse> listarPorPago(UUID pagoId) {
        pagoRepository.findById(pagoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Pago no encontrado: " + pagoId));
        return responder(repository.findByPagoIdOrderByCreadoEnDesc(pagoId));
    }

    @Transactional(readOnly = true)
    public List<AjustePagoResponse> listarPendientesPorProveedor(UUID proveedorId) {
        List<AjustePago> ajustes = repository.findByEstadoAndPago_Proveedor_IdOrderByCreadoEnDesc(EstadoAjustePago.PENDIENTE, proveedorId);
        return responder(ajustes);
    }

    // Pagos efectivos: suma de los montos (± y vigentes) de los ajustes de litros de cada liquidacion, para su saldo.
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> montosPorPago(Collection<UUID> pagoIds) {
        Map<UUID, BigDecimal> montos = new HashMap<>();
        if (pagoIds.isEmpty()) {
            return montos;
        }
        for (AjustePagoResponse a : responder(repository.findByPagoIdIn(pagoIds))) {
            montos.merge(a.pagoId(), a.monto(), BigDecimal::add);
        }
        return montos;
    }

    // Liquidacion manual (decision confirmada): ADMIN decide cuando y como se
    // salda (efectivo aparte, descontado del proximo pago a su criterio,
    // etc.) -- no hay vinculo automatico a un pago_liquidacion aqui.
    // Al liquidar se congela el precio: desde ahi ya no sigue a los ajustes de calidad del pago.
    @Transactional
    public AjustePagoResponse liquidar(UUID id) {
        AjustePago ajuste = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Ajuste de pago no encontrado: " + id));
        if (ajuste.getEstado() == EstadoAjustePago.LIQUIDADO) {
            throw new ValidacionNegocioException("El ajuste " + id + " ya estaba liquidado");
        }
        BigDecimal precio = precioDeLaEntrega(ajuste.getPago(),
                lineaPagoRepository.findByRegistroAcopioId(ajuste.getRegistroAcopio().getId()));
        ajuste.setPrecioLitroLiquidado(precio);
        ajuste.setEstado(EstadoAjustePago.LIQUIDADO);
        return toResponse(ajuste, precio);
    }

    // Precio vigente de cada ajuste: el de SU entrega (ajuste de calidad propio o el del pago). Con dos consultas para todos; si la entrega
    // no tiene linea (no deberia ocurrir tras V28) se usa el precio efectivo de su pago.
    private List<AjustePagoResponse> responder(Collection<AjustePago> ajustes) {
        if (ajustes.isEmpty()) {
            return List.of();
        }
        Map<UUID, BigDecimal> delPago = precioAplicado.efectivosDePagos(ajustes.stream().map(AjustePago::getPago).toList());
        Map<UUID, BigDecimal> porRegistro = new HashMap<>();
        List<LineaPago> lineas = lineaPagoRepository.findByRegistroAcopioIdIn(
                ajustes.stream().map(a -> a.getRegistroAcopio().getId()).distinct().toList());
        if (!lineas.isEmpty()) {
            Map<UUID, BigDecimal> porLinea = precioAplicado.deLineas(lineas);
            for (LineaPago l : lineas) {
                porRegistro.put(l.getRegistroAcopio().getId(), porLinea.get(l.getId()));
            }
        }
        return ajustes.stream()
                .map(a -> toResponse(a, porRegistro.getOrDefault(a.getRegistroAcopio().getId(), delPago.get(a.getPago().getId()))))
                .toList();
    }

    // precioDelPago = precio efectivo actual del pago. PENDIENTE se valora a ese precio; LIQUIDADO, al que se congelo.
    private AjustePagoResponse toResponse(AjustePago a, BigDecimal precioDelPago) {
        BigDecimal precio;
        if (a.getEstado() == EstadoAjustePago.LIQUIDADO) {
            precio = a.getPrecioLitroLiquidado() != null ? a.getPrecioLitroLiquidado() : a.getPrecioLitro();
        } else {
            precio = precioDelPago != null ? precioDelPago : a.getPrecioLitro();
        }
        return new AjustePagoResponse(
                a.getId(), a.getPago().getId(), a.getRegistroAcopio().getId(), a.getCorreccionRegistro().getId(),
                a.getLitrosDiferencia(), precio, PrecioAplicado.monto(a.getLitrosDiferencia(), precio),
                a.getEstado().name(), a.getCreadoEn(), a.getPrecioLitro(), a.getMonto());
    }
}
