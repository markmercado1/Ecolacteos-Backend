package com.planta.acopio.service;

import com.planta.acopio.dto.*;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoInactivoException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.ComunicadoRepository;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.MotivoObservacionRepository;
import com.planta.acopio.repository.PrecioLitroRepository;
import com.planta.acopio.repository.PrediccionProveedorRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.UnidadRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// CORE_DESIGN.md §11, flujo 2. Sube lotes de las 4 entidades offline-critical
// (RegistroAcopio, AnalisisCalidad, LoteProduccion, Venta) y descarga catalogos
// incrementales via /sync/cambios. Cada item del lote se procesa con el flujo
// idempotente de su propio Service; un item que falla NO aborta el resto del
// lote (CORE_DESIGN.md §16, prioridad 2).
@Service
public class SyncService {

    // Auditoria de bajo riesgo (Fase 5): sin limite, un dispositivo con meses
    // de datos acumulados offline podria mandar un payload sin cota. 500 es
    // un techo conservador -- muy por encima de una jornada real de un solo
    // acopiador/vehiculo, pero acota el peor caso.
    private static final int MAX_ITEMS_POR_LOTE = 500;

    private final RegistroAcopioService registroAcopioService;
    private final AnalisisCalidadService analisisCalidadService;
    private final LoteProduccionService loteProduccionService;
    private final VentaService ventaService;
    private final JornadaService jornadaService;
    private final ConciliacionService conciliacionService;
    private final PuntoGpsJornadaService puntoGpsJornadaService;
    private final ProveedorService proveedorService;
    private final PrecioLitroRepository precioLitroRepository;
    private final ComunicadoRepository comunicadoRepository;
    private final PrediccionProveedorRepository prediccionProveedorRepository;
    private final MotivoObservacionRepository motivoObservacionRepository;
    private final ProductoRepository productoRepository;
    private final PresentacionRepository presentacionRepository;
    private final UnidadRepository unidadRepository;
    private final InsumoRepository insumoRepository;
    private final Validator validator;

    public SyncService(RegistroAcopioService registroAcopioService, AnalisisCalidadService analisisCalidadService,
                        LoteProduccionService loteProduccionService, VentaService ventaService,
                        JornadaService jornadaService, ConciliacionService conciliacionService,
                        PuntoGpsJornadaService puntoGpsJornadaService,
                        ProveedorService proveedorService, PrecioLitroRepository precioLitroRepository,
                        ComunicadoRepository comunicadoRepository, PrediccionProveedorRepository prediccionProveedorRepository,
                        MotivoObservacionRepository motivoObservacionRepository, ProductoRepository productoRepository,
                        PresentacionRepository presentacionRepository,
                        UnidadRepository unidadRepository, InsumoRepository insumoRepository, Validator validator) {
        this.registroAcopioService = registroAcopioService;
        this.analisisCalidadService = analisisCalidadService;
        this.loteProduccionService = loteProduccionService;
        this.ventaService = ventaService;
        this.jornadaService = jornadaService;
        this.conciliacionService = conciliacionService;
        this.puntoGpsJornadaService = puntoGpsJornadaService;
        this.proveedorService = proveedorService;
        this.precioLitroRepository = precioLitroRepository;
        this.comunicadoRepository = comunicadoRepository;
        this.prediccionProveedorRepository = prediccionProveedorRepository;
        this.motivoObservacionRepository = motivoObservacionRepository;
        this.productoRepository = productoRepository;
        this.presentacionRepository = presentacionRepository;
        this.unidadRepository = unidadRepository;
        this.insumoRepository = insumoRepository;
        this.validator = validator;
    }

