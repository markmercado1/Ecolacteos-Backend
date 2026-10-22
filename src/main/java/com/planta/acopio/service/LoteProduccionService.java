package com.planta.acopio.service;

import com.planta.acopio.dto.CrearLoteRequest;
import com.planta.acopio.dto.DetalleSalidaRequest;
import com.planta.acopio.dto.VencimientoSalidaRequest;
import com.planta.acopio.dto.DetalleSalidaResponse;
import com.planta.acopio.dto.DisponibilidadProduccionResponse;
import com.planta.acopio.dto.DisponibilidadProduccionResponse.Linea;
import com.planta.acopio.dto.FinalizarLoteRequest;
import com.planta.acopio.dto.InsumoUsadoRequest;
import com.planta.acopio.dto.InsumoUsadoResponse;
import com.planta.acopio.dto.LoteProduccionResponse;
import com.planta.acopio.dto.PlanificarLoteRequest;
import com.planta.acopio.dto.RecepcionConsumidaResponse;
import com.planta.acopio.entity.EstadoLote;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.LoteProduccion;
import com.planta.acopio.entity.LoteProduccionDetalle;
import com.planta.acopio.entity.LoteProduccionInsumo;
import com.planta.acopio.entity.LoteProduccionRecepcionConsumo;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.RecepcionPlanta;
import com.planta.acopio.entity.Receta;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoInactivoException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.LoteProduccionDetalleRepository;
import com.planta.acopio.repository.LoteProduccionInsumoRepository;
import com.planta.acopio.repository.LoteProduccionRecepcionConsumoRepository;
import com.planta.acopio.repository.LoteProduccionRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.RecepcionPlantaRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.RecetaRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// RF-PROD (CORE_DESIGN.md §11, flujo 5). Refactor de fase 2 (nuevo modelo funcional): ACOPIO -> RECEPCION ->
// EXISTENCIA DE LECHE -> PRODUCCION -> EXISTENCIA DE PRODUCTO TERMINADO. Un lote consume LECHE DISPONIBLE
// (saldo del libro mayor movimiento_inventario_leche) por FIFO automatico contra las RecepcionPlanta con
// saldo remanente, mas antiguas primero (sin cambios en esta fase, ver planificarConsumo()).
//
// Fase 3 (Produccion con varias presentaciones de salida e Inventario por presentacion): un lote sigue
// consumiendo leche UNA sola vez (SUM(consumos del lote) = litrosUsados, invariante sin cambios), pero
// puede generar VARIAS presentaciones de salida del MISMO producto -- una fila de LoteProduccionDetalle por
// presentacion, cada una con su propio movimiento ENTRADA_PRODUCCION (ver InventarioService). No hay
// precio requerido para producir (Precios es un modulo independiente).
@Service
public class LoteProduccionService {

    /** lote_produccion_insumo.cantidad es NUMERIC(9,2). */
    private static final BigDecimal MAX_CANTIDAD_INSUMO_LOTE = new BigDecimal("9999999.99");
    /** lote_produccion.cantidad_esperada es NUMERIC(12,3). */
    private static final BigDecimal MAX_CANTIDAD_ESPERADA = new BigDecimal("999999999.999");

    private final LoteProduccionRepository repository;
    private final ProductoRepository productoRepository;
    private final PresentacionRepository presentacionRepository;
    private final UsuarioRepository usuarioRepository;
    private final InventarioService inventarioService;
    private final InventarioLecheService inventarioLecheService;
    private final RecepcionPlantaRepository recepcionPlantaRepository;
    private final LoteProduccionRecepcionConsumoRepository consumoRepository;
    private final LoteProduccionDetalleRepository detalleRepository;
    private final InsumoRepository insumoRepository;
    private final LoteProduccionInsumoRepository loteInsumoRepository;
    private final RecetaRepository recetaRepository;
    private final InventarioInsumoService inventarioInsumoService;

    public LoteProduccionService(LoteProduccionRepository repository, ProductoRepository productoRepository,
                                  PresentacionRepository presentacionRepository, UsuarioRepository usuarioRepository,
                                  InventarioService inventarioService, InventarioLecheService inventarioLecheService,
                                  RecepcionPlantaRepository recepcionPlantaRepository,
                                  LoteProduccionRecepcionConsumoRepository consumoRepository,
                                  LoteProduccionDetalleRepository detalleRepository,
                                  InsumoRepository insumoRepository,
                                  LoteProduccionInsumoRepository loteInsumoRepository,
                                  RecetaRepository recetaRepository,
                                  InventarioInsumoService inventarioInsumoService) {
        this.recetaRepository = recetaRepository;
        this.inventarioInsumoService = inventarioInsumoService;
        this.repository = repository;
        this.productoRepository = productoRepository;
        this.presentacionRepository = presentacionRepository;
        this.usuarioRepository = usuarioRepository;
        this.inventarioService = inventarioService;
        this.inventarioLecheService = inventarioLecheService;
        this.recepcionPlantaRepository = recepcionPlantaRepository;
        this.consumoRepository = consumoRepository;
        this.detalleRepository = detalleRepository;
        this.insumoRepository = insumoRepository;
        this.loteInsumoRepository = loteInsumoRepository;
    }

