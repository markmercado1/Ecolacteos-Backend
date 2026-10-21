package com.planta.acopio.service;

import com.planta.acopio.dto.CatalogoPublicoResponse;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.PrecioQueso;
import com.planta.acopio.entity.TipoClienteVenta;
import com.planta.acopio.repository.PrecioQuesoRepository;
import com.planta.acopio.repository.PresentacionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Fase 3 (catalogo publico). Reglas decididas por el usuario:
//  - solo se publican productos que CONTROLAN vencimiento (activos), con presentaciones activas;
//  - el precio es el de modalidad PUBLICO vigente hoy; una presentacion SIN ese precio SI se lista, con
//    precio = null ("Precio a confirmar") y se puede reservar (Fase 4, opcion B: la reserva queda pendiente de que el
//    personal defina el precio y el cliente lo acepte);
//  - la disponibilidad se calcula con el stock VENDIBLE (lotes vigentes: sin vencidos ni "sin trazabilidad"), nunca con
//    el fisico, DESCONTANDO lo que ya comprometen las reservas vigentes (Fase 4), para la primera fecha de retiro
//    posible; se publica como estado (DISPONIBLE / POCAS_UNIDADES / AGOTADO), no como cantidad.
//
// La disponibilidad se calcula con InventarioService.calcularExistencias, la MISMA funcion que decide si una venta
// puede salir: asi el catalogo no puede discrepar de lo que realmente se puede vender, y el frontend publico no
// repite ninguna regla (solo muestra el estado que recibe).
//
// Es un endpoint SIN autenticacion: el resultado se conserva unos segundos en memoria (app.catalogo.cache-segundos)
// para que visitas repetidas no recalculen contra la base. Es solo informativo; la comprobacion autoritativa de
// stock ocurrira, bajo bloqueo, al reservar (Fase 4). Una presentacion cuyas existencias no cuadran con el libro
// se publica como AGOTADO (falla cerrada) y se registra el error, sin tumbar el resto del catalogo.
@Service
public class CatalogoPublicoService {

    private static final Logger log = LoggerFactory.getLogger(CatalogoPublicoService.class);

    private final PresentacionRepository presentacionRepository;
    private final PrecioQuesoRepository precioRepository;
    private final InventarioService inventarioService;
    private final ReglaVencimiento reglaVencimiento;
    private final ReservaCompromisos compromisos;
    private final ReservaConfig reservaConfig;
    private final TransactionTemplate transaccion;
    private final int umbralPocasUnidades;
    private final long ttlMillis;
    private final Clock reloj;

    private record Instantanea(CatalogoPublicoResponse respuesta, long venceEnMillis) {}

    private volatile Instantanea cache;

    @Autowired
    public CatalogoPublicoService(PresentacionRepository presentacionRepository, PrecioQuesoRepository precioRepository,
                                  InventarioService inventarioService, ReglaVencimiento reglaVencimiento,
                                  ReservaCompromisos compromisos, ReservaConfig reservaConfig,
                                  PlatformTransactionManager transactionManager,
                                  @Value("${app.catalogo.umbral-pocas-unidades:5}") int umbralPocasUnidades,
                                  @Value("${app.catalogo.cache-segundos:30}") int cacheSegundos) {
        this(presentacionRepository, precioRepository, inventarioService, reglaVencimiento, compromisos, reservaConfig,
                transactionManager, umbralPocasUnidades, cacheSegundos, Clock.systemUTC());
    }

    // Para pruebas con un reloj controlado.
    CatalogoPublicoService(PresentacionRepository presentacionRepository, PrecioQuesoRepository precioRepository,
                           InventarioService inventarioService, ReglaVencimiento reglaVencimiento,
                           ReservaCompromisos compromisos, ReservaConfig reservaConfig,
                           PlatformTransactionManager transactionManager, int umbralPocasUnidades, int cacheSegundos,
                           Clock reloj) {
        this.presentacionRepository = presentacionRepository;
        this.precioRepository = precioRepository;
        this.inventarioService = inventarioService;
        this.reglaVencimiento = reglaVencimiento;
        this.compromisos = compromisos;
        this.reservaConfig = reservaConfig;
        this.umbralPocasUnidades = umbralPocasUnidades;
        this.ttlMillis = Math.max(cacheSegundos, 0) * 1000L;
        this.reloj = reloj;
        // Una sola instantanea consistente (REPEATABLE READ, solo lectura) para todas las consultas del catalogo.
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setReadOnly(true);
        t.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.transaccion = t;
    }

    public CatalogoPublicoResponse obtener() {
        Instantanea actual = cache;
        if (actual != null && reloj.millis() < actual.venceEnMillis()) return actual.respuesta();
        synchronized (this) {
            actual = cache;
            long ahora = reloj.millis();
            if (actual != null && ahora < actual.venceEnMillis()) return actual.respuesta();
            CatalogoPublicoResponse nueva = transaccion.execute(estado -> construir());
            if (ttlMillis > 0) cache = new Instantanea(nueva, ahora + ttlMillis);
            return nueva;
        }
    }

