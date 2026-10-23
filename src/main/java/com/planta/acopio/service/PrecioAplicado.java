package com.planta.acopio.service;

import com.planta.acopio.entity.AjusteCalidadEntrega;
import com.planta.acopio.entity.AjustePrecioPago;
import com.planta.acopio.entity.LineaPago;
import com.planta.acopio.entity.Pago;
import com.planta.acopio.repository.AjusteCalidadEntregaRepository;
import com.planta.acopio.repository.AjustePrecioPagoRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Fase B/C: precio por litro EFECTIVAMENTE aplicado a las entregas de un pago, incluidos los ajustes de calidad. Es el unico punto donde
// se decide el precio de una entrega.
//
// - Precio del PAGO: el del AjustePrecioPago mas reciente (V24, de un pago completo) o, sin ajustes, el precio normal del pago.
// - Precio de una ENTREGA (Fase C): si tiene un ajuste de calidad propio (AjusteCalidadEntrega), ese; si no, el del pago. "La entrega
//   manda": un ajuste de calidad propio ignora el ajuste de precio del pago completo, que solo alcanza a las entregas sin precio propio.
// Todo se deriva al leer: no se reescribe ninguna fila.
@Service
public class PrecioAplicado {

    private final AjustePrecioPagoRepository ajustePrecioRepository;
    private final AjusteCalidadEntregaRepository ajusteCalidadRepository;

    public PrecioAplicado(AjustePrecioPagoRepository ajustePrecioRepository, AjusteCalidadEntregaRepository ajusteCalidadRepository) {
        this.ajustePrecioRepository = ajustePrecioRepository;
        this.ajusteCalidadRepository = ajusteCalidadRepository;
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

    // Precio aplicado a UNA entrega: su ajuste de calidad mas reciente o, sin el, el precio efectivo de su pago.
    public BigDecimal deLinea(LineaPago linea) {
        return ajusteCalidadRepository.findFirstByPagoLineaIdOrderByCreadoEnDescIdDesc(linea.getId())
                .map(AjusteCalidadEntrega::getPrecioNuevo)
                .orElseGet(() -> efectivoDelPago(linea.getPago()));
    }

    // Igual, para varias entregas con dos consultas. La clave es el id de la linea; todas las lineas pedidas aparecen.
    public Map<UUID, BigDecimal> deLineas(Collection<LineaPago> lineas) {
        Map<UUID, BigDecimal> precios = new HashMap<>();
        if (lineas.isEmpty()) {
            return precios;
        }
        Map<UUID, BigDecimal> delPago = efectivosDePagos(lineas.stream().map(LineaPago::getPago).toList());
        for (LineaPago l : lineas) {
            precios.put(l.getId(), delPago.get(l.getPago().getId()));
        }
        for (AjusteCalidadEntrega a : ajusteCalidadRepository.findByPagoLineaIdInOrderByCreadoEnAscIdAsc(
                lineas.stream().map(LineaPago::getId).toList())) { // cronologico: el ultimo pisa a los anteriores
            precios.put(a.getPagoLinea().getId(), a.getPrecioNuevo());
        }
        return precios;
    }

    // Lineas con precio propio de calidad (por id de linea), para distinguirlas de las que siguen el precio del pago.
    public Map<UUID, AjusteCalidadEntrega> ultimosAjustesDeCalidad(Collection<LineaPago> lineas) {
        Map<UUID, AjusteCalidadEntrega> propios = new HashMap<>();
        if (lineas.isEmpty()) {
            return propios;
        }
        for (AjusteCalidadEntrega a : ajusteCalidadRepository.findByPagoLineaIdInOrderByCreadoEnAscIdAsc(
                lineas.stream().map(LineaPago::getId).toList())) {
            propios.put(a.getPagoLinea().getId(), a);
        }
        return propios;
    }

    // Total reconocido de un pago: suma de (litros liquidados x precio aplicado) de cada entrega, redondeado UNA sola vez al final
    // (a centimos, half-up). Redondear por linea acumularia error; con precio unico por pago daria 589.80 donde lo correcto es 590.00.
    public static BigDecimal totalReconocido(Collection<LineaPago> lineas, Map<UUID, BigDecimal> precioPorLinea) {
        BigDecimal suma = BigDecimal.ZERO;
        for (LineaPago l : lineas) {
            suma = suma.add(l.getLitrosLiquidados().multiply(precioPorLinea.get(l.getId())));
        }
        return suma.setScale(2, RoundingMode.HALF_UP);
    }

    // Mismo redondeo que la columna generada pago.total (NUMERIC(10,2), half-up).
    public static BigDecimal monto(BigDecimal litros, BigDecimal precioLitro) {
        return litros.multiply(precioLitro).setScale(2, RoundingMode.HALF_UP);
    }
}
