package com.planta.acopio.service;

import com.planta.acopio.dto.AcopioDeJornadaResponse;
import com.planta.acopio.dto.JornadaBandejaResponse;
import com.planta.acopio.dto.JornadaDTO;
import com.planta.acopio.dto.JornadaDetalleResponse;
import com.planta.acopio.dto.JornadaEstadoRequest;
import com.planta.acopio.dto.JornadaResponse;
import com.planta.acopio.entity.EstadoJornada;
import com.planta.acopio.entity.Jornada;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.JornadaRepository;
import com.planta.acopio.repository.RegistroAcopioRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Fase 1 -- Nucleo de jornada (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend). Una Jornada es un
// viaje concreto de recoleccion de un acopiador con un vehiculo. Idempotente por uuidCliente en la
// creacion (mismo criterio que RegistroAcopioService.registrarOIgnorarSiDuplicado); las transiciones de
// estado (finalizar/cancelar) son idempotentes por si mismas: reenviar la misma transicion cuando el
// estado actual ya es el destino no falla, devuelve la jornada tal cual esta.
@Service
public class JornadaService {

    private final JornadaRepository jornadaRepository;
    private final UnidadRepository unidadRepository;
    private final UsuarioRepository usuarioRepository;
    private final RegistroAcopioRepository registroAcopioRepository;

    public JornadaService(JornadaRepository jornadaRepository, UnidadRepository unidadRepository,
                           UsuarioRepository usuarioRepository, RegistroAcopioRepository registroAcopioRepository) {
        this.jornadaRepository = jornadaRepository;
        this.unidadRepository = unidadRepository;
        this.usuarioRepository = usuarioRepository;
        this.registroAcopioRepository = registroAcopioRepository;
    }