    // Segunda auditoria (flujos end-to-end), hallazgo critico §11/§14-G:
    // ESTOS 4 METODOS DELIBERADAMENTE NO SON @Transactional. Cada llamada a
    // procesador.apply(item) invoca un service (RegistroAcopioService,
    // AnalisisCalidadService, LoteProduccionService, VentaService) que YA es
    // @Transactional por si mismo. Si este metodo tambien lo fuera, Spring
    // uniria la transaccion del item a la MISMA transaccion fisica del lote
    // completo (propagacion REQUIRED por defecto); al lanzar una excepcion de
    // negocio (RuntimeException), el interceptor transaccional del metodo
    // INTERNO marca esa transaccion compartida como rollback-only ANTES de que
    // el catch de procesarLote() la atrape -- el catch evita que la excepcion
    // se propague, pero no revierte la marca. El lote entero terminaria
    // lanzando UnexpectedRollbackException al hacer commit, revirtiendo
    // tambien los items que si tuvieron exito. Sin @Transactional aqui, cada
    // item corre en su propia transaccion fisica independiente (una nueva por
    // llamada, ya que no hay ninguna abierta que heredar), que es exactamente
    // la tolerancia a fallos parciales que este service dice implementar.
    public SyncResultResponse procesarLoteRegistrosAcopio(List<RegistroAcopioDTO> lote) {
        return procesarLote(lote, RegistroAcopioDTO::uuidCliente,
                dto -> registroAcopioService.registrarOIgnorarSiDuplicado(dto).id());
    }

    public SyncResultResponse procesarLoteAnalisisCalidad(List<AnalisisCalidadRequest> lote) {
        return procesarLote(lote, AnalisisCalidadRequest::uuidCliente,
                dto -> analisisCalidadService.registrarOIgnorarSiDuplicado(dto).id());
    }

    public SyncResultResponse procesarLoteLotesProduccion(List<CrearLoteRequest> lote) {
        return procesarLote(lote, CrearLoteRequest::uuidCliente, dto -> loteProduccionService.crear(dto).id());
    }

    public SyncResultResponse procesarLoteVentas(List<VentaRequest> lote) {
        return procesarLote(lote, VentaRequest::uuidCliente, dto -> ventaService.registrarOIgnorarSiDuplicado(dto).id());
    }

    // Fase 1 de jornada (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend): dos lotes distintos --
    // creacion (idempotente por uuidCliente, igual que los 4 de arriba) y transicion de estado
    // (finalizar/cancelar, tambien idempotente pero sobre una jornada YA creada). El motor de sync del
    // movil los envia en ese orden, y siempre despues de que este lote de creacion se haya resuelto, para
    // que un item recien creado en este mismo ciclo pueda promover a sus RegistroAcopio hijos (mismo
    // criterio de orden que RegistroAcopio -> AnalisisCalidad/LoteProduccion).
    public SyncResultResponse procesarLoteJornadas(List<JornadaDTO> lote) {
        return procesarLote(lote, JornadaDTO::uuidCliente, dto -> jornadaService.crear(dto).id());
    }

    public SyncResultResponse procesarLoteJornadaEstados(List<JornadaEstadoRequest> lote) {
        return procesarLote(lote, JornadaEstadoRequest::uuidCliente, dto -> jornadaService.aplicarTransicion(dto).id());
    }

    // Fase 2 de jornada (docs/ESPEC_JORNADA_TRAZABILIDAD.md): recepcion vinculada a una Jornada YA conocida
    // por el servidor -- a diferencia de RegistroAcopio, RECEPCION nunca crea la jornada, solo la ve desde
    // GET /api/jornadas (Fase 2), asi que su id de servidor siempre es real desde el dia uno para este
    // dispositivo -- no hace falta ningun mecanismo de resolucion de padre como el de RegistroAcopio/Jornada.
    public SyncResultResponse procesarLoteRecepcionesJornada(List<RecepcionJornadaRequest> lote) {
        return procesarLote(lote, RecepcionJornadaRequest::uuidCliente,
                dto -> conciliacionService.registrarRecepcionDeJornada(dto).id());
    }

    // Fase 3 -- Recorrido GPS (docs/ESPEC_JORNADA_TRAZABILIDAD.md): mismo patron idempotente que el resto.
    // El motor de sync del movil solo envia puntos cuya jornada ya tiene id de servidor resuelto (mismo
    // criterio que RegistroAcopioDTO.jornadaId) -- un punto nunca llega aca antes que su jornada.
    public SyncResultResponse procesarLotePuntosGps(List<PuntoGpsJornadaDTO> lote) {
        return procesarLote(lote, PuntoGpsJornadaDTO::uuidCliente,
                dto -> puntoGpsJornadaService.registrarOIgnorarSiDuplicado(dto).getId());
    }

