package com.planta.acopio.service;

import com.planta.acopio.dto.RecepcionJornadaRequest;
import com.planta.acopio.dto.RecepcionPlantaRequest;
import com.planta.acopio.dto.RecepcionPlantaResponse;
import com.planta.acopio.entity.EstadoConciliacion;
import com.planta.acopio.entity.EstadoJornada;
import com.planta.acopio.entity.Jornada;
import com.planta.acopio.entity.RecepcionPlanta;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.JornadaRepository;
import com.planta.acopio.repository.RecepcionPlantaRepository;
import com.planta.acopio.repository.RegistroAcopioRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// RF-REC-02/03 (CORE_DESIGN.md §11, flujo 3). Implementa la mejora #7 aprobada:
// diferencia_pct es una columna GENERADA en PostgreSQL que devuelve 0% cuando
// litros_campo = 0 (para evitar division por cero) -- ese caso borde puede
// ocultar justo el descuadre mas grave posible. Este service NO depende
// unicamente de diferencia_pct para decidir el estado.
@Service
public class ConciliacionService {

    private final RecepcionPlantaRepository repository;
    private final UnidadRepository unidadRepository;
    private final UsuarioRepository usuarioRepository;
    private final ConfiguracionSistemaService configuracionSistemaService;
    private final EntityManager entityManager;
    private final RegistroAcopioRepository registroAcopioRepository;
    private final JornadaRepository jornadaRepository;
    private final InventarioLecheService inventarioLecheService;

    public ConciliacionService(RecepcionPlantaRepository repository, UnidadRepository unidadRepository,
                                UsuarioRepository usuarioRepository, ConfiguracionSistemaService configuracionSistemaService,
                                EntityManager entityManager, RegistroAcopioRepository registroAcopioRepository,
                                JornadaRepository jornadaRepository, InventarioLecheService inventarioLecheService) {
        this.repository = repository;
        this.unidadRepository = unidadRepository;
        this.usuarioRepository = usuarioRepository;
        this.configuracionSistemaService = configuracionSistemaService;
        this.entityManager = entityManager;
        this.registroAcopioRepository = registroAcopioRepository;
        this.jornadaRepository = jornadaRepository;
        this.inventarioLecheService = inventarioLecheService;
    }

    @Transactional
    public RecepcionPlantaResponse registrarYConciliar(RecepcionPlantaRequest dto) {
        String turno = (dto.turno() == null || dto.turno().isBlank()) ? "UNICO" : dto.turno();

        repository.findByFechaAndUnidadIdAndTurno(dto.fecha(), dto.unidadId(), turno)
                .ifPresent(r -> { throw new ConflictoNegocioException(
                        "Ya existe una recepcion para esa unidad/fecha/turno"); });

        Unidad unidad = unidadRepository.findById(dto.unidadId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Unidad no encontrada: " + dto.unidadId()));

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Usuario responsable = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));

        RecepcionPlanta recepcion = new RecepcionPlanta();
        recepcion.setFecha(dto.fecha());
        recepcion.setTurno(turno);
        recepcion.setUnidad(unidad);
        recepcion.setLitrosCampo(dto.litrosCampo());
        recepcion.setLitrosPlanta(dto.litrosPlanta());
        recepcion.setResponsable(responsable);
        recepcion.setEstado(EstadoConciliacion.OK); // provisional, se recalcula abajo

        // Fase 3, decision de negocio 3 (opcion B, confirmada): seleccion
        // explicita -- RECEPCION indica que RegistroAcopio componen esta
        // recepcion (mismo patron que LoteProduccionService). Opcional: un
        // litrosCampo=0 con litrosPlanta>0 es valido sin ningun registro vinculado.
        if (dto.registroAcopioIds() != null && !dto.registroAcopioIds().isEmpty()) {
            Set<RegistroAcopio> registros = new HashSet<>();
            for (UUID registroId : dto.registroAcopioIds()) {
                // Fix trazabilidad (confirmado): mismo patron y mismo orden que
                // LoteProduccionService.crear() -- chequeo de vinculo previo
                // ANTES de resolver el registro, misma ValidacionNegocioException.
                if (repository.existeRegistroYaVinculado(registroId)) {
                    throw new ValidacionNegocioException(
                            "El registro de acopio " + registroId + " ya pertenece a otra recepcion de planta");
                }
                registros.add(registroAcopioRepository.findById(registroId)
                        .orElseThrow(() -> new RecursoNoEncontradoException("Registro de acopio no encontrado: " + registroId)));
            }
            recepcion.setRegistrosVinculados(registros);
        }

        recepcion = repository.save(recepcion);

        // diferencia_pct es GENERATED ALWAYS -- se calcula en PostgreSQL al
        // insertar. Se relee la fila para tener el valor calculado antes de
        // decidir el estado final.
        entityManager.flush();
        entityManager.refresh(recepcion);