    private CatalogoPublicoResponse construir() {
        java.time.LocalDateTime ahora = reglaVencimiento.ahora();
        LocalDate hoy = ahora.toLocalDate();
        List<Presentacion> candidatas = presentacionRepository.listarPublicables();
        if (candidatas.isEmpty()) return new CatalogoPublicoResponse(List.of());

        Map<UUID, PrecioQueso> precios = preciosPublicosVigentes(candidatas, hoy);
        // La disponibilidad se mide para la primera fecha de retiro posible (hoy si todavia se atiende; si no, el siguiente
        // dia de atencion dentro del plazo). La comprobacion exacta, para la fecha elegida, ocurre al reservar.
        LocalDate fechaReferencia = reservaConfig.actuales().primeraFechaDisponible(ahora).orElse(hoy);
        Map<UUID, List<CoberturaReservas.DemandaReserva>> comprometidas =
                compromisos.compromisosDe(candidatas.stream().map(Presentacion::getId).toList());

        Map<UUID, List<CatalogoPublicoResponse.Presentacion>> porProducto = new LinkedHashMap<>();
        Map<UUID, Presentacion> primeraDeProducto = new HashMap<>();
        for (Presentacion pr : candidatas) {
            PrecioQueso precio = precios.get(pr.getId()); // null = "Precio a confirmar"
            var producto = pr.getProducto();
            String fotoPresentacion = FotoCatalogoService.urlDe(pr.getFotoArchivo());
            String foto = fotoPresentacion != null ? fotoPresentacion : FotoCatalogoService.urlDe(producto.getFotoArchivo());
            int disponible = disponibleDe(pr.getId(), comprometidas.getOrDefault(pr.getId(), List.of()), fechaReferencia);
            DisponibilidadPublica estado = DisponibilidadPublica.de(disponible, umbralPocasUnidades);
            porProducto.computeIfAbsent(producto.getId(), k -> new ArrayList<>()).add(new CatalogoPublicoResponse.Presentacion(
                    pr.getId(), pr.getNombre(), pr.getContenidoCantidad(),
                    pr.getContenidoUnidad() != null ? pr.getContenidoUnidad().name() : null,
                    pr.getUnidadConteo(), foto, precio == null ? null : precio.getPrecio(), precio == null, estado,
                    estado == DisponibilidadPublica.POCAS_UNIDADES ? disponible : null));
            primeraDeProducto.putIfAbsent(producto.getId(), pr);
        }

        List<CatalogoPublicoResponse.Producto> productos = new ArrayList<>();
        porProducto.forEach((productoId, presentaciones) -> {
            var producto = primeraDeProducto.get(productoId).getProducto();
            productos.add(new CatalogoPublicoResponse.Producto(producto.getId(), producto.getNombre(),
                    producto.getFamilia().getNombre(), FotoCatalogoService.urlDe(producto.getFotoArchivo()), presentaciones));
        });
        return new CatalogoPublicoResponse(List.copyOf(productos));
    }

    /** El precio PUBLICO vigente a `hoy` de cada presentacion: el de vigente_desde mas reciente <= hoy. */
    private Map<UUID, PrecioQueso> preciosPublicosVigentes(List<Presentacion> candidatas, LocalDate hoy) {
        List<UUID> ids = candidatas.stream().map(Presentacion::getId).toList();
        Map<UUID, PrecioQueso> vigentes = new HashMap<>();
        for (PrecioQueso p : precioRepository.findVigentesHasta(ids, TipoClienteVenta.PUBLICO, hoy)) {
            vigentes.merge(p.getPresentacion().getId(), p,
                    (a, b) -> b.getVigenteDesde().isAfter(a.getVigenteDesde()) ? b : a);
        }
        return vigentes;
    }

    /**
     * Unidades que todavia se pueden reservar/vender: stock vendible real (lotes vigentes que alcanzan la fecha) menos lo que
     * ya comprometen las reservas vigentes. Existencias inconsistentes => 0 (falla cerrada), sin romper el catalogo.
     */
    private int disponibleDe(UUID presentacionId, List<CoberturaReservas.DemandaReserva> comprometidas, LocalDate fechaReferencia) {
        try {
            var lotes = CoberturaReservas.lotesVendibles(inventarioService.calcularExistencias(presentacionId));
            return CoberturaReservas.disponible(lotes, ReservaCompromisos.demandas(comprometidas), fechaReferencia);
        } catch (IllegalStateException e) {
            log.error("Catálogo público: existencias inconsistentes en la presentación {}; se publica como AGOTADO. {}",
                    presentacionId, e.getMessage());
            return 0;
        }
    }
}
