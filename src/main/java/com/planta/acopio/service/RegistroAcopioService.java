package com.planta.acopio.service;

import com.planta.acopio.dto.AcopioPorJornadaResponse;
import com.planta.acopio.dto.RegistroAcopioDTO;
import com.planta.acopio.dto.RegistroAcopioResponse;
import com.planta.acopio.dto.RegistroAcopioResumenResponse;
import com.planta.acopio.dto.TotalAcopioProveedorResponse;
import com.planta.acopio.entity.Jornada;
import com.planta.acopio.entity.MotivoObservacion;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.RecursoInactivoException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.JornadaRepository;
import com.planta.acopio.repository.LoteProduccionRepository;
import com.planta.acopio.repository.MotivoObservacionRepository;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.RegistroAcopioRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Operacion de negocio central del sistema (CORE_DESIGN.md §11, flujo 1).
// RegistroAcopio es INMUTABLE una vez creado: este service deliberadamente
// no expone ningun metodo de actualizacion/borrado sobre el, solo creacion
// idempotente y consulta. Los "cambios" posteriores viven en
// CorreccionRegistroService (anexado, nunca reemplaza).
@Service
public class RegistroAcopioService {

    private final RegistroAcopioRepository repository;
    private final ProveedorRepository proveedorRepository;
    private final UnidadRepository unidadRepository;
    private final UsuarioRepository usuarioRepository;
    private final MotivoObservacionRepository motivoObservacionRepository;
    private final AnomaliaService anomaliaService;
    private final LoteProduccionRepository loteProduccionRepository;
    private final JornadaRepository jornadaRepository;
    private final ZonaHistoricaService zonaHistoricaService;

    private final long toleranciaDiasAtras;
    private final long toleranciaMinutosAdelante;

    public RegistroAcopioService(
            RegistroAcopioRepository repository,
            ProveedorRepository proveedorRepository,
            UnidadRepository unidadRepository,
            UsuarioRepository usuarioRepository,
            MotivoObservacionRepository motivoObservacionRepository,
            AnomaliaService anomaliaService,
            LoteProduccionRepository loteProduccionRepository,
            JornadaRepository jornadaRepository,
            ZonaHistoricaService zonaHistoricaService,
            @Value("${app.registro-acopio.tolerancia-dias-atras}") long toleranciaDiasAtras,
            @Value("${app.registro-acopio.tolerancia-minutos-adelante}") long toleranciaMinutosAdelante) {
        this.repository = repository;
        this.proveedorRepository = proveedorRepository;
        this.unidadRepository = unidadRepository;
        this.usuarioRepository = usuarioRepository;
        this.motivoObservacionRepository = motivoObservacionRepository;
        this.anomaliaService = anomaliaService;
        this.loteProduccionRepository = loteProduccionRepository;
        this.jornadaRepository = jornadaRepository;
        this.zonaHistoricaService = zonaHistoricaService;
        this.toleranciaDiasAtras = toleranciaDiasAtras;
        this.toleranciaMinutosAdelante = toleranciaMinutosAdelante;
    }

    // Idempotencia por uuidCliente (decision aprobada §18 punto 2): si ya existe,
    // se devuelve el existente en vez de duplicar o lanzar error -- un reintento
    // de sincronizacion nunca debe crear una segunda fila.
    @Transactional
    public RegistroAcopioResponse registrarOIgnorarSiDuplicado(RegistroAcopioDTO dto) {
        var existente = repository.findByUuidCliente(dto.uuidCliente());
        if (existente.isPresent()) {
            return toResponse(existente.get());
        }

        validarTolerancia(dto.fechaHora());

        Proveedor proveedor = proveedorRepository.findById(dto.proveedorId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + dto.proveedorId()));
        if (!Boolean.TRUE.equals(proveedor.getActivo())) {
            throw new RecursoInactivoException("El proveedor " + proveedor.getNombre() + " esta inactivo");
        }

