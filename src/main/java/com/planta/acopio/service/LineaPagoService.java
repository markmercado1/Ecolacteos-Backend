package com.planta.acopio.service;

import com.planta.acopio.dto.LineaPagoResponse;
import com.planta.acopio.entity.AjustePago;
import com.planta.acopio.entity.LineaPago;
import com.planta.acopio.entity.Pago;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.AjustePagoRepository;
import com.planta.acopio.repository.LineaPagoRepository;
import com.planta.acopio.repository.PagoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Fase B: detalle por entrega de un pago (solo lectura): original / liquidado / contabilizado / efectivo, y precio aplicado.
@Service
public class LineaPagoService {

    private final PagoRepository pagoRepository;
    private final LineaPagoRepository lineaPagoRepository;
    private final AjustePagoRepository ajustePagoRepository;
    private final LitrosEfectivos litrosEfectivos;
    private final PrecioAplicado precioAplicado;

    public LineaPagoService(PagoRepository pagoRepository, LineaPagoRepository lineaPagoRepository,
                            AjustePagoRepository ajustePagoRepository, LitrosEfectivos litrosEfectivos,
                            PrecioAplicado precioAplicado) {
        this.pagoRepository = pagoRepository;
        this.lineaPagoRepository = lineaPagoRepository;
        this.ajustePagoRepository = ajustePagoRepository;
        this.litrosEfectivos = litrosEfectivos;
        this.precioAplicado = precioAplicado;
    }

    @Transactional(readOnly = true)
    public List<LineaPagoResponse> listarPorPago(UUID pagoId) {
        Pago pago = pagoRepository.findById(pagoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Pago no encontrado: " + pagoId));
        List<LineaPago> lineas = lineaPagoRepository.findByPagoIdOrderByCreadoEnAscIdAsc(pagoId);

        Map<UUID, BigDecimal> sumaAjustes = new HashMap<>();
        for (AjustePago a : ajustePagoRepository.findByPagoId(pagoId)) {
            sumaAjustes.merge(a.getRegistroAcopio().getId(), a.getLitrosDiferencia(), BigDecimal::add);
        }
        Map<UUID, LitrosEfectivos.Efectivo> efectivos = litrosEfectivos.deRegistros(
                lineas.stream().map(LineaPago::getRegistroAcopio).toList());
        BigDecimal precio = precioAplicado.efectivoDelPago(pago);

        return lineas.stream().map(l -> {
            RegistroAcopio r = l.getRegistroAcopio();
            return new LineaPagoResponse(
                    r.getId(), r.getFechaHora(), l.getLitrosOriginales(), l.getLitrosLiquidados(),
                    l.getLitrosLiquidados().add(sumaAjustes.getOrDefault(r.getId(), BigDecimal.ZERO)),
                    efectivos.get(r.getId()).litros(), l.getPrecioBase(), precio,
                    l.getCorreccionAplicada() != null ? l.getCorreccionAplicada().getId() : null);
        }).toList();
    }
}
