package com.planta.acopio.service;

import com.planta.acopio.dto.HistorialEntregaResponse;
import com.planta.acopio.dto.HistorialEntregaResponse.CalidadEntrega;
import com.planta.acopio.dto.HistorialEntregaResponse.LiquidacionEntrega;
import com.planta.acopio.dto.HistorialResumenResponse;
import com.planta.acopio.dto.HistorialResumenResponse.CalidadResumen;
import com.planta.acopio.dto.HistorialResumenResponse.EntregasResumen;
import com.planta.acopio.dto.HistorialResumenResponse.PagosResumen;
import com.planta.acopio.dto.PagoResponse;
import com.planta.acopio.dto.SaldoPagoResponse;
import com.planta.acopio.dto.SaldoProveedorResponse;
import com.planta.acopio.entity.AlertaAnomalia;
import com.planta.acopio.entity.AnalisisCalidad;
import com.planta.acopio.entity.AjusteCalidadEntrega;
import com.planta.acopio.entity.LineaPago;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.AjusteCalidadEntregaRepository;
import com.planta.acopio.repository.AlertaAnomaliaRepository;
import com.planta.acopio.repository.AnalisisCalidadRepository;
import com.planta.acopio.repository.LineaPagoRepository;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.RegistroAcopioRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Fase D: historial consolidado de un proveedor. SOLO LECTURA sobre tablas que ya existen (registro_acopio, correcciones, analisis, alertas,
// pagos, lineas, ajustes, pagos efectivos, historial de zonas): no guarda ni duplica nada, y los totales salen de las mismas fuentes que las
// pantallas de cada fase (PagoService, SaldoPagoService, LitrosEfectivos, PrecioAplicado), asi que cuadran con ellas por construccion.
// Cada seccion se calcula SOLO si el rol puede verla (ver Secciones): una seccion no autorizada ni se consulta.
@Service
public class HistorialProveedorService {

    /** Que secciones puede ver quien consulta. Las entregas (litros y zona) las ven todos los roles autorizados al historial. */
    // Fase H: COMPRAS = lo que el proveedor compro (solo ADMIN); no toca sus liquidaciones de leche.
    public record Secciones(boolean calidad, boolean pagos, boolean zonas, boolean compras) {
        public Secciones(boolean calidad, boolean pagos, boolean zonas) {
            this(calidad, pagos, zonas, false);
        }

        public List<String> nombres() {
            List<String> n = new ArrayList<>(List.of("ENTREGAS"));
            if (calidad) n.add("CALIDAD");
            if (pagos) n.add("PAGOS");
            if (zonas) n.add("ZONAS");
            if (compras) n.add("COMPRAS");
            return n;
        }
    }

    private final ProveedorRepository proveedorRepository;
    private final RegistroAcopioRepository registroAcopioRepository;
    private final LitrosEfectivos litrosEfectivos;
    private final AnalisisCalidadRepository analisisRepository;
    private final AlertaAnomaliaRepository alertaRepository;
    private final AjusteCalidadEntregaRepository ajusteCalidadRepository;
    private final LineaPagoRepository lineaPagoRepository;
    private final PrecioAplicado precioAplicado;
    private final PagoService pagoService;
    private final SaldoPagoService saldoPagoService;
    private final ProveedorService proveedorService;
    private final HistorialComprasService historialCompras;

    public HistorialProveedorService(ProveedorRepository proveedorRepository, RegistroAcopioRepository registroAcopioRepository,
                                     LitrosEfectivos litrosEfectivos, AnalisisCalidadRepository analisisRepository,
                                     AlertaAnomaliaRepository alertaRepository, AjusteCalidadEntregaRepository ajusteCalidadRepository,
                                     LineaPagoRepository lineaPagoRepository, PrecioAplicado precioAplicado, PagoService pagoService,
                                     SaldoPagoService saldoPagoService, ProveedorService proveedorService,
                                     HistorialComprasService historialCompras) {
        this.historialCompras = historialCompras;
        this.proveedorRepository = proveedorRepository;
        this.registroAcopioRepository = registroAcopioRepository;
        this.litrosEfectivos = litrosEfectivos;
        this.analisisRepository = analisisRepository;
        this.alertaRepository = alertaRepository;
        this.ajusteCalidadRepository = ajusteCalidadRepository;
        this.lineaPagoRepository = lineaPagoRepository;
        this.precioAplicado = precioAplicado;
        this.pagoService = pagoService;
        this.saldoPagoService = saldoPagoService;
        this.proveedorService = proveedorService;
    }

    // ------------------------------------------------------------------ resumen

    @Transactional(readOnly = true)
    public HistorialResumenResponse resumen(UUID proveedorId, Secciones secciones) {
        Proveedor proveedor = proveedor(proveedorId);
        List<RegistroAcopio> registros = registroAcopioRepository.findByProveedorIdOrderByFechaHoraDesc(proveedorId);
        Map<UUID, LitrosEfectivos.Efectivo> efectivos = litrosEfectivos.deRegistros(registros);

        EntregasResumen entregas = resumenEntregas(registros, efectivos);
        CalidadResumen calidad = secciones.calidad() ? resumenCalidad(proveedorId, registros) : null;
        PagosResumen pagos = secciones.pagos() ? resumenPagos(proveedorId, registros, efectivos) : null;
        return new HistorialResumenResponse(
                proveedor.getId(), proveedor.getNombre(), proveedor.getZonaActual() != null ? proveedor.getZonaActual().getNombre() : null,
                secciones.nombres(), entregas, calidad, pagos, secciones.zonas() ? proveedorService.historialZona(proveedorId) : null,
                secciones.compras() ? historialCompras.resumen(com.planta.acopio.entity.TipoComprador.PROVEEDOR, proveedorId) : null);
    }

    private EntregasResumen resumenEntregas(List<RegistroAcopio> registros, Map<UUID, LitrosEfectivos.Efectivo> efectivos) {
        BigDecimal recibidos = BigDecimal.ZERO;
        BigDecimal efectivosTotal = BigDecimal.ZERO;
        LocalDateTime primera = null;
        LocalDateTime ultima = null;
        long corregidas = 0;
        long sinZona = 0;
        for (RegistroAcopio r : registros) {
            LitrosEfectivos.Efectivo e = efectivos.get(r.getId());
            recibidos = recibidos.add(r.getLitros());
            efectivosTotal = efectivosTotal.add(e.litros());
            if (e.correccion() != null) corregidas++;
            if (r.getZona() == null) sinZona++;
            if (primera == null || r.getFechaHora().isBefore(primera)) primera = r.getFechaHora();
            if (ultima == null || r.getFechaHora().isAfter(ultima)) ultima = r.getFechaHora();
        }
        return new EntregasResumen(registros.size(), recibidos, efectivosTotal, primera, ultima, corregidas, sinZona);
    }

    private CalidadResumen resumenCalidad(UUID proveedorId, List<RegistroAcopio> registros) {
        Map<UUID, AnalisisCalidad> ultimo = new HashMap<>();
        for (AnalisisCalidad a : analisisRepository.findByRegistroAcopioProveedorIdOrderByCreadoEnAscIdAsc(proveedorId)) {
            ultimo.put(a.getRegistroAcopio().getId(), a); // cronologico: el ultimo de cada entrega pisa a los anteriores
        }
        long aprobadas = 0, observadas = 0, rechazadas = 0;
        for (AnalisisCalidad a : ultimo.values()) {
            if (a.getResultado() == null) continue;
            switch (a.getResultado()) {
                case APROBADO -> aprobadas++;
                case OBSERVADO -> observadas++;
                case RECHAZADO -> rechazadas++;
                default -> { }
            }
        }
        return new CalidadResumen(ultimo.size(), registros.size() - ultimo.size(), aprobadas, observadas, rechazadas,
                alertaRepository.countByRegistroAcopioProveedorId(proveedorId), ajusteCalidadRepository.contarEntregasConPrecioPropio(proveedorId));
    }

    private PagosResumen resumenPagos(UUID proveedorId, List<RegistroAcopio> registros, Map<UUID, LitrosEfectivos.Efectivo> efectivos) {
        List<PagoResponse> pagos = pagoService.historialPorProveedor(proveedorId);
        SaldoProveedorResponse saldo = saldoPagoService.delProveedor(proveedorId);

        BigDecimal litros = BigDecimal.ZERO, original = BigDecimal.ZERO, reconocido = BigDecimal.ZERO, ajusteCalidad = BigDecimal.ZERO;
        for (PagoResponse p : pagos) {
            litros = litros.add(p.litrosTotales());
            original = original.add(p.total());
            reconocido = reconocido.add(p.totalAPagar());
            ajusteCalidad = ajusteCalidad.add(p.ajusteCalidad());
        }
        BigDecimal ajustesLitros = BigDecimal.ZERO, conAjustes = BigDecimal.ZERO, pagado = BigDecimal.ZERO;
        for (SaldoPagoResponse s : saldo.liquidaciones()) {
            ajustesLitros = ajustesLitros.add(s.ajustesLitros());
            conAjustes = conAjustes.add(s.totalConAjustes());
            pagado = pagado.add(s.aplicado());
        }

        Set<UUID> liquidadas = new HashSet<>();
        if (!pagos.isEmpty()) {
            for (LineaPago l : lineaPagoRepository.findByPagoIdIn(pagos.stream().map(PagoResponse::id).toList())) {
                liquidadas.add(l.getRegistroAcopio().getId());
            }
        }
        long sinLiquidar = 0;
        BigDecimal litrosSinLiquidar = BigDecimal.ZERO;
        for (RegistroAcopio r : registros) {
            if (!liquidadas.contains(r.getId())) {
                sinLiquidar++;
                litrosSinLiquidar = litrosSinLiquidar.add(efectivos.get(r.getId()).litros());
            }
        }
        return new PagosResumen(pagos.size(), litros, original, reconocido, ajusteCalidad, ajustesLitros, conAjustes, pagado,
                saldo.saldoLiquidaciones(), saldo.adelantosSinAplicar(), saldo.saldoNeto(), sinLiquidar, litrosSinLiquidar);
    }

    // ------------------------------------------------------------------ entregas

    @Transactional(readOnly = true)
    public Page<HistorialEntregaResponse> entregas(UUID proveedorId, Pageable pageable, Secciones secciones) {
        proveedor(proveedorId);
        Page<RegistroAcopio> pagina = registroAcopioRepository.findByProveedorIdOrderByFechaHoraDescIdDesc(proveedorId, pageable);
        List<RegistroAcopio> registros = pagina.getContent();
        if (registros.isEmpty()) {
            return new org.springframework.data.domain.PageImpl<>(List.of(), pageable, pagina.getTotalElements());
        }
        List<UUID> ids = registros.stream().map(RegistroAcopio::getId).toList();
        Map<UUID, LitrosEfectivos.Efectivo> efectivos = litrosEfectivos.deRegistros(registros);

        // Lineas y precios: los usan tanto la liquidacion (pagos) como la marca de precio propio (calidad).
        Map<UUID, LineaPago> lineas = (secciones.pagos() || secciones.calidad() ? lineaPagoRepository.findByRegistroAcopioIdIn(ids) : List.<LineaPago>of())
                .stream().collect(Collectors.toMap(l -> l.getRegistroAcopio().getId(), l -> l));
        Map<UUID, BigDecimal> precios = secciones.pagos() ? precioAplicado.deLineas(lineas.values()) : Map.of();
        Map<UUID, AjusteCalidadEntrega> propios = secciones.calidad() ? precioAplicado.ultimosAjustesDeCalidad(lineas.values()) : Map.of();

        Map<UUID, List<AnalisisCalidad>> analisis = secciones.calidad()
                ? analisisRepository.findByRegistroAcopioIdInOrderByCreadoEnAscIdAsc(ids).stream()
                        .collect(Collectors.groupingBy(a -> a.getRegistroAcopio().getId()))
                : Map.of();
        Map<UUID, Long> alertas = secciones.calidad()
                ? alertaRepository.findByRegistroAcopioIdIn(ids).stream()
                        .collect(Collectors.groupingBy(a -> a.getRegistroAcopio().getId(), Collectors.counting()))
                : Map.of();

        return pagina.map(r -> {
            LitrosEfectivos.Efectivo e = efectivos.get(r.getId());
            CalidadEntrega calidad = null;
            if (secciones.calidad()) {
                List<AnalisisCalidad> lista = analisis.getOrDefault(r.getId(), List.of());
                AnalisisCalidad ultimo = lista.isEmpty() ? null : lista.get(lista.size() - 1);
                LineaPago linea = lineas.get(r.getId());
                calidad = new CalidadEntrega(
                        ultimo != null && ultimo.getResultado() != null ? ultimo.getResultado().name() : null,
                        ultimo != null ? ultimo.getFolioMuestra() : null, ultimo != null ? ultimo.getCreadoEn() : null,
                        lista.size(), alertas.getOrDefault(r.getId(), 0L).intValue(), linea != null && propios.containsKey(linea.getId()));
            }
            LiquidacionEntrega liquidacion = null;
            if (secciones.pagos() && lineas.containsKey(r.getId())) {
                LineaPago l = lineas.get(r.getId());
                BigDecimal precio = precios.get(l.getId());
                liquidacion = new LiquidacionEntrega(l.getPago().getId(), l.getPago().getSemanaInicio(), l.getPago().getSemanaFin(),
                        l.getLitrosLiquidados(), precio, l.getLitrosLiquidados().multiply(precio));
            }
            return new HistorialEntregaResponse(r.getId(), r.getFechaHora(), r.getLitros(), e.litros(), e.correccion() != null,
                    r.getZona() != null ? r.getZona().getNombre() : null, calidad, liquidacion);
        });
    }

    private Proveedor proveedor(UUID id) {
        return proveedorRepository.findById(id).orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + id));
    }
}