        Unidad unidad = unidadRepository.findById(dto.unidadId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Unidad no encontrada: " + dto.unidadId()));

        // El acopiador es el usuario autenticado en el token JWT (RF-ACO), no un
        // campo enviado por el cliente -- evita que un dispositivo suplante a otro.
        Usuario acopiador = usuarioActual();
        if (unidad.getResponsable() == null || !unidad.getResponsable().getId().equals(acopiador.getId())) {
            throw new ValidacionNegocioException(
                    "La unidad " + unidad.getPlaca() + " no esta asignada al acopiador autenticado");
        }

        RegistroAcopio registro = new RegistroAcopio();
        registro.setUuidCliente(dto.uuidCliente());
        registro.setProveedor(proveedor);
        registro.setAcopiador(acopiador);
        registro.setUnidad(unidad);
        registro.setFechaHora(dto.fechaHora());
        registro.setLitros(dto.litros());
        registro.setGpsLat(dto.gpsLat());
        registro.setGpsLng(dto.gpsLng());
        registro.setLitrosPorVoz(Boolean.TRUE.equals(dto.litrosPorVoz()));
        registro.setFotoUrl(dto.fotoUrl());
        registro.setSincronizadoEn(LocalDateTime.now());
        // La entrega conserva la zona que tenia el proveedor cuando se hizo (fechaHora), aunque se sincronice despues
        // de un cambio de zona. Sin periodo claro queda sin zona: nunca se usa la zona actual como relleno.
        zonaHistoricaService.zonaEn(proveedor.getId(), dto.fechaHora()).ifPresent(registro::setZona);

        if (dto.motivoObservacionId() != null) {
            MotivoObservacion motivo = motivoObservacionRepository.findById(dto.motivoObservacionId())
                    .orElseThrow(() -> new RecursoNoEncontradoException("Motivo de observacion no encontrado: " + dto.motivoObservacionId()));
            if (!Boolean.TRUE.equals(motivo.getActivo())) {
                throw new RecursoInactivoException("El motivo de observacion '" + motivo.getDescripcion() + "' esta desactivado");
            }
            registro.setMotivoObservacion(motivo);
        }

        // Fase 1 de jornada: asociacion opcional -- el motor de sync del movil ya resolvio este id de
        // servidor antes de enviar el registro (nunca se sincroniza un registro cuya jornada todavia no
        // se confirmo). Defensa en profundidad: la jornada debe pertenecer al mismo acopiador autenticado,
        // igual criterio que la validacion de unidad de arriba.
        if (dto.jornadaId() != null) {
            Jornada jornada = jornadaRepository.findById(dto.jornadaId())
                    .orElseThrow(() -> new RecursoNoEncontradoException("Jornada no encontrada: " + dto.jornadaId()));
            if (!jornada.getAcopiador().getId().equals(acopiador.getId())) {
                throw new ValidacionNegocioException("La jornada indicada no pertenece al acopiador autenticado");
            }
            registro.setJornada(jornada);
        }

        registro = repository.save(registro);

        // RF-INN-03 (deteccion reactiva): se evalua al persistir el registro,
        // dentro de la misma transaccion del sync (CORE_DESIGN.md §11, flujo 1/7).
        anomaliaService.evaluarNuevoRegistro(registro);

        return toResponse(registro);
    }

    // Auditoria CRUD (prioridad BAJA): historialPorProveedor solo devuelve un
    // resumen liviano (RF-TRA-01); faltaba el detalle completo de un registro
    // puntual (gps, foto, motivo) para la vista previa a anexar una correccion.
    public RegistroAcopioResponse obtener(UUID id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Registro de acopio no encontrado: " + id)));
    }

    // Bloque 2 (confirmado): bandeja operativa con filtros combinables, todos
    // opcionales. Objetivo principal: CALIDAD obtiene de un solo llamado los
    // registros del dia que todavia no tienen analisis (sinAnalisis=true),
    // en vez de descargarlos uno por uno. Sin paginacion en este bloque
    // (decision explicita, ver conversacion) -- se deja para el bloque 5.
    public List<RegistroAcopioResponse> buscar(LocalDate fecha, UUID zonaId, Boolean sinAnalisis) {
        LocalDateTime fechaInicio = fecha != null ? fecha.atStartOfDay() : null;
        LocalDateTime fechaFin = fecha != null ? fecha.plusDays(1).atStartOfDay() : null;
        List<RegistroAcopio> encontrados = repository.buscarConFiltros(fechaInicio, fechaFin, zonaId, Boolean.TRUE.equals(sinAnalisis));
        Set<UUID> vinculados = idsYaVinculados(encontrados.stream().map(RegistroAcopio::getId).toList());
        return encontrados.stream().map(r -> toResponse(r, vinculados)).toList();
    }

    public List<RegistroAcopioResumenResponse> historialPorProveedor(UUID proveedorId) {
        // RF-TRA-01: historial liviano que el acopiador muestra al proveedor.
        List<RegistroAcopio> encontrados = repository.findByProveedorIdOrderByFechaHoraDesc(proveedorId);
        Set<UUID> vinculados = idsYaVinculados(encontrados.stream().map(RegistroAcopio::getId).toList());
        return encontrados.stream()
                .map(r -> new RegistroAcopioResumenResponse(
                        r.getId(), r.getFechaHora(), r.getLitros(), r.getMotivoObservacion() != null,
                        !vinculados.contains(r.getId())))
                .toList();
    }

    // ---------------------------------------------------------------------
    // Paginacion pendiente (web). Los metodos List<> de arriba se mantienen
    // intactos para la app movil y la bandeja de CALIDAD.

    public Page<RegistroAcopioResponse> buscarPaginado(LocalDate fecha, UUID zonaId, Boolean sinAnalisis,
                                                       String busqueda, Pageable pageable) {
        Page<RegistroAcopio> pagina = repository.buscarConFiltrosPaginado(
                inicioDe(fecha), finDe(fecha), zonaId, Boolean.TRUE.equals(sinAnalisis),
                BusquedaTexto.patronLike(busqueda), pageable);
        Set<UUID> vinculados = idsYaVinculados(pagina.getContent().stream().map(RegistroAcopio::getId).toList());
        return pagina.map(r -> toResponse(r, vinculados));
    }

