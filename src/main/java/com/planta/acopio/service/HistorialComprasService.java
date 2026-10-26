package com.planta.acopio.service;

import com.planta.acopio.dto.ClienteDtos.ComprasResumen;
import com.planta.acopio.dto.ClienteDtos.CompraResponse;
import com.planta.acopio.dto.ClienteDtos.HistorialClienteResponse;
import com.planta.acopio.dto.ClienteDtos.LineaCompra;
import com.planta.acopio.dto.ClienteDtos.ReservaDeComprador;
import com.planta.acopio.dto.ClienteDtos.ResumenReservas;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.entity.EstadoReserva;
import com.planta.acopio.entity.Reserva;
import com.planta.acopio.entity.TipoComprador;
import com.planta.acopio.entity.Venta;
import com.planta.acopio.repository.ReservaRepository;
import com.planta.acopio.repository.VentaDetalleRepository;
import com.planta.acopio.repository.VentaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Fase H: compras y reservas de una identidad (ficha de cliente o de proveedor). Solo cuenta ventas REALMENTE asociadas a esa
// identidad: las ventas anonimas siguen en los indicadores generales de ventas, pero no se atribuyen a nadie. Nada se duplica.
@Service
public class HistorialComprasService {

    private static final LocalDate DESDE_SIEMPRE = LocalDate.of(1900, 1, 1);
    private static final LocalDate HASTA_SIEMPRE = LocalDate.of(9999, 12, 31);

    private final VentaRepository ventaRepository;
    private final VentaDetalleRepository detalleRepository;
    private final ReservaRepository reservaRepository;
    private final Clock reloj;

    @org.springframework.beans.factory.annotation.Autowired
    public HistorialComprasService(VentaRepository ventaRepository, VentaDetalleRepository detalleRepository, ReservaRepository reservaRepository) {
        this(ventaRepository, detalleRepository, reservaRepository, Clock.system(ZoneId.of("America/Lima")));
    }

    HistorialComprasService(VentaRepository ventaRepository, VentaDetalleRepository detalleRepository, ReservaRepository reservaRepository, Clock reloj) {
        this.ventaRepository = ventaRepository;
        this.detalleRepository = detalleRepository;
        this.reservaRepository = reservaRepository;
        this.reloj = reloj;
    }

    /** Indicadores: total historico y del AÑO CALENDARIO en curso (America/Lima), ultima compra y cuantas vinieron de una reserva. */
    @Transactional(readOnly = true)
    public ComprasResumen resumen(TipoComprador tipo, UUID id) {
        int anio = LocalDate.now(reloj).getYear();
        Object[] total = fila(tipo, id, DESDE_SIEMPRE, HASTA_SIEMPRE);
        Object[] delAnio = fila(tipo, id, LocalDate.of(anio, 1, 1), LocalDate.of(anio, 12, 31));
        return new ComprasResumen(anio, numero(total[0]), dinero(total[1]), numero(delAnio[0]), dinero(delAnio[1]), (LocalDate) total[2], numero(total[3]));
    }

    @Transactional(readOnly = true)
    public HistorialClienteResponse historial(TipoComprador tipo, UUID id) {
        List<Reserva> reservas = tipo == TipoComprador.CLIENTE ? reservaRepository.findByClienteIdOrderByCreadoEnDesc(id)
                : reservaRepository.findByProveedorIdOrderByCreadoEnDesc(id);
        LocalDateTime ahora = LocalDateTime.now(reloj);
        List<ReservaDeComprador> filas = reservas.stream()
                .map(r -> new ReservaDeComprador(r.getId(), r.getCodigo(), r.estadoEfectivo(ahora, ahora.toLocalDate()).name(), r.getFechaRetiro(),
                        r.getTotal(), r.getVentaId()))
                .toList();
        return new HistorialClienteResponse(resumen(tipo, id), filas);
    }

    /**
     * Resumen de reservas por ficha de cliente para una pagina del padron: una sola consulta y el MISMO estado efectivo que la
     * pantalla de reservas (una pendiente expirada o una confirmada con su fecha pasada cuentan como vencidas, no como activas).
     */
    @Transactional(readOnly = true)
    public Map<UUID, ResumenReservas> reservasPorCliente(Collection<UUID> clienteIds) {
        if (clienteIds.isEmpty()) return Map.of();
        LocalDateTime ahora = LocalDateTime.now(reloj);
        LocalDate hoy = ahora.toLocalDate();
        Map<UUID, List<Reserva>> porCliente = new HashMap<>();
        for (Reserva r : reservaRepository.findByClienteIdIn(clienteIds)) {
            porCliente.computeIfAbsent(r.getClienteId(), k -> new java.util.ArrayList<>()).add(r);
        }
        Map<UUID, ResumenReservas> resumen = new HashMap<>();
        porCliente.forEach((id, reservas) -> {
            int pendientes = 0, confirmadas = 0;
            LocalDate proximo = null;
            for (Reserva r : reservas) {
                EstadoReserva e = r.estadoEfectivo(ahora, hoy);
                if (e == EstadoReserva.PENDIENTE) pendientes++;
                else if (e == EstadoReserva.CONFIRMADA) confirmadas++;
                else continue;
                if (proximo == null || r.getFechaRetiro().isBefore(proximo)) proximo = r.getFechaRetiro();
            }
            resumen.put(id, new ResumenReservas(pendientes, confirmadas, pendientes + confirmadas, reservas.size(), proximo));
        });
        return resumen;
    }

    @Transactional(readOnly = true)
    public PaginaResponse<CompraResponse> compras(TipoComprador tipo, UUID id, Pageable pageable) {
        Page<Venta> pagina = tipo == TipoComprador.CLIENTE ? ventaRepository.findByClienteIdOrderByFechaDescIdDesc(id, pageable)
                : ventaRepository.findByProveedorIdOrderByFechaDescIdDesc(id, pageable);
        Map<UUID, String> codigos = new HashMap<>();
        return PaginaResponse.de(pagina.map(v -> new CompraResponse(v.getId(), v.getFecha(), v.getTipoCliente().name(), v.getTotal(), v.getReservaId(),
                v.getReservaId() == null ? null : codigos.computeIfAbsent(v.getReservaId(),
                        r -> reservaRepository.findById(r).map(Reserva::getCodigo).orElse(null)),
                detalleRepository.findByVentaId(v.getId()).stream()
                        .map(d -> new LineaCompra(d.getPresentacion().getProducto().getNombre(), d.getPresentacion().getNombre(), d.getCantidad(), d.getSubtotal()))
                        .toList())));
    }

    private Object[] fila(TipoComprador tipo, UUID id, LocalDate desde, LocalDate hasta) {
        List<Object[]> r = tipo == TipoComprador.CLIENTE ? ventaRepository.resumenDeCliente(id, desde, hasta)
                : ventaRepository.resumenDeProveedor(id, desde, hasta);
        return r.isEmpty() ? new Object[]{0L, BigDecimal.ZERO, null, 0L} : r.get(0);
    }

    private static long numero(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static BigDecimal dinero(Object o) {
        return (o == null ? BigDecimal.ZERO : new BigDecimal(o.toString())).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
