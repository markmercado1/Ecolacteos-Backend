package com.planta.acopio.service;

import com.planta.acopio.entity.AjustePrecioPago;
import com.planta.acopio.entity.Pago;
import com.planta.acopio.repository.AjustePrecioPagoRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Fase B: precio por litro EFECTIVAMENTE aplicado a las entregas de un pago, incluidos los ajustes de calidad.
// Un AjustePrecioPago es de un pago completo (V24): se atribuye a TODAS las lineas de ese pago, desde que se creo, y se deriva
// al leer -- no se reescribe ninguna fila. Es el unico punto donde se decide el precio de una entrega: cuando exista precio
// propio por entrega (Fase C) tendra precedencia aqui, sin tocar a quienes lo usan.
@Service
public class PrecioAplicado {

    private final AjustePrecioPagoRepository ajustePrecioRepository;

    public PrecioAplicado(AjustePrecioPagoRepository ajustePrecioRepository) {
        this.ajustePrecioRepository = ajustePrecioRepository;
    }

    // El del ajuste de precio mas reciente del pago, o el del pago original si no tiene ajustes.
    public BigDecimal efectivoDelPago(Pago pago) {
        return ajustePrecioRepository.findFirstByPagoIdOrderByCreadoEnDescIdDesc(pago.getId())
                .map(AjustePrecioPago::getPrecioLitroNuevo)
                .orElse(pago.getPrecioLitro());
    }

    // Igual, para varios pagos con UNA consulta. Todos los pagos pedidos aparecen en el resultado.
    public Map<UUID, BigDecimal> efectivosDePagos(Collection<Pago> pagos) {
        Map<UUID, BigDecimal> precios = new HashMap<>();
        if (pagos.isEmpty()) {
            return precios;
        }
        for (Pago p : pagos) {
            precios.put(p.getId(), p.getPrecioLitro());
        }
        for (AjustePrecioPago a : ajustePrecioRepository.findByPagoIdInOrderByCreadoEnAscIdAsc(
                pagos.stream().map(Pago::getId).distinct().toList())) { // cronologico: el ultimo pisa a los anteriores
            precios.put(a.getPago().getId(), a.getPrecioLitroNuevo());
        }
        return precios;
    }

    // Mismo redondeo que la columna generada pago.total (NUMERIC(10,2), half-up).
    public static BigDecimal monto(BigDecimal litros, BigDecimal precioLitro) {
        return litros.multiply(precioLitro).setScale(2, RoundingMode.HALF_UP);
    }
}