    // Aisla cada item en su propia transaccion fisica (ver comentario arriba):
    // si uno falla (Bean Validation o regla de negocio), se reporta como error
    // individual (con motivo) y el resto del lote continua -- CORE_DESIGN.md
    // §16: la sincronizacion batch NO debe ser todo-o-nada. Por eso la
    // validacion se hace aqui manualmente (Validator inyectado) en vez de con
    // @Valid en el Controller, que abortaria el lote completo ante el primer
    // item invalido.
    //
    // DATA-014: 'procesador' devuelve el UUID real (antes devolvia el DTO de
    // respuesta completo y se descartaba sin leerlo) -- cada uno de los 4
    // Service ya es idempotente por uuidCliente (ver el findByUuidCliente/
    // existeRegistroYaVinculadoAOtroLote de cada uno), asi que reintentar un
    // uuidCliente ya confirmado siempre devuelve el mismo id, nunca crea una
    // fila nueva.
    private <T> SyncResultResponse procesarLote(List<T> lote, Function<T, String> uuidExtractor, Function<T, UUID> procesador) {
        if (lote.size() > MAX_ITEMS_POR_LOTE) {
            throw new ValidacionNegocioException(
                    "El lote excede el maximo permitido de " + MAX_ITEMS_POR_LOTE
                            + " items (recibidos: " + lote.size() + "); dividir en lotes mas pequeños");
        }

        List<String> confirmados = new ArrayList<>();
        List<SyncResultResponse.SyncErrorItem> errores = new ArrayList<>();
        List<SyncResultResponse.ConfirmacionItem> confirmadosConId = new ArrayList<>();

        for (T item : lote) {
            String uuidCliente = uuidExtractor.apply(item);

            Set<ConstraintViolation<T>> violaciones = validator.validate(item);
            if (!violaciones.isEmpty()) {
                String detalle = violaciones.stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                        .collect(Collectors.joining("; "));
                errores.add(new SyncResultResponse.SyncErrorItem(uuidCliente, detalle));
                continue;
            }

            try {
                UUID id = procesador.apply(item);
                confirmados.add(uuidCliente);
                confirmadosConId.add(new SyncResultResponse.ConfirmacionItem(uuidCliente, id));
            } catch (RecursoNoEncontradoException | RecursoInactivoException | ValidacionNegocioException
                     | ConstraintViolationException | ConflictoNegocioException ex) {
                // ConflictoNegocioException (409, ej. segunda jornada activa creada desde otro dispositivo):
                // se reporta igual que cualquier otro rechazo de negocio -- error individual, el resto del
                // lote sigue. El motor de sync del movil lo deja FAILED permanente y conserva los datos
                // locales (docs/ESPEC_JORNADA_TRAZABILIDAD.md, "no los elimines").
                errores.add(new SyncResultResponse.SyncErrorItem(uuidCliente, ex.getMessage()));
            }
        }

        return new SyncResultResponse(confirmados, errores, confirmadosConId);
    }