    public Page<AcopioPorJornadaResponse> agruparPorJornada(LocalDate fecha, UUID zonaId, Boolean sinAnalisis,
                                                           String busqueda, Pageable pageable) {
        return repository.agruparPorJornadaPaginado(
                        inicioDe(fecha), finDe(fecha), zonaId, Boolean.TRUE.equals(sinAnalisis),
                        BusquedaTexto.patronLike(busqueda), pageable)
                .map(f -> new AcopioPorJornadaResponse(
                        FilaNativa.uuid(f[0]), FilaNativa.bool(f[1]), FilaNativa.texto(f[7]), FilaNativa.texto(f[6]),
                        FilaNativa.fechaHora(f[4]), FilaNativa.entero(f[2]), FilaNativa.decimal(f[3]),
                        FilaNativa.uuid(f[5])));
    }

    // Sin busqueda de texto a proposito: el grafico resume el filtro completo
    // (fecha/zona/sinAnalisis), igual que antes con la lista sin paginar.
    public List<TotalAcopioProveedorResponse> totalesPorProveedor(LocalDate fecha, UUID zonaId, Boolean sinAnalisis) {
        return repository.totalesPorProveedor(inicioDe(fecha), finDe(fecha), zonaId,
                        Boolean.TRUE.equals(sinAnalisis), null).stream()
                .map(f -> new TotalAcopioProveedorResponse(
                        FilaNativa.uuid(f[0]), FilaNativa.texto(f[1]), FilaNativa.decimal(f[2]), FilaNativa.entero(f[3])))
                .toList();
    }

    public Page<RegistroAcopioResumenResponse> historialPorProveedor(UUID proveedorId, Pageable pageable) {
        Page<RegistroAcopio> pagina = repository.findByProveedorIdOrderByFechaHoraDescIdDesc(proveedorId, pageable);
        Set<UUID> vinculados = idsYaVinculados(pagina.getContent().stream().map(RegistroAcopio::getId).toList());
        return pagina.map(r -> new RegistroAcopioResumenResponse(
                r.getId(), r.getFechaHora(), r.getLitros(), r.getMotivoObservacion() != null,
                !vinculados.contains(r.getId())));
    }

    private static LocalDateTime inicioDe(LocalDate fecha) {
        return fecha != null ? fecha.atStartOfDay() : null;
    }

    private static LocalDateTime finDe(LocalDate fecha) {
        return fecha != null ? fecha.plusDays(1).atStartOfDay() : null;
    }

    // Fase 4 PRODUCCION: version en bloque, para no consultar lote_produccion
    // una vez por fila -- ver LoteProduccionRepository.idsYaVinculados. IN()
    // con una coleccion vacia no es valido en JPQL, así que se corta antes.
    private Set<UUID> idsYaVinculados(List<UUID> registroAcopioIds) {
        if (registroAcopioIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(loteProduccionRepository.idsYaVinculados(registroAcopioIds));
    }

    private void validarTolerancia(LocalDateTime fechaHora) {
        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime limiteAtras = ahora.minusDays(toleranciaDiasAtras);
        LocalDateTime limiteAdelante = ahora.plusMinutes(toleranciaMinutosAdelante);

        if (fechaHora.isBefore(limiteAtras) || fechaHora.isAfter(limiteAdelante)) {
            throw new ValidacionNegocioException(
                    "fechaHora fuera de tolerancia permitida (-" + toleranciaDiasAtras + " dias / +"
                            + toleranciaMinutosAdelante + " min): " + fechaHora);
        }
    }

    private Usuario usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth.getName();
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));
    }

    private RegistroAcopioResponse toResponse(RegistroAcopio r) {
        boolean vinculado = loteProduccionRepository.existeRegistroYaVinculadoAOtroLote(r.getId());
        return construirResponse(r, !vinculado);
    }

    private RegistroAcopioResponse toResponse(RegistroAcopio r, Set<UUID> idsYaVinculados) {
        return construirResponse(r, !idsYaVinculados.contains(r.getId()));
    }

    private RegistroAcopioResponse construirResponse(RegistroAcopio r, boolean disponibleParaLote) {
        return new RegistroAcopioResponse(
                r.getId(), r.getUuidCliente(), r.getProveedor().getId(), r.getProveedor().getNombre(),
                r.getUnidad().getId(), r.getFechaHora(), r.getLitros(), r.getGpsLat(), r.getGpsLng(),
                r.getMotivoObservacion() != null ? r.getMotivoObservacion().getDescripcion() : null,
                r.getLitrosPorVoz(), r.getSincronizadoEn(), r.getFotoUrl(),
                disponibleParaLote, r.getJornada() != null ? r.getJornada().getId() : null);
    }
}