    @Transactional
    public JornadaResponse crear(JornadaDTO dto) {
        var existente = jornadaRepository.findByUuidCliente(dto.uuidCliente());
        if (existente.isPresent()) {
            return toResponse(existente.get());
        }

        Usuario acopiador = usuarioActual();

        Unidad unidad = unidadRepository.findById(dto.unidadId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Unidad no encontrada: " + dto.unidadId()));
        if (unidad.getResponsable() == null || !unidad.getResponsable().getId().equals(acopiador.getId())) {
            throw new ValidacionNegocioException(
                    "La unidad " + unidad.getPlaca() + " no esta asignada al acopiador autenticado");
        }

        EstadoJornada estado = dto.estado() != null ? dto.estado() : EstadoJornada.EN_RECOLECCION;
        if (estado == EstadoJornada.RECIBIDA) {
            throw new ValidacionNegocioException("No se puede crear una jornada directamente en estado RECIBIDA");
        }
        boolean terminal = estado == EstadoJornada.REGRESANDO_A_PLANTA || estado == EstadoJornada.CANCELADA;
        if (terminal && dto.horaFin() == null) {
            throw new ValidacionNegocioException("horaFin es obligatoria al crear una jornada ya en estado " + estado);
        }

        // Solo un recorrido todavia activo (EN_RECOLECCION/REGRESANDO_A_PLANTA) puede chocar con otro ya
        // activo -- una jornada creada directamente CANCELADA (recorrido completo capturado offline) nunca
        // desplaza a otra ni corre riesgo de conflicto (ESPEC §5).
        if (estado.esActiva()) {
            verificarSinJornadaActivaConflictiva(acopiador.getId(), unidad.getId());
        }

        Jornada jornada = new Jornada();
        jornada.setUuidCliente(dto.uuidCliente());
        jornada.setAcopiador(acopiador);
        jornada.setUnidad(unidad);
        jornada.setEstado(estado);
        jornada.setHoraInicio(dto.horaInicio());
        jornada.setGpsLatInicio(dto.gpsLatInicio());
        jornada.setGpsLngInicio(dto.gpsLngInicio());
        if (terminal) {
            jornada.setHoraFin(dto.horaFin());
            jornada.setGpsLatFin(dto.gpsLatFin());
            jornada.setGpsLngFin(dto.gpsLngFin());
        }
        if (estado == EstadoJornada.CANCELADA) {
            jornada.setMotivoCancelacion(dto.motivoCancelacion());
        }

        try {
            jornada = jornadaRepository.save(jornada);
        } catch (DataIntegrityViolationException ex) {
            // Cerrojo real contra la condicion de carrera (indice parcial unico, V3__jornada.sql): dos
            // dispositivos creando a la vez para el mismo acopiador/unidad. El caso normal (sin carrera) ya
            // se rechazo arriba con un mensaje mas especifico.
            throw new ConflictoNegocioException(
                    "Ya existe una jornada activa para este acopiador o vehiculo (creada concurrentemente)");
        }

        return toResponse(jornada);
    }

    @Transactional
    public JornadaResponse aplicarTransicion(JornadaEstadoRequest dto) {
        Jornada jornada = jornadaRepository.findByUuidCliente(dto.uuidCliente())
                .orElseThrow(() -> new RecursoNoEncontradoException("Jornada no encontrada: " + dto.uuidCliente()));

        Usuario acopiador = usuarioActual();
        if (!jornada.getAcopiador().getId().equals(acopiador.getId())) {
            throw new ValidacionNegocioException("La jornada indicada no pertenece al acopiador autenticado");
        }

        return switch (dto.tipo()) {
            case FINALIZAR -> toResponse(finalizar(jornada, dto));
            case CANCELAR -> toResponse(cancelar(jornada, dto));
        };
    }

    private Jornada finalizar(Jornada jornada, JornadaEstadoRequest dto) {
        if (jornada.getEstado() == EstadoJornada.REGRESANDO_A_PLANTA) {
            return jornada; // idempotente: reintento de la misma transicion ya aplicada
        }
        if (jornada.getEstado() != EstadoJornada.EN_RECOLECCION) {
            throw new ValidacionNegocioException(
                    "No se puede finalizar una jornada en estado " + jornada.getEstado());
        }
        jornada.setEstado(EstadoJornada.REGRESANDO_A_PLANTA);
        jornada.setHoraFin(dto.horaEvento());
        jornada.setGpsLatFin(dto.gpsLat());
        jornada.setGpsLngFin(dto.gpsLng());
        return jornadaRepository.save(jornada);
    }

    private Jornada cancelar(Jornada jornada, JornadaEstadoRequest dto) {
        // Idempotente en sentido amplio (antes solo cubria CANCELADA): la jornada ya llego a un estado
        // terminal por CUALQUIER camino legitimo -- esta misma transicion reenviada, o RECIBIDA porque
        // RECEPCION la recibio en planta mientras este dispositivo todavia no se enteraba (reconciliacion
        // del lado movil, ver SyncEngineImpl.kt del frontend). Un CANCELAR tardio contra una jornada que ya
        // esta cerrada no es un error de negocio, es una noticia vieja -- devolverla tal cual, nunca 400.
        if (!jornada.getEstado().esActiva()) {
            return jornada;
        }
        if (jornada.getEstado() != EstadoJornada.EN_RECOLECCION) {
            throw new ValidacionNegocioException(
                    "No se puede cancelar una jornada en estado " + jornada.getEstado());
        }
        long acopiosAsociados = registroAcopioRepository.countByJornadaId(jornada.getId());
        if (acopiosAsociados > 0) {
            throw new ValidacionNegocioException(
                    "No se puede cancelar: la jornada ya tiene " + acopiosAsociados + " acopio(s) asociado(s)");
        }
        jornada.setEstado(EstadoJornada.CANCELADA);
        jornada.setHoraFin(dto.horaEvento());
        jornada.setGpsLatFin(dto.gpsLat());
        jornada.setGpsLngFin(dto.gpsLng());
        jornada.setMotivoCancelacion(dto.motivoCancelacion());
        return jornadaRepository.save(jornada);
    }

    // Fase 2 (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend): bandeja de RECEPCION -- jornadas
    // REGRESANDO_A_PLANTA disponibles para recibir. Sin paginacion (volumen bajo, mismo criterio que el
    // resto de bandejas operativas del sistema, ej. RegistroAcopioController): una jornada activa por
    // acopiador/unidad (ESPEC §5) implica que nunca hay muchas esperando a la vez.
    @Transactional(readOnly = true)
    public List<JornadaBandejaResponse> listarPendientesRecepcion() {
        return jornadaRepository.findByEstadoOrderByHoraInicioAsc(EstadoJornada.REGRESANDO_A_PLANTA).stream()
                .map(this::toBandejaResponse)
                .toList();
    }

    // Fase 2: detalle completo para que RECEPCION decida antes de registrar la recepcion -- vehiculo,
    // acopiador, proveedores visitados, acopios individuales y total esperado (ESPEC §14).
    @Transactional(readOnly = true)
    public JornadaDetalleResponse obtenerDetalleParaRecepcion(UUID id) {
        Jornada jornada = jornadaRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Jornada no encontrada: " + id));
        List<RegistroAcopio> acopios = registroAcopioRepository.findByJornadaIdOrderByFechaHoraAsc(jornada.getId());

        BigDecimal litrosEsperados = acopios.stream()
                .map(RegistroAcopio::getLitros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // LinkedHashSet: proveedores en orden de primera visita, sin duplicados -- ni el prompt ni la ESPEC
        // piden un orden distinto, y es el mas legible para RECEPCION (recorrido cronologico del viaje).
        List<String> proveedoresVisitados = acopios.stream()
                .map(r -> r.getProveedor().getNombre())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream().toList();
        List<AcopioDeJornadaResponse> acopiosResponse = acopios.stream()
                .map(r -> new AcopioDeJornadaResponse(
                        r.getId(), r.getProveedor().getId(), r.getProveedor().getNombre(), r.getFechaHora(), r.getLitros()))
                .toList();

        return new JornadaDetalleResponse(
                jornada.getId(), jornada.getUuidCliente(), jornada.getEstado(),
                jornada.getUnidad().getId(), jornada.getUnidad().getPlaca(),
                jornada.getAcopiador().getId(), jornada.getAcopiador().getNombre(),
                jornada.getHoraInicio(), jornada.getHoraFin(),
                litrosEsperados, proveedoresVisitados, acopiosResponse);
    }

    private JornadaBandejaResponse toBandejaResponse(Jornada jornada) {
        List<RegistroAcopio> acopios = registroAcopioRepository.findByJornadaIdOrderByFechaHoraAsc(jornada.getId());
        BigDecimal litrosEsperados = acopios.stream()
                .map(RegistroAcopio::getLitros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Set<UUID> proveedores = acopios.stream().map(r -> r.getProveedor().getId()).collect(java.util.stream.Collectors.toSet());

        return new JornadaBandejaResponse(
                jornada.getId(), jornada.getUuidCliente(), jornada.getEstado(),
                jornada.getUnidad().getId(), jornada.getUnidad().getPlaca(),
                jornada.getAcopiador().getId(), jornada.getAcopiador().getNombre(),
                jornada.getHoraInicio(), jornada.getHoraFin(),
                acopios.size(), proveedores.size(), litrosEsperados);
    }

    private void verificarSinJornadaActivaConflictiva(java.util.UUID acopiadorId, java.util.UUID unidadId) {
        // Mensaje enriquecido (fecha/hora + estado de la jornada que bloquea): antes decia solo "ya existe
        // una jornada activa", sin decir CUAL ni DESDE CUANDO -- el acopiador no tenia forma de saber si es
        // un dato real (recorrido de hoy sin terminar) o una jornada huerfana de dias atras. El frontend
        // puede mostrar este texto tal cual en la card de conflicto.
        jornadaRepository.findActivaPorAcopiador(acopiadorId).ifPresent(activa -> {
            throw new ConflictoNegocioException(
                    "Ya existe una jornada activa para este acopiador, iniciada el " + activa.getHoraInicio()
                            + " (estado " + activa.getEstado() + "). Debe finalizarse o recibirse en planta "
                            + "antes de iniciar una nueva.");
        });
        if (jornadaRepository.existeActivaParaUnidad(unidadId)) {
            throw new ConflictoNegocioException("Ya existe una jornada activa para este vehiculo");
        }
    }

    // Soporte/administracion (ver AdminJornadaController): todas las jornadas activas del sistema, sin
    // filtrar por estado como listarPendientesRecepcion() -- incluye EN_RECOLECCION, que la bandeja normal
    // de RECEPCION nunca ve. Reutiliza toBandejaResponse tal cual (mismo shape que la bandeja normal).
    @Transactional(readOnly = true)
    public List<JornadaBandejaResponse> listarTodasActivas() {
        return jornadaRepository.findAllActivas().stream()
                .map(this::toBandejaResponse)
                .toList();
    }

    // Soporte/administracion: valvula de escape para una jornada huerfana que el propio acopiador ya no
    // puede destrabar desde la app (ej. EN_RECOLECCION cuyo FINALIZAR nunca llego a sincronizarse, o
    // REGRESANDO_A_PLANTA que nadie recibio). A diferencia de cancelar() (transicion normal, exige
    // EN_RECOLECCION sin acopios asociados), esta no tiene esas restricciones: cierra desde cualquier
    // estado activo y aunque ya tenga acopios. Los RegistroAcopio ya capturados NO se tocan -- solo quedan
    // sin jornada activa asociada, igual que cualquier registro historico previo a esta funcionalidad.
    @Transactional
    public JornadaResponse cerrarForzado(java.util.UUID id, String motivo) {
        Jornada jornada = jornadaRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Jornada no encontrada: " + id));
        if (!jornada.getEstado().esActiva()) {
            return toResponse(jornada); // idempotente: ya esta RECIBIDA o CANCELADA
        }
        jornada.setEstado(EstadoJornada.CANCELADA);
        if (jornada.getHoraFin() == null) {
            jornada.setHoraFin(java.time.LocalDateTime.now());
        }
        jornada.setMotivoCancelacion(
                motivo != null && !motivo.isBlank() ? motivo : "Cierre forzado por administrador (jornada huérfana)");
        return toResponse(jornadaRepository.save(jornada));
    }

    // Reconciliacion cliente-servidor (ver SyncEngineImpl.kt del frontend, `reconciliarJornadaActivaConServidor`):
    // el modelo movil del acopiador no modela el estado RECIBIDA (deliberado, ver EstadoJornada.kt del
    // frontend) y nunca tenia forma de saber que su jornada REGRESANDO_A_PLANTA ya fue recibida en planta --
    // por eso la card seguia mostrando "activa" y el contador de tiempo transcurrido indefinidamente. Este
    // endpoint es la fuente de verdad que el dispositivo consulta para reconciliar su copia local.
    @Transactional(readOnly = true)
    public java.util.Optional<JornadaResponse> obtenerActivaDelAcopiadorAutenticado() {
        Usuario acopiador = usuarioActual();
        return jornadaRepository.findActivaPorAcopiador(acopiador.getId()).map(this::toResponse);
    }

    private Usuario usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth.getName();
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));
    }

    private JornadaResponse toResponse(Jornada j) {
        return new JornadaResponse(
                j.getId(), j.getUuidCliente(), j.getUnidad().getId(), j.getEstado(),
                j.getHoraInicio(), j.getGpsLatInicio(), j.getGpsLngInicio(),
                j.getHoraFin(), j.getGpsLatFin(), j.getGpsLngFin(), j.getMotivoCancelacion());
    }
}
