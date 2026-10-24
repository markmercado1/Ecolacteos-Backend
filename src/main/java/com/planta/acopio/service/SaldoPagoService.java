package com.planta.acopio.service;

import com.planta.acopio.dto.SaldoPagoResponse;
import com.planta.acopio.dto.SaldoProveedorResponse;
import com.planta.acopio.entity.Pago;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.PagoProveedorAplicacionRepository;
import com.planta.acopio.repository.PagoProveedorRepository;
import com.planta.acopio.repository.PagoRepository;
import com.planta.acopio.repository.ProveedorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Pagos efectivos: saldos DERIVADOS (nada se guarda ni hay un estado "pagado" duplicado).
//   saldo de una liquidacion = total a pagar (total reconocido, con precios de calidad) + ajustes de litros posteriores (+/-, valorados a su
//   precio vigente o congelado) - aplicaciones de desembolsos no anulados. Negativo = saldo a favor del proveedor.
// Lo calcula el servidor; el panel solo lo muestra.
@Service
public class SaldoPagoService {

    private final PagoRepository pagoRepository;
    private final PagoService pagoService;
    private final AjustePagoService ajustePagoService;
    private final PagoProveedorAplicacionRepository aplicacionRepository;
    private final PagoProveedorRepository pagoProveedorRepository;
    private final ProveedorRepository proveedorRepository;

    public SaldoPagoService(PagoRepository pagoRepository, PagoService pagoService, AjustePagoService ajustePagoService,
                            PagoProveedorAplicacionRepository aplicacionRepository, PagoProveedorRepository pagoProveedorRepository,
                            ProveedorRepository proveedorRepository) {
        this.pagoRepository = pagoRepository;
        this.pagoService = pagoService;
        this.ajustePagoService = ajustePagoService;
        this.aplicacionRepository = aplicacionRepository;
        this.pagoProveedorRepository = pagoProveedorRepository;
        this.proveedorRepository = proveedorRepository;
    }

    @Transactional(readOnly = true)
    public List<SaldoPagoResponse> deLosPagos(Collection<UUID> pagoIds) {
        List<UUID> ids = pagoIds.stream().distinct().toList();
        List<Pago> pagos = pagoRepository.findAllById(ids);
        if (pagos.size() != ids.size()) {
            throw new RecursoNoEncontradoException("Alguno de los pagos indicados no existe");
        }
        Map<UUID, SaldoPagoResponse> saldos = new HashMap<>();
        calcular(pagos).forEach(s -> saldos.put(s.pagoId(), s));
        return ids.stream().map(saldos::get).toList(); // en el orden pedido
    }

    @Transactional(readOnly = true)
    public SaldoProveedorResponse delProveedor(UUID proveedorId) {
        Proveedor proveedor = proveedorRepository.findById(proveedorId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + proveedorId));
        List<SaldoPagoResponse> liquidaciones = new ArrayList<>(calcular(pagoRepository.findByProveedorIdOrderBySemanaInicioAscIdAsc(proveedorId)));
        java.util.Collections.reverse(liquidaciones); // semana mas reciente primero
        BigDecimal saldoLiquidaciones = liquidaciones.stream().map(SaldoPagoResponse::saldo).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal adelantos = pagoProveedorRepository.sumaVigenteDelProveedor(proveedorId)
                .subtract(aplicacionRepository.sumaVigenteDelProveedor(proveedorId));
        return new SaldoProveedorResponse(proveedorId, proveedor.getNombre(), liquidaciones, saldoLiquidaciones, adelantos,
                saldoLiquidaciones.subtract(adelantos));
    }

    // Tres consultas para todos los pagos pedidos (totales, ajustes de litros y aplicaciones vigentes).
    List<SaldoPagoResponse> calcular(Collection<Pago> pagos) {
        if (pagos.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = pagos.stream().map(Pago::getId).toList();
        Map<UUID, BigDecimal> totales = pagoService.totalesAPagar(pagos);
        Map<UUID, BigDecimal> ajustes = ajustePagoService.montosPorPago(ids);
        Map<UUID, BigDecimal> aplicado = new HashMap<>();
        for (Object[] fila : aplicacionRepository.sumaVigentePorPago(ids)) {
            aplicado.put((UUID) fila[0], (BigDecimal) fila[1]);
        }
        List<SaldoPagoResponse> saldos = new ArrayList<>();
        for (Pago p : pagos) {
            BigDecimal total = totales.getOrDefault(p.getId(), p.getTotal());
            BigDecimal ajuste = ajustes.getOrDefault(p.getId(), BigDecimal.ZERO);
            BigDecimal pagado = aplicado.getOrDefault(p.getId(), BigDecimal.ZERO);
            BigDecimal conAjustes = total.add(ajuste);
            BigDecimal saldo = conAjustes.subtract(pagado);
            saldos.add(new SaldoPagoResponse(p.getId(), p.getProveedor().getId(), p.getSemanaInicio(), p.getSemanaFin(),
                    total, ajuste, conAjustes, pagado, saldo, estado(saldo, pagado)));
        }
        return saldos;
    }

    static String estado(BigDecimal saldo, BigDecimal aplicado) {
        if (saldo.signum() < 0) {
            return "SALDO_A_FAVOR";
        }
        if (saldo.signum() == 0) {
            return "PAGADO";
        }
        return aplicado.signum() > 0 ? "PARCIAL" : "PENDIENTE";
    }
}