        recepcion.setEstado(calcularEstado(recepcion.getLitrosCampo(), recepcion.getLitrosPlanta(), recepcion.getDiferenciaPct()));

        // Fase 1 del refactor de Produccion: litrosPlanta (autoritativo) alimenta la existencia de leche en
        // la MISMA transaccion -- no puede existir una recepcion sin su entrada de leche (mismo criterio que
        // LoteProduccionService.crear() ya aplica para la entrada de inventario de queso).
        inventarioLecheService.registrarEntradaRecepcion(recepcion);

        return toResponse(recepcion);
    }

    // Fase 2 (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend): recepcion vinculada a una Jornada.
    // Reutiliza deliberadamente esta misma entidad/tabla (RecepcionPlanta) en vez de crear un agregado
    // paralelo: litrosCampo pasa a ser el total esperado AUTORITATIVO (SUM de RegistroAcopio de la jornada,
    // nunca un valor que RECEPCION digite), litrosPlanta sigue siendo lo medido en planta, y la columna
    // GENERATED diferencia_pct de Postgres (mismo criterio que registrarYConciliar) sigue siendo la fuente
    // real de diferenciaPct sin duplicar esa formula acá.
    @Transactional
    public RecepcionPlantaResponse registrarRecepcionDeJornada(RecepcionJornadaRequest dto) {
        var existente = repository.findByUuidCliente(dto.uuidCliente());
        if (existente.isPresent()) {
            return toResponse(existente.get());
        }

        Jornada jornada = jornadaRepository.findById(dto.jornadaId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Jornada no encontrada: " + dto.jornadaId()));

        // Regla obligatoria (ESPEC §14/§22): no se puede recibir una jornada EN_RECOLECCION, CANCELADA, ni
        // ya RECIBIDA -- solo REGRESANDO_A_PLANTA. El mensaje literal incluye el estado real para que el
        // movil pueda mostrarlo tal cual.
        if (jornada.getEstado() != EstadoJornada.REGRESANDO_A_PLANTA) {
            throw new ValidacionNegocioException(
                    "No se puede recibir una jornada en estado " + jornada.getEstado());
        }

        // Verificacion previa (mensaje 409 legible en el caso normal, sin carrera) -- el cerrojo real es el
        // indice parcial unico de V4__recepcion_jornada.sql (ver el catch de abajo para la condicion de carrera).
        if (repository.existsByJornadaId(jornada.getId())) {
            throw new ConflictoNegocioException("La jornada ya tiene una recepcion registrada");
        }

        String turno = (dto.turno() == null || dto.turno().isBlank()) ? "UNICO" : dto.turno();

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Usuario responsable = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));

        // Una sola consulta sirve para el total esperado, la vinculacion automatica (registrosVinculados) y
        // -- en JornadaService.obtenerDetalleParaRecepcion -- el detalle de acopios/proveedores: nunca se
        // confia en un total editable enviado por el movil (ESPEC §8/§14, regla obligatoria).
        List<RegistroAcopio> acopiosDeJornada = registroAcopioRepository.findByJornadaIdOrderByFechaHoraAsc(jornada.getId());
        BigDecimal litrosEsperados = acopiosDeJornada.stream()
                .map(RegistroAcopio::getLitros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        RecepcionPlanta recepcion = new RecepcionPlanta();
        recepcion.setUuidCliente(dto.uuidCliente());
        recepcion.setJornada(jornada);
        recepcion.setFecha(dto.horaLlegada().toLocalDate());
        recepcion.setTurno(turno);
        recepcion.setUnidad(jornada.getUnidad());
        recepcion.setLitrosCampo(litrosEsperados);
        recepcion.setLitrosPlanta(dto.litrosReales());
        recepcion.setHoraLlegada(dto.horaLlegada());
        recepcion.setObservacion(dto.observacion());
        recepcion.setResponsable(responsable);
        recepcion.setEstado(EstadoConciliacion.OK); // provisional, se recalcula abajo
        recepcion.setRegistrosVinculados(new HashSet<>(acopiosDeJornada));

        try {
            // saveAndFlush(), NO save() + entityManager.flush() por separado (segundo fix verificado con
            // Postgres real, no solo con Mockito, en la misma ronda que el de LoteProduccionService):
            // RecepcionPlanta.id es un UUID @GeneratedValue en cliente, asi que sin forzar el flush aqui el
            // INSERT real (y su posible violacion de indice unico) queda diferido. Un entityManager.flush()
            // llamado directamente (no a traves del proxy del repositorio) NO pasa por la traduccion de
            // excepciones de Spring (@Repository) -- lanza la excepcion nativa de Hibernate
            // (ConstraintViolationException), que este catch(DataIntegrityViolationException) nunca
            // atrapaba, y se propagaba cruda (500) en vez de traducirse a ConflictoNegocioException (409).
            // repository.saveAndFlush() si pasa por ese proxy y traduce correctamente. Confirmado en
            // caliente contra Postgres real con dos dispositivos recibiendo la misma jornada a la vez.
            recepcion = repository.saveAndFlush(recepcion);
        } catch (DataIntegrityViolationException ex) {
            // Cerrojo real contra la condicion de carrera: dos dispositivos recibiendo la misma jornada a
            // la vez (ESPEC §22, "detectar conflictos; no sobrescribir silenciosamente un estado remoto valido").
            throw new ConflictoNegocioException(
                    "La jornada ya tiene una recepcion registrada (creada concurrentemente)");
        }

        entityManager.refresh(recepcion);
        recepcion.setEstado(calcularEstado(recepcion.getLitrosCampo(), recepcion.getLitrosPlanta(), recepcion.getDiferenciaPct()));

        // Fase 1 del refactor de Produccion: misma entrada de leche que registrarYConciliar(), misma
        // transaccion -- una recepcion de jornada alimenta la existencia de leche exactamente igual que una
        // conciliacion manual, litrosPlanta es autoritativo en ambos caminos por igual.
        inventarioLecheService.registrarEntradaRecepcion(recepcion);

        // Confirmacion + transicion a RECIBIDA en la MISMA transaccion (ESPEC §14, regla obligatoria) --
        // ambas escrituras comparten la transaccion fisica de este metodo (@Transactional de clase), si
        // cualquier paso posterior fallara, ambas se revierten juntas.
        jornada.setEstado(EstadoJornada.RECIBIDA);
        jornadaRepository.save(jornada);

        return toResponse(recepcion);
    }

    // mejora #7: litros_campo = 0 AND litros_planta > 0 -> ALERTA forzado,
    // sin importar lo que reporte diferencia_pct (que en ese caso vale 0).
    private EstadoConciliacion calcularEstado(BigDecimal litrosCampo, BigDecimal litrosPlanta, BigDecimal diferenciaPct) {
        boolean casoBordeCampoCero = litrosCampo.compareTo(BigDecimal.ZERO) == 0
                && litrosPlanta.compareTo(BigDecimal.ZERO) > 0;
        if (casoBordeCampoCero) {
            return EstadoConciliacion.ALERTA;
        }

        double umbral = Double.parseDouble(configuracionSistemaService.valorDe("UMBRAL_CONCILIACION_PCT"));
        double diferenciaAbs = diferenciaPct == null ? 0.0 : Math.abs(diferenciaPct.doubleValue());
        return diferenciaAbs > umbral ? EstadoConciliacion.ALERTA : EstadoConciliacion.OK;
    }

    // Auditoria CRUD (prioridad ALTA): el modulo no tenia ningun listado --
    // RECEPCION no podia revisar el historial de conciliaciones desde el
    // frontend. unidadId es opcional para filtrar por unidad.
    // Fase 3, decision de negocio 3: registrosVinculados es @ManyToMany LAZY y
    // toResponse() la recorre -- mismo patron de bug ya corregido en
    // PagoService/LoteProduccionService/ComunicadoService (open-in-view=false).
    // Bloque 5 (confirmado): agrega filtro opcional por fecha, combinable con
    // el de unidad ya existente. Sin parametros, mismo resultado que antes.
    @Transactional(readOnly = true)
    public Page<RecepcionPlantaResponse> listar(UUID unidadId, LocalDate fecha, Pageable pageable) {
        return repository.buscarConFiltros(unidadId, fecha, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public RecepcionPlantaResponse obtener(UUID id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Recepcion de planta no encontrada: " + id)));
    }

    private RecepcionPlantaResponse toResponse(RecepcionPlanta r) {
        // Segunda auditoria (flujos end-to-end §5/§14-D): dato de referencia
        // para que el frontend contraste litrosCampo (manual) contra lo que
        // realmente se registro en RegistroAcopio para esa unidad/fecha --
        // sin sobreescribir litrosCampo ni cambiar la regla de 'estado'.
        BigDecimal litrosRegistradosAcopio = registroAcopioRepository.sumLitrosPorUnidadYFecha(
                r.getUnidad().getId(), r.getFecha().atStartOfDay(), r.getFecha().plusDays(1).atStartOfDay());

        return new RecepcionPlantaResponse(
                r.getId(), r.getFecha(), r.getTurno(), r.getUnidad().getId(),
                r.getLitrosCampo(), r.getLitrosPlanta(), r.getDiferenciaPct(), r.getEstado().name(),
                litrosRegistradosAcopio,
                r.getRegistrosVinculados().stream().map(RegistroAcopio::getId).toList(),
                r.getJornada() != null ? r.getJornada().getId() : null,
                r.getHoraLlegada(),
                r.getLitrosPlanta().subtract(r.getLitrosCampo()),
                r.getObservacion());
    }
}