    // GET /api/sync/cambios: catalogos + precio + comunicados + predicciones
    // descargados por el dispositivo (CORE_DESIGN.md §12). El parametro
    // 'desde' se recibe para uso futuro de filtrado incremental fino; en v1
    // se devuelve el estado completo de cada catalogo (volumen bajo: 4
    // unidades, una planta -- no justifica paginacion en v1, CORE_DESIGN.md §6).
    // Flujo end-to-end en vivo (verificacion con BD real): @Transactional
    // necesario aqui (a diferencia de los 4 metodos de arriba, que NO deben
    // llevarlo) porque Comunicado.zonasDestino es @ManyToMany LAZY y se
    // recorre al construir ComunicadoResponse -- sin sesion de Hibernate
    // abierta (open-in-view=false) lanzaba LazyInitializationException.
    @Transactional(readOnly = true)
    public CambiosResponse obtenerCambiosDesde(Instant desde) {
        List<ProveedorPublicoResponse> proveedores = proveedorService.listarPublico();

        var precioVigente = precioLitroRepository.findVigenteHoy()
                .map(p -> p.getPrecio())
                .orElse(null);

        List<ComunicadoResponse> comunicados = comunicadoRepository.findAll().stream()
                .map(c -> new ComunicadoResponse(c.getId(), c.getMensaje(), c.getFecha(),
                        c.getZonasDestino().stream().map(z -> z.getNombre()).toList()))
                .toList();

        List<PrediccionProveedorResponse> predicciones = prediccionProveedorRepository.findAll().stream()
                .map(p -> new PrediccionProveedorResponse(p.getProveedor().getId(), p.getFechaPrevista(),
                        p.getLitrosEstimadosMin(), p.getLitrosEstimadosMax()))
                .toList();

        List<MotivoObservacionResponse> motivos = motivoObservacionRepository.findByActivoTrue().stream()
                .map(m -> new MotivoObservacionResponse(m.getId(), m.getDescripcion()))
                .toList();

        List<ProductoResponse> productos = productoRepository.findByActivoTrue().stream()
                .map(p -> new ProductoResponse(p.getId(), p.getNombre(), p.getFamilia().getId(), p.getFamilia().getNombre(),
                        p.getRendimientoEsperadoPct(), p.getCicloCapital() != null ? p.getCicloCapital().name() : null,
                        p.getActivo(), presentacionRepository.findByProductoIdOrderByNombre(p.getId()).stream()
                                .map(pr -> new PresentacionResponse(pr.getId(), pr.getProducto().getId(), pr.getNombre(),
                                        pr.getContenidoCantidad(),
                                        pr.getContenidoUnidad() != null ? pr.getContenidoUnidad().name() : null,
                                        pr.getUnidadConteo(), pr.getActivo(), FotoCatalogoService.urlDe(pr.getFotoArchivo())))
                                .toList(), com.planta.acopio.dto.RangoRendimientoDto.from(p.getRangoRendimiento()),
                        FotoCatalogoService.urlDe(p.getFotoArchivo())))
                .toList();

        // Segunda auditoria (flujos end-to-end), hallazgo critico §14-D/G:
        // sin esto, ACOPIADOR/RECEPCION no tenian ninguna forma de conocer que
        // unidadId corresponde a que camion -- GET /api/admin/unidades es
        // ADMIN-only, y unidad no tiene columna 'activo' en schema.sql (catalogo
        // completo, sin filtrar, igual que la baja volumetria ya documentada
        // para el resto de catalogos de este endpoint).
        List<UnidadResponse> unidades = unidadRepository.findAll().stream()
                .map(u -> new UnidadResponse(u.getId(), u.getPlaca(), u.getCapacidadTon(),
                        u.getZona() != null ? u.getZona().getId() : null,
                        u.getResponsable().getId(), u.getResponsable().getNombre()))
                .toList();

        // Feature "Produccion diaria": mismo criterio que tiposQueso/unidades -- PRODUCCION necesita el
        // catalogo de insumos sin el rol ADMIN que exige GET /api/admin/insumos.
        List<InsumoResponse> insumos = insumoRepository.findByActivoTrue().stream()
                .map(i -> new InsumoResponse(i.getId(), i.getNombre(), i.getUnidadMedida(), i.getActivo(), i.getStock()))
                .toList();

        // El movil exige rendimientoEsperadoPct y cicloCapital no nulos en cada tipo de queso: los productos
        // sin esos datos se omiten para no romper la deserializacion de todo el catalogo.
        List<TipoQuesoResponse> tiposQueso = productos.stream()
                .filter(p -> p.rendimientoEsperadoPct() != null && p.cicloCapital() != null)
                .map(p -> new TipoQuesoResponse(p.id(), p.nombre(), p.rendimientoEsperadoPct(), p.cicloCapital(), p.activo()))
                .toList();

        return new CambiosResponse(Instant.now(), proveedores, precioVigente, comunicados, predicciones, motivos, productos, unidades, insumos, tiposQueso);
    }
}