    @Transactional
    public LoteProduccionResponse crear(CrearLoteRequest dto) {
        var existente = repository.findByUuidCliente(dto.uuidCliente());
        if (existente.isPresent()) {
            LoteProduccion lote = existente.get();
            if (coincideConSolicitud(lote, dto)) {
                return toResponse(lote);
            }
            // Mismo uuidCliente, contenido distinto: conflicto explicito, la operacion existente NO se
            // modifica (nunca se reinterpreta un reintento con datos diferentes como una edicion).
            throw new ConflictoNegocioException(
                    "Ya existe un lote de produccion con este uuidCliente, pero con datos distintos a los "
                            + "enviados; el lote existente no se modifica. Usa un uuidCliente nuevo para una "
                            + "producción distinta.");
        }

        Producto producto = productoRepository.findById(dto.productoId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Producto no encontrado: " + dto.productoId()));
        if (!Boolean.TRUE.equals(producto.getActivo())) {
            throw new RecursoInactivoException("El producto '" + producto.getNombre() + "' esta desactivado");
        }

        // Presentaciones de salida: validadas ANTES de tocar leche/consumo (fallo barato primero). Lista
        // vacia = produccion sin ningun rendimiento aprovechable, caso real ya existente -- se preserva tal
        // cual, sin generar ninguna fila de detalle ni movimiento de inventario.
        List<Presentacion> presentacionesValidadas = validarDetalles(producto, dto.detalles());
        // Fase 1 (control de vencimiento): este camino (registro de un solo paso, compartido con otros
        // clientes) no registra fechas de vencimiento; un producto controlado se produce por el flujo
        // planificado (finalizar). Falla rapida: antes de tocar leche o insumos. La guarda definitiva, con el
        // valor ya bloqueado, esta en InventarioService.registrarEntradaProduccion.
        if (Boolean.TRUE.equals(producto.getControlaVencimiento())) {
            throw new ValidacionNegocioException("El producto '" + producto.getNombre() + "' controla vencimiento: "
                    + "se produce con el flujo planificado, registrando la fecha de vencimiento de cada salida.");
        }
        // Insumos (opcionales): tambien antes de tocar leche. Lista vacia = lote sin insumos, valido.
        Map<Insumo, BigDecimal> insumosValidados = validarInsumos(dto.insumosOVacio());
        // V18: los insumos registrados en un lote SE DESCUENTAN de su stock (antes eran solo informativos).
        // Aqui solo se rechaza con el detalle de faltantes; el descuento atomico va despues, en la misma
        // transaccion que la leche (ver InventarioInsumoService.registrarSalidasProduccion(), que ademas deja
        // el movimiento SALIDA_PRODUCCION en el kardex del insumo).
        List<Linea> lineasInsumos = insumosValidados.entrySet().stream()
                .map(e -> CalculoReceta.linea(e.getKey().getId(), e.getKey().getNombre(),
                        e.getKey().getUnidadMedida(), e.getValue(), e.getKey().getStock()))
                .toList();
        if (lineasInsumos.stream().anyMatch(l -> !l.suficiente())) {
            throw new ValidacionNegocioException(CalculoReceta.mensajeFaltantes(
                    CalculoReceta.lineaLeche(BigDecimal.ZERO, BigDecimal.ZERO), lineasInsumos));
        }

        RevisionRendimiento revision = RevisionRendimiento.evaluar(producto.getRangoRendimiento(),
                dto.litrosUsados(), dto.detalles(), presentacionesValidadas);
        String motivo = normalizarMotivo(dto.motivoRendimiento());
        if (revision.fueraDeRango() && motivo == null) {
            throw new ValidacionNegocioException("Producción fuera del rango configurado: "
                    + revision.descripcion() + ". Revisa las cantidades o indica un motivo para continuar.");
        }

        // Planifica (y bloquea) el consumo ANTES de escribir nada: si el saldo no alcanza, se rechaza sin
        // haber escrito una sola fila (ver planificarConsumo()).
        List<LoteProduccionRecepcionConsumo> plan = planificarConsumo(dto.litrosUsados());

        Usuario responsable = usuarioAutenticado();

        LoteProduccion lote = new LoteProduccion();
        lote.setUuidCliente(dto.uuidCliente());
        lote.setFecha(dto.fecha());
        lote.setProducto(producto);
        lote.setLitrosUsados(dto.litrosUsados());
        lote.setResponsable(responsable);
        lote.setMotivoRendimiento(motivo);
        lote.setRevisionRendimiento(revision.descripcion());

        aplicarRendimiento(lote, producto, dto.litrosUsados(), presentacionesValidadas, dto.detalles());

        try {
            // saveAndFlush(), NO save(): ver comentario historico completo en git -- fuerza el INSERT real
            // ahora, para que una violacion de UNIQUE(uuid_cliente) por carrera concurrente se traduzca a
            // ConflictoNegocioException en vez de escapar sin capturar en el commit.
            lote = repository.saveAndFlush(lote);
        } catch (DataIntegrityViolationException ex) {
            // Carrera real (mismo criterio que JornadaService/ConciliacionService/etc. para su propio
            // indice unico concurrente): se lanza el conflicto y se corta aqui, SIN volver a leer el
            // repositorio en esta misma transaccion ya marcada para rollback. La idempotencia real la da el
            // reintento del cliente con el mismo uuidCliente en una peticion NUEVA.
            throw new ConflictoNegocioException(
                    "Ya existe un lote de produccion con este uuidCliente (creado concurrentemente); "
                            + "reintentar con el mismo uuidCliente para obtenerlo.");
        }

        registrarConsumoLeche(lote, plan, dto.litrosUsados());

        // Insumos usados: misma transaccion que la leche y el producto terminado -- si algo falla, el lote
        // completo se revierte y nunca queda un producto en inventario sin su registro de insumos.
        for (Map.Entry<Insumo, BigDecimal> uso : insumosValidados.entrySet()) {
            LoteProduccionInsumo loteInsumo = new LoteProduccionInsumo();
            loteInsumo.setLote(lote);
            loteInsumo.setInsumo(uso.getKey());
            loteInsumo.setCantidad(uso.getValue());
            loteInsumoRepository.save(loteInsumo);
        }
        inventarioInsumoService.registrarSalidasProduccion(lote, insumosValidados);

        registrarSalidas(lote, presentacionesValidadas, dto.detalles());

        return toResponse(lote);
    }

    // ------------------------------------------------------------------------------------------------
    // V18: produccion planificada desde receta. PLANIFICADA -> EN_PRODUCCION -> FINALIZADA (o CANCELADA
    // desde PLANIFICADA). Reutiliza exactamente las mismas piezas que crear(): FIFO de leche
    // (planificarConsumo/registrarConsumoLeche), validacion de salidas (validarDetalles), revision de rango
    // (RevisionRendimiento) y entradas de inventario por detalle (registrarSalidas) -- solo cambia CUANDO
    // ocurre cada una:
    //   planificar: congela el plan (litros, insumos escalados, cantidad esperada). No toca ningun stock.
    //   iniciar:    valida TODO el stock y consume leche + insumos (confirmacion definitiva del consumo).
    //   finalizar:  registra la salida REAL y recien ahi ingresa producto terminado al inventario.
    //   cancelar:   solo desde PLANIFICADA -- como nada se consumio, no hay nada que revertir.
    // ------------------------------------------------------------------------------------------------

    @Transactional
    public LoteProduccionResponse planificar(PlanificarLoteRequest dto) {
        var existente = repository.findByUuidCliente(dto.uuidCliente());
        if (existente.isPresent()) {
            LoteProduccion lote = existente.get();
            boolean mismoPlan = lote.getReceta() != null && lote.getReceta().getId().equals(dto.recetaId())
                    && lote.getFecha().equals(dto.fecha()) && lote.getLitrosUsados().compareTo(dto.litros()) == 0;
            if (mismoPlan) return toResponse(lote);
            throw new ConflictoNegocioException("Ya existe una producción con este uuidCliente, pero con datos "
                    + "distintos; la existente no se modifica. Usa un uuidCliente nuevo para otra producción.");
        }

        Receta receta = recetaRepository.findById(dto.recetaId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Receta no encontrada: " + dto.recetaId()));
        if (!Boolean.TRUE.equals(receta.getActivo())) {
            throw new RecursoInactivoException("La receta '" + receta.getNombre() + "' está desactivada");
        }
        Producto producto = receta.getProducto();
        if (!Boolean.TRUE.equals(producto.getActivo())) {
            throw new RecursoInactivoException("El producto '" + producto.getNombre() + "' está desactivado");
        }
        if (!Boolean.TRUE.equals(receta.getPresentacion().getActivo())) {
            throw new RecursoInactivoException("La presentación '" + receta.getPresentacion().getNombre()
                    + "' de la receta está desactivada");
        }

        List<CalculoReceta.Requerimiento> requerimientos = CalculoReceta.requerimientos(receta, dto.litros());
        for (CalculoReceta.Requerimiento r : requerimientos) {
            if (!Boolean.TRUE.equals(r.insumo().getActivo())) {
                throw new RecursoInactivoException("El insumo '" + r.insumo().getNombre()
                        + "' de la receta está desactivado. Actualiza la receta antes de planificar.");
            }
            if (r.requerido().signum() <= 0) {
                throw new ValidacionNegocioException("Con " + dto.litros().stripTrailingZeros().toPlainString()
                        + " L la cantidad de " + r.insumo().getNombre() + " resulta 0 al redondear. Aumenta la leche.");
            }
            if (r.requerido().compareTo(MAX_CANTIDAD_INSUMO_LOTE) > 0) {
                throw new ValidacionNegocioException("La cantidad de " + r.insumo().getNombre()
                        + " supera el máximo registrable por lote.");
            }
        }
        BigDecimal esperada = CalculoReceta.cantidadEsperada(receta, dto.litros());
        if (esperada.compareTo(MAX_CANTIDAD_ESPERADA) > 0) {
            throw new ValidacionNegocioException("La cantidad esperada supera el máximo registrable por lote.");
        }

        LoteProduccion lote = new LoteProduccion();
        lote.setUuidCliente(dto.uuidCliente());
        lote.setFecha(dto.fecha());
        lote.setProducto(producto);
        lote.setReceta(receta);
        lote.setLitrosUsados(dto.litros());
        lote.setCantidadEsperada(esperada);
        lote.setEstado(EstadoLote.PLANIFICADA);
        lote.setResponsable(usuarioAutenticado());
        try {
            lote = repository.saveAndFlush(lote);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictoNegocioException("Ya existe una producción con este uuidCliente (creada "
                    + "concurrentemente); reintentar con el mismo uuidCliente para obtenerla.");
        }
        // Snapshot del plan de insumos: son exactamente las cantidades que se descontaran al iniciar, aunque
        // la receta cambie despues.
        for (CalculoReceta.Requerimiento r : requerimientos) {
            LoteProduccionInsumo fila = new LoteProduccionInsumo();
            fila.setLote(lote);
            fila.setInsumo(r.insumo());
            fila.setCantidad(r.requerido());
            loteInsumoRepository.save(fila);
        }
        return toResponse(lote);
    }

    /** Validacion informativa del plan de un lote contra el stock actual (sin bloqueo). */
    @Transactional(readOnly = true)
    public DisponibilidadProduccionResponse disponibilidad(UUID id) {
        LoteProduccion lote = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Lote de produccion no encontrado: " + id));
        return evaluarDisponibilidad(lote, loteInsumoRepository.findByLoteId(id));
    }

    @Transactional
    public LoteProduccionResponse iniciar(UUID id) {
        LoteProduccion lote = bloquearLote(id);
        if (lote.getEstado() == EstadoLote.EN_PRODUCCION) {
            return toResponse(lote); // reintento: el consumo ya se confirmo una vez, nunca dos
        }
        exigirEstado(lote, EstadoLote.PLANIFICADA, "iniciar");

        List<LoteProduccionInsumo> filas = loteInsumoRepository.findByLoteId(id);
        for (LoteProduccionInsumo fila : filas) {
            if (!Boolean.TRUE.equals(fila.getInsumo().getActivo())) {
                throw new RecursoInactivoException("El insumo '" + fila.getInsumo().getNombre()
                        + "' está desactivado; no se puede iniciar esta producción.");
            }
        }
        DisponibilidadProduccionResponse disponibilidad = evaluarDisponibilidad(lote, filas);
        if (!disponibilidad.todoDisponible()) {
            throw new ValidacionNegocioException(
                    CalculoReceta.mensajeFaltantes(disponibilidad.leche(), disponibilidad.insumos()));
        }

        // Autoridad real sobre la leche: bloqueo de las recepciones candidatas (FIFO). Si otra produccion
        // consumio la misma leche entre la lectura anterior y este bloqueo, rechaza aqui sin escribir nada.
        List<LoteProduccionRecepcionConsumo> plan = planificarConsumo(lote.getLitrosUsados());
        registrarConsumoLeche(lote, plan, lote.getLitrosUsados());

        Map<Insumo, BigDecimal> consumo = new LinkedHashMap<>();
        for (LoteProduccionInsumo fila : filas) consumo.put(fila.getInsumo(), fila.getCantidad());
        inventarioInsumoService.registrarSalidasProduccion(lote, consumo);

        lote.setEstado(EstadoLote.EN_PRODUCCION);
        lote.setIniciadoEn(LocalDateTime.now());
        return toResponse(lote);
    }

    @Transactional
    public LoteProduccionResponse finalizar(UUID id, FinalizarLoteRequest dto) {
        LoteProduccion lote = bloquearLote(id);
        if (lote.getEstado() == EstadoLote.FINALIZADA) {
            // Reintento con el mismo resultado: se devuelve tal cual, sin volver a ingresar inventario.
            if (lote.getIniciadoEn() != null && mismasSalidas(lote, dto.detalles())
                    && java.util.Objects.equals(lote.getMotivoRendimiento(), normalizarMotivo(dto.motivoRendimiento()))) {
                return toResponse(lote);
            }
            throw new ConflictoNegocioException("Esta producción ya fue finalizada; su resultado no se puede "
                    + "volver a registrar ni modificar.");
        }
        exigirEstado(lote, EstadoLote.EN_PRODUCCION, "finalizar");

        Producto producto = lote.getProducto();
        List<Presentacion> presentaciones = validarDetalles(producto, dto.detalles());
        RevisionRendimiento revision = RevisionRendimiento.evaluar(producto.getRangoRendimiento(),
                lote.getLitrosUsados(), dto.detalles(), presentaciones);
        String motivo = normalizarMotivo(dto.motivoRendimiento());
        if (revision.fueraDeRango() && motivo == null) {
            throw new ValidacionNegocioException("Producción fuera del rango configurado: "
                    + revision.descripcion() + ". Revisa las cantidades o indica un motivo para continuar.");
        }
        lote.setMotivoRendimiento(motivo);
        lote.setRevisionRendimiento(revision.descripcion());

        registrarSalidas(lote, presentaciones, dto.detalles(),
                dto.vencimientos() == null ? List.of() : dto.vencimientos());
        lote.setEstado(EstadoLote.FINALIZADA);
        lote.setFinalizadoEn(LocalDateTime.now());
        return toResponse(lote);
    }

    @Transactional
    public LoteProduccionResponse cancelar(UUID id) {
        LoteProduccion lote = bloquearLote(id);
        if (lote.getEstado() == EstadoLote.CANCELADA) return toResponse(lote);
        if (lote.getEstado() == EstadoLote.EN_PRODUCCION) {
            throw new ConflictoNegocioException("No se puede cancelar una producción en curso: la leche y los "
                    + "insumos ya se consumieron. Finalízala registrando lo obtenido (sin salidas si se perdió).");
        }
        exigirEstado(lote, EstadoLote.PLANIFICADA, "cancelar");
        lote.setEstado(EstadoLote.CANCELADA);
        lote.setCanceladoEn(LocalDateTime.now());
        return toResponse(lote);
    }

    private LoteProduccion bloquearLote(UUID id) {
        return repository.bloquearPorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Lote de produccion no encontrado: " + id));
    }

    private static void exigirEstado(LoteProduccion lote, EstadoLote esperado, String accion) {
        if (lote.getEstado() != esperado) {
            throw new ConflictoNegocioException("No se puede " + accion + " una producción en estado "
                    + lote.getEstado() + " (se requiere " + esperado + ").");
        }
    }

    private DisponibilidadProduccionResponse evaluarDisponibilidad(LoteProduccion lote, List<LoteProduccionInsumo> filas) {
        Linea leche = CalculoReceta.lineaLeche(lote.getLitrosUsados(), inventarioLecheService.obtenerSaldoDisponibleLitros());
        List<Linea> insumos = filas.stream()
                .map(f -> CalculoReceta.linea(f.getInsumo().getId(), f.getInsumo().getNombre(),
                        f.getInsumo().getUnidadMedida(), f.getCantidad(), f.getInsumo().getStock()))
                .toList();
        Presentacion salida = lote.getReceta() != null ? lote.getReceta().getPresentacion() : null;
        return CalculoReceta.respuesta(lote.getLitrosUsados(), null, leche, insumos, lote.getCantidadEsperada(),
                salida != null ? salida.getNombre() : null, salida != null ? salida.getUnidadConteo() : null,
                null, null);
    }

    private boolean mismasSalidas(LoteProduccion lote, List<DetalleSalidaRequest> solicitadas) {
        Map<UUID, Integer> existentes = new HashMap<>();
        for (LoteProduccionDetalle d : detalleRepository.findByLoteId(lote.getId())) {
            existentes.put(d.getPresentacion().getId(), d.getCantidadObtenida());
        }
        Map<UUID, Integer> pedidas = new HashMap<>();
        for (DetalleSalidaRequest d : solicitadas) pedidas.put(d.presentacionId(), d.cantidadObtenida());
        return existentes.equals(pedidas);
    }

    // Leche: salida agregada del total consumido, UNA sola vez por lote (nunca una vez por presentacion de
    // salida -- el consumo de materia prima es del lote completo), mas su desglose FIFO por recepcion.
    private void registrarConsumoLeche(LoteProduccion lote, List<LoteProduccionRecepcionConsumo> plan, BigDecimal litros) {
        for (LoteProduccionRecepcionConsumo consumo : plan) {
            consumo.setLoteProduccion(lote);
            consumoRepository.save(consumo);
        }
        inventarioLecheService.registrarSalidaProduccion(lote, litros);
    }

    // Producto terminado: una entrada de inventario POR DETALLE, cada una en la misma transaccion -- si
    // cualquiera falla, toda la transaccion se revierte junta (verificado con Testcontainers reales, ver
    // ProduccionMultiPresentacionIntegrationTest).
    private void registrarSalidas(LoteProduccion lote, List<Presentacion> presentaciones, List<DetalleSalidaRequest> detalles) {
        registrarSalidas(lote, presentaciones, detalles, null);
    }

    // Fase 1 (control de vencimiento). `vencimientos == null` = camino legado (crear): nunca se traza, y si el
    // producto fuera controlado la guarda de InventarioService lo rechaza. `vencimientos != null` (finalizar,
    // exclusivo de la web) = si el producto controla vencimiento, cada salida lleva su fecha y su entrada se
    // registra trazada; si no lo controla, no se admiten fechas y todo sigue exactamente igual que antes.
    private void registrarSalidas(LoteProduccion lote, List<Presentacion> presentaciones, List<DetalleSalidaRequest> detalles,
                                  List<VencimientoSalidaRequest> vencimientos) {
        // Bloqueo de TODAS las presentaciones, en orden de id, ANTES de escribir nada: una produccion con las
        // presentaciones en otro orden no puede interbloquearse con una venta (que bloquea en orden de id) y
        // la activacion/desactivacion del control no puede intercalarse con estas entradas.
        inventarioService.bloquearPresentaciones(detalles.stream().map(DetalleSalidaRequest::presentacionId).toList());
        Map<UUID, LocalDate> fechas = resolverVencimientos(lote, presentaciones, detalles, vencimientos);

        for (DetalleSalidaRequest d : detalles) {
            Presentacion presentacion = presentaciones.stream()
                    .filter(p -> p.getId().equals(d.presentacionId())).findFirst().orElseThrow();
            LoteProduccionDetalle detalle = new LoteProduccionDetalle();
            detalle.setLote(lote);
            detalle.setPresentacion(presentacion);
            detalle.setCantidadObtenida(d.cantidadObtenida());
            LocalDate vencimiento = fechas == null ? null : fechas.get(d.presentacionId());
            detalle.setFechaVencimiento(vencimiento);
            detalle = detalleRepository.save(detalle);
            if (vencimiento != null) {
                inventarioService.registrarEntradaProduccionTrazada(detalle);
            } else {
                inventarioService.registrarEntradaProduccion(detalle);
            }
        }
    }

    // Devuelve null si estas salidas no se trazan (camino legado o producto sin control). Para un producto
    // controlado exige una fecha por cada salida (ni de mas, ni repetidas) y que ninguna sea anterior a la
    // fecha del lote; no hay tope superior. Siempre valida ANTES de escribir nada.
    private Map<UUID, LocalDate> resolverVencimientos(LoteProduccion lote, List<Presentacion> presentaciones,
                                                      List<DetalleSalidaRequest> detalles, List<VencimientoSalidaRequest> vencimientos) {
        if (vencimientos == null) return null;
        boolean controlado = !detalles.isEmpty() && inventarioService.controlaVencimiento(detalles.get(0).presentacionId());
        if (!controlado) {
            if (!vencimientos.isEmpty()) {
                throw new ValidacionNegocioException("El producto '" + lote.getProducto().getNombre()
                        + "' no controla vencimiento: no se admiten fechas de vencimiento.");
            }
            return null;
        }
        Map<UUID, LocalDate> porPresentacion = new HashMap<>();
        for (VencimientoSalidaRequest v : vencimientos) {
            if (porPresentacion.put(v.presentacionId(), v.fechaVencimiento()) != null) {
                throw new ValidacionNegocioException("La fecha de vencimiento de una presentación está repetida.");
            }
        }
        Set<UUID> salidas = detalles.stream().map(DetalleSalidaRequest::presentacionId).collect(java.util.stream.Collectors.toSet());
        for (UUID presentacionId : porPresentacion.keySet()) {
            if (!salidas.contains(presentacionId)) {
                throw new ValidacionNegocioException("Se indicó un vencimiento para una presentación que no es una salida de este lote.");
            }
        }
        for (DetalleSalidaRequest d : detalles) {
            String nombre = presentaciones.stream().filter(p -> p.getId().equals(d.presentacionId()))
                    .map(Presentacion::getNombre).findFirst().orElse(d.presentacionId().toString());
            LocalDate fecha = porPresentacion.get(d.presentacionId());
            if (fecha == null) {
                throw new ValidacionNegocioException("Falta la fecha de vencimiento de '" + nombre + "': el producto controla vencimiento.");
            }
            if (fecha.isBefore(lote.getFecha())) {
                throw new ValidacionNegocioException("El vencimiento de '" + nombre + "' no puede ser anterior a la fecha del lote ("
                        + lote.getFecha() + ").");
            }
        }
        return porPresentacion;
    }

    private Usuario usuarioAutenticado() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));
    }

    // No permite presentaciones ajenas al producto, inactivas, duplicadas, ni con cantidad invalida (esto
    // ultimo ya lo garantiza @Min(1) en DetalleSalidaRequest, pero se revalida aqui para no depender
    // exclusivamente de la anotacion si algun otro llamador construyera el DTO a mano). Lista vacia: valida,
    // no valida nada mas (caso explicito de produccion sin rendimiento).
    private List<Presentacion> validarDetalles(Producto producto, List<DetalleSalidaRequest> detalles) {
        List<Presentacion> resueltas = new ArrayList<>();
        Set<UUID> vistas = new HashSet<>();
        for (DetalleSalidaRequest d : detalles) {
            if (d.cantidadObtenida() == null || d.cantidadObtenida() < 1) {
                throw new ValidacionNegocioException("La cantidad obtenida debe ser al menos 1 por presentación.");
            }
            if (!vistas.add(d.presentacionId())) {
                throw new ValidacionNegocioException(
                        "La presentación " + d.presentacionId() + " aparece más de una vez en el mismo lote.");
            }
            Presentacion presentacion = presentacionRepository.findById(d.presentacionId())
                    .orElseThrow(() -> new RecursoNoEncontradoException("Presentación no encontrada: " + d.presentacionId()));
            if (!presentacion.getProducto().getId().equals(producto.getId())) {
                throw new ValidacionNegocioException(
                        "La presentación '" + presentacion.getNombre() + "' no pertenece al producto '"
                                + producto.getNombre() + "'.");
            }
            if (!Boolean.TRUE.equals(presentacion.getActivo())) {
                throw new RecursoInactivoException("La presentación '" + presentacion.getNombre() + "' está desactivada");
            }
            resueltas.add(presentacion);
        }
        return resueltas;
    }

    // Insumo existente y activo, cantidad > 0 (ya lo exige @DecimalMin, se revalida por el mismo motivo que
    // validarDetalles), sin repetir el mismo insumo. LinkedHashMap: conserva el orden en que se cargaron.
    private Map<Insumo, BigDecimal> validarInsumos(List<InsumoUsadoRequest> filas) {
        Map<Insumo, BigDecimal> resueltos = new LinkedHashMap<>();
        Set<UUID> vistos = new HashSet<>();
        for (InsumoUsadoRequest fila : filas) {
            if (fila.cantidad() == null || fila.cantidad().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ValidacionNegocioException("La cantidad de cada insumo debe ser mayor a 0.");
            }
            if (!vistos.add(fila.insumoId())) {
                throw new ValidacionNegocioException(
                        "El insumo " + fila.insumoId() + " aparece más de una vez en el mismo lote.");
            }
            Insumo insumo = insumoRepository.findById(fila.insumoId())
                    .orElseThrow(() -> new RecursoNoEncontradoException("Insumo no encontrado: " + fila.insumoId()));
            if (!Boolean.TRUE.equals(insumo.getActivo())) {
                throw new RecursoInactivoException("El insumo '" + insumo.getNombre() + "' está desactivado");
            }
            resueltos.put(insumo, fila.cantidad());
        }
        return resueltos;
    }

    // Rendimiento esperado/real (nuevo modelo, RF-PROD-02): snapshot del catalogo al momento de producir.
    // Fase 3: NO basta con tener una unica salida para reutilizar la formula historica de queso
    // (unidadesObtenidas/litrosUsados*100) -- esa formula solo tiene sentido cuando la salida es una
    // presentacion SIN contenido declarado (el mismo shape que "Unidad"/"Pieza" siempre tuvo: una pieza
    // contable, no un volumen), Y el producto tiene rendimientoEsperadoPct configurado (atributo de
    // produccion de queso, opcional para otras familias). Una "Botella 1 L" con contenido declarado NUNCA
    // entra en esta formula aunque sea la unica salida del lote: mezclar litros con un % pensado para piezas
    // contables inventaria una equivalencia que no existe. Con 0 o 2+ salidas, o con contenido declarado,
    // los tres campos quedan en null -- "Sin cálculo configurado", nunca una cantidad agregada entre
    // tamaños distintos disfrazada de rendimiento.
    private void aplicarRendimiento(LoteProduccion lote, Producto producto, BigDecimal litrosUsados,
                                     List<Presentacion> presentaciones, List<DetalleSalidaRequest> detalles) {
        if (presentaciones.size() == 1) {
            Presentacion unica = presentaciones.get(0);
            BigDecimal rendimientoEsperado = producto.getRendimientoEsperadoPct();
            if (unica.getContenidoCantidad() == null && rendimientoEsperado != null) {
                lote.setRendimientoEsperadoPctSnapshot(rendimientoEsperado);
                lote.setUnidadesEsperadas(
                        litrosUsados.multiply(rendimientoEsperado)
                                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                                .intValue());
                int cantidadObtenida = detalles.get(0).cantidadObtenida();
                lote.setRendimientoPct(
                        BigDecimal.valueOf(cantidadObtenida)
                                .divide(litrosUsados, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                                .setScale(2, RoundingMode.HALF_UP));
                return;
            }
        }
        lote.setRendimientoEsperadoPctSnapshot(null);
        lote.setUnidadesEsperadas(null);
        lote.setRendimientoPct(null);
    }

    // Mismo uuidCliente: recupera si el contenido coincide EXACTAMENTE (fecha, producto, litros y el
    // conjunto presentacion->cantidad de los detalles); si difiere en cualquier campo, es un conflicto
    // explicito (ver crear()) -- nunca se reinterpreta como una correccion silenciosa del lote existente.
    private boolean coincideConSolicitud(LoteProduccion existente, CrearLoteRequest dto) {
        if (existente.getReceta() != null) return false; // un lote planificado nunca es "el mismo" que uno directo
        if (!java.util.Objects.equals(existente.getMotivoRendimiento(), normalizarMotivo(dto.motivoRendimiento()))) return false;
        if (!existente.getFecha().equals(dto.fecha())) return false;
        if (!existente.getProducto().getId().equals(dto.productoId())) return false;
        if (existente.getLitrosUsados().compareTo(dto.litrosUsados()) != 0) return false;

        Map<UUID, Integer> detallesExistentes = new HashMap<>();
        for (LoteProduccionDetalle d : detalleRepository.findByLoteId(existente.getId())) {
            detallesExistentes.put(d.getPresentacion().getId(), d.getCantidadObtenida());
        }
        Map<UUID, Integer> detallesSolicitados = new HashMap<>();
        for (DetalleSalidaRequest d : dto.detalles()) {
            detallesSolicitados.put(d.presentacionId(), d.cantidadObtenida());
        }
        if (!detallesExistentes.equals(detallesSolicitados)) return false;

        // Insumos: comparados por valor numerico (compareTo), no por escala -- "2" y "2.00" son lo mismo.
        Map<UUID, BigDecimal> insumosExistentes = new HashMap<>();
        for (LoteProduccionInsumo i : loteInsumoRepository.findByLoteId(existente.getId())) {
            insumosExistentes.put(i.getInsumo().getId(), i.getCantidad());
        }
        List<InsumoUsadoRequest> insumosSolicitados = dto.insumosOVacio();
        if (insumosExistentes.size() != insumosSolicitados.size()) return false;
        for (InsumoUsadoRequest i : insumosSolicitados) {
            BigDecimal cantidadExistente = insumosExistentes.get(i.insumoId());
            if (cantidadExistente == null || i.cantidad() == null || cantidadExistente.compareTo(i.cantidad()) != 0) {
                return false;
            }
        }
        return true;
    }

    // FIFO sobre RecepcionPlanta.litrosPlanta con saldo remanente, mas antiguas primero. Sin cambios en
    // esta fase (ver comentario historico completo en git para el detalle del doble bloqueo).
    private List<LoteProduccionRecepcionConsumo> planificarConsumo(BigDecimal litrosNecesarios) {
        List<UUID> candidatosIds = recepcionPlantaRepository.buscarConSaldoDisponibleOrdenadasPorAntiguedad()
                .stream().map(RecepcionPlanta::getId).toList();

        List<RecepcionPlanta> bloqueadas = candidatosIds.isEmpty()
                ? List.of()
                : recepcionPlantaRepository.bloquearPorIds(candidatosIds);
        List<RecepcionPlanta> ordenadas = new ArrayList<>(bloqueadas);
        ordenadas.sort(Comparator.comparing(RecepcionPlanta::getFecha).thenComparing(RecepcionPlanta::getCreadoEn));

        List<LoteProduccionRecepcionConsumo> plan = new ArrayList<>();
        BigDecimal restante = litrosNecesarios;
        for (RecepcionPlanta recepcion : ordenadas) {
            if (restante.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal yaConsumido = consumoRepository.sumarLitrosConsumidosPorRecepcion(recepcion.getId());
            BigDecimal disponibleEnEsta = recepcion.getLitrosPlanta().subtract(yaConsumido);
            if (disponibleEnEsta.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal aTomar = disponibleEnEsta.min(restante);
            LoteProduccionRecepcionConsumo consumo = new LoteProduccionRecepcionConsumo();
            consumo.setRecepcionPlanta(recepcion);
            consumo.setLitrosConsumidos(aTomar);
            plan.add(consumo);
            restante = restante.subtract(aTomar);
        }

        if (restante.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal disponibleTotal = litrosNecesarios.subtract(restante);
            throw new ValidacionNegocioException(
                    "Leche disponible insuficiente: se solicitaron " + litrosNecesarios
                            + " L, disponibles " + disponibleTotal + " L");
        }
        return plan;
    }

    @Transactional(readOnly = true)
    public Page<LoteProduccionResponse> buscar(LocalDate fecha, UUID productoId, EstadoLote estado, String busqueda,
                                               Pageable pageable) {
        return repository.buscarConFiltros(fecha, productoId, estado != null ? estado.name() : null,
                        BusquedaTexto.patronLike(busqueda), pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public LoteProduccionResponse obtener(UUID id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Lote de produccion no encontrado: " + id)));
    }

    private LoteProduccionResponse toResponse(LoteProduccion l) {
        List<RecepcionConsumidaResponse> recepcionesConsumidas = consumoRepository.findByLoteProduccionId(l.getId())
                .stream()
                .map(c -> new RecepcionConsumidaResponse(c.getRecepcionPlanta().getId(), c.getLitrosConsumidos()))
                .toList();
        List<DetalleSalidaResponse> detalles = detalleRepository.findByLoteId(l.getId()).stream()
                .map(d -> {
                    Presentacion p = d.getPresentacion();
                    return new DetalleSalidaResponse(p.getId(), p.getNombre(), p.getContenidoCantidad(),
                            p.getContenidoUnidad() != null ? p.getContenidoUnidad().name() : null,
                            p.getUnidadConteo(), d.getCantidadObtenida());
                })
                .toList();
        List<InsumoUsadoResponse> insumos = loteInsumoRepository.findByLoteId(l.getId()).stream()
                .map(i -> new InsumoUsadoResponse(i.getInsumo().getId(), i.getInsumo().getNombre(),
                        i.getInsumo().getUnidadMedida(), i.getCantidad()))
                .toList();

        Receta receta = l.getReceta();
        Presentacion salidaEsperada = receta != null ? receta.getPresentacion() : null;
        BigDecimal diferencia = null;
        if (l.getEstado() == EstadoLote.FINALIZADA && salidaEsperada != null && l.getCantidadEsperada() != null) {
            int obtenida = detalles.stream()
                    .filter(d -> d.presentacionId().equals(salidaEsperada.getId()))
                    .mapToInt(DetalleSalidaResponse::cantidadObtenida).sum();
            diferencia = BigDecimal.valueOf(obtenida).subtract(l.getCantidadEsperada());
        }
        return new LoteProduccionResponse(
                l.getId(), l.getFecha(), l.getProducto().getId(), l.getProducto().getNombre(), l.getLitrosUsados(),
                detalles, l.getRendimientoPct(), l.getProducto().getRendimientoEsperadoPct(),
                l.getRegistrosVinculados().stream().map(RegistroAcopio::getId).toList(),
                l.getUnidadesEsperadas(), l.getRendimientoEsperadoPctSnapshot(), recepcionesConsumidas,
                l.getMotivoRendimiento(), l.getRevisionRendimiento(), insumos,
                l.getEstado().name(), receta != null ? receta.getId() : null, receta != null ? receta.getNombre() : null,
                l.getCantidadEsperada(), salidaEsperada != null ? salidaEsperada.getId() : null,
                salidaEsperada != null ? salidaEsperada.getNombre() : null, diferencia,
                l.getIniciadoEn(), l.getFinalizadoEn(), l.getCanceladoEn());
    }

    private static String normalizarMotivo(String motivo) {
        return motivo == null || motivo.isBlank() ? null : motivo.trim();
    }
}
