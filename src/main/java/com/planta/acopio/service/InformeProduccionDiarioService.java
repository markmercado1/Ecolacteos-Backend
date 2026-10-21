package com.planta.acopio.service;

import com.planta.acopio.dto.CrearInformeProduccionRequest;
import com.planta.acopio.dto.EditarInformeProduccionRequest;
import com.planta.acopio.dto.InformeProduccionDiarioResponse;
import com.planta.acopio.dto.InsumoUsadoRequest;
import com.planta.acopio.dto.InsumoUsadoResponse;
import com.planta.acopio.entity.InformeProduccionDiario;
import com.planta.acopio.entity.InformeProduccionInsumo;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.InformeProduccionDiarioRepository;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Modulo independiente de LoteProduccion (decision explicita): virtualiza el cuadro en papel "INFORME DE
// PRODUCCION DE QUESOS DIARIO", un resumen operativo por dia (no trazabilidad de un lote puntual). A
// diferencia de RegistroAcopio, es editable el mismo dia (decision explicita): el papel se corrige a mano.
@Service
public class InformeProduccionDiarioService {

    private final InformeProduccionDiarioRepository repository;
    private final InsumoRepository insumoRepository;
    private final UsuarioRepository usuarioRepository;

    public InformeProduccionDiarioService(InformeProduccionDiarioRepository repository,
                                           InsumoRepository insumoRepository,
                                           UsuarioRepository usuarioRepository) {
        this.repository = repository;
        this.insumoRepository = insumoRepository;
        this.usuarioRepository = usuarioRepository;
    }

    // Idempotente por uuidCliente (decision aprobada, mismo criterio que RegistroAcopio/Jornada/
    // LoteProduccion): un reintento de red tras un POST incierto nunca debe duplicar el informe del dia.
    @Transactional
    public InformeProduccionDiarioResponse crear(CrearInformeProduccionRequest dto) {
        var existente = repository.findByUuidCliente(dto.uuidCliente());
        if (existente.isPresent()) {
            return toResponse(existente.get());
        }

        // Regla de negocio explicita (confirmada con el usuario): un informe solo se puede CREAR para HOY,
        // nunca para una fecha pasada ni futura. No afecta las lecturas (obtener/obtenerPorFecha/buscar),
        // que si pueden consultar historico sin restriccion.
        LocalDate hoy = LocalDate.now();
        if (!dto.fecha().isEqual(hoy)) {
            throw new ValidacionNegocioException("Solo se puede crear un informe de produccion para la fecha de hoy ("
                    + hoy + "); fecha recibida: " + dto.fecha());
        }

        // UNIQUE(fecha) real en la migracion; esta comprobacion da un mensaje de negocio claro (409) en vez
        // de dejar que la excepcion de integridad de la BD llegue cruda al cliente en el caso normal (sin
        // carrera). El catch alrededor de save() de abajo es la segunda barrera para la carrera real: dos
        // requests concurrentes con distinto uuidCliente pueden pasar este chequeo al mismo tiempo.
        if (repository.findByFecha(dto.fecha()).isPresent()) {
            throw new ConflictoNegocioException("Ya existe un informe de produccion para la fecha " + dto.fecha()
                    + ". Edita el informe existente en vez de crear uno nuevo.");
        }

        InformeProduccionDiario informe = new InformeProduccionDiario();
        informe.setUuidCliente(dto.uuidCliente());
        informe.setFecha(dto.fecha());
        informe.setQuesosDiarios(dto.quesosDiarios());
        informe.setQuesosReparados(dto.quesosReparados() != null ? dto.quesosReparados() : 0);
        informe.setQuesosDeRecorte(dto.quesosDeRecorte() != null ? dto.quesosDeRecorte() : 0);
        informe.setRecorteEnKilos(dto.recorteEnKilos());
        informe.setResponsable(usuarioActual());
        informe.setInsumos(construirInsumosUsados(informe, dto.insumos()));

        try {
            return toResponse(repository.save(informe));
        } catch (DataIntegrityViolationException ex) {
            // Carrera real: dos requests concurrentes con distinto uuidCliente pasaron el findByFecha() de
            // arriba al mismo tiempo -- el UNIQUE(fecha) de la migracion frena el segundo INSERT. Mismo
            // criterio que JornadaService.crear() para su propio indice unico concurrente.
            throw new ConflictoNegocioException("Ya existe un informe de produccion para la fecha " + dto.fecha()
                    + " (creado concurrentemente).");
        }
    }

    // Edicion del mismo dia (decision explicita, ver comentario de clase): reemplaza por completo
    // quesos/recorte/insumos, igual que la correccion a mano en el papel. La fecha no se reasigna aqui.
    @Transactional
    public InformeProduccionDiarioResponse editar(UUID id, EditarInformeProduccionRequest dto) {
        InformeProduccionDiario informe = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Informe de produccion no encontrado: " + id));

        // Regla de negocio explicita: solo se edita el informe del dia de hoy -- uno de una fecha pasada (o,
        // por datos viejos/manuales, futura) no se puede tocar.
        LocalDate hoy = LocalDate.now();
        if (!informe.getFecha().isEqual(hoy)) {
            throw new ValidacionNegocioException("Solo se puede editar el informe de produccion del dia de hoy ("
                    + hoy + "); el informe " + id + " es del " + informe.getFecha());
        }

        informe.setQuesosDiarios(dto.quesosDiarios());
        informe.setQuesosReparados(dto.quesosReparados() != null ? dto.quesosReparados() : 0);
        informe.setQuesosDeRecorte(dto.quesosDeRecorte() != null ? dto.quesosDeRecorte() : 0);
        informe.setRecorteEnKilos(dto.recorteEnKilos());

        reemplazarInsumosUsados(informe, dto.insumos());

        informe.setActualizadoPor(usuarioActual());
        informe.setActualizadoEn(LocalDateTime.now());

        return toResponse(informe);
    }

    @Transactional(readOnly = true)
    public InformeProduccionDiarioResponse obtener(UUID id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Informe de produccion no encontrado: " + id)));
    }

    @Transactional(readOnly = true)
    public InformeProduccionDiarioResponse obtenerPorFecha(LocalDate fecha) {
        return toResponse(repository.findByFecha(fecha)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe un informe de produccion para la fecha " + fecha)));
    }

    @Transactional(readOnly = true)
    public Page<InformeProduccionDiarioResponse> buscar(LocalDate desde, LocalDate hasta, Pageable pageable) {
        return repository.buscarConFiltros(desde, hasta, pageable).map(this::toResponse);
    }

    // Solo para crear(): el informe es nuevo, su coleccion de insumos arranca vacia -- no hay nada que
    // reconciliar, cada fila es un alta simple.
    private Set<InformeProduccionInsumo> construirInsumosUsados(InformeProduccionDiario informe, List<InsumoUsadoRequest> filas) {
        Set<InformeProduccionInsumo> resultado = new HashSet<>();
        for (Map.Entry<UUID, BigDecimal> fila : validarSinInsumosDuplicados(filas).entrySet()) {
            Insumo insumo = insumoRepository.findById(fila.getKey())
                    .orElseThrow(() -> new RecursoNoEncontradoException("Insumo no encontrado: " + fila.getKey()));
            InformeProduccionInsumo detalle = new InformeProduccionInsumo();
            detalle.setInforme(informe);
            detalle.setInsumo(insumo);
            detalle.setCantidad(fila.getValue());
            resultado.add(detalle);
        }
        return resultado;
    }

    // Solo para editar(): a diferencia de crear(), aca la coleccion YA tiene filas persistidas. Reemplazarla
    // con clear()+addAll() (como se hacia antes) dispara un bug clasico de Hibernate: si la lista nueva
    // repite un insumoId que ya estaba, Hibernate agenda el INSERT del reemplazo ANTES que el DELETE del
    // original dentro del mismo flush -- violacion transitoria de UNIQUE(informe_id, insumo_id) ->
    // DataIntegrityViolationException sin capturar -> 500 (reportado en vivo: editar un informe con insumos
    // ya cargados). La solucion es reconciliar en el lugar: actualizar cantidad() de las filas que siguen
    // presentes (nunca se borran ni se recrean), eliminar (orphanRemoval) solo las que ya no estan, y crear
    // solo las genuinamente nuevas -- ningun insumoId pasa por un DELETE+INSERT simultaneo.
    private void reemplazarInsumosUsados(InformeProduccionDiario informe, List<InsumoUsadoRequest> filas) {
        Map<UUID, BigDecimal> deseados = new LinkedHashMap<>(validarSinInsumosDuplicados(filas));

        informe.getInsumos().removeIf(detalle -> {
            BigDecimal cantidad = deseados.remove(detalle.getInsumo().getId());
            if (cantidad == null) return true; // ya no esta en la lista nueva: se quita (orphanRemoval)
            detalle.setCantidad(cantidad); // sigue estando: se actualiza in-place, nunca se borra/recrea
            return false;
        });

        // Lo que queda en 'deseados' (tras remover arriba los ya existentes) son insumos realmente nuevos.
        for (Map.Entry<UUID, BigDecimal> fila : deseados.entrySet()) {
            Insumo insumo = insumoRepository.findById(fila.getKey())
                    .orElseThrow(() -> new RecursoNoEncontradoException("Insumo no encontrado: " + fila.getKey()));
            InformeProduccionInsumo detalle = new InformeProduccionInsumo();
            detalle.setInforme(informe);
            detalle.setInsumo(insumo);
            detalle.setCantidad(fila.getValue());
            informe.getInsumos().add(detalle);
        }
    }

    // Validacion explicita por insumoId (decision aprobada): mas simple y segura que agregar equals/hashCode
    // a InformeProduccionInsumo (una entidad JPA -- comparar instancias transitorias/proxies por esos
    // metodos es una fuente clasica de bugs sutiles). El UNIQUE(informe_id, insumo_id) de la migracion sigue
    // como segunda barrera, pero con esto nunca deberia llegar a dispararse desde la API. Devuelve un mapa
    // (preserva el orden de llegada) para que crear()/editar() no tengan que volver a recorrer la lista.
    private Map<UUID, BigDecimal> validarSinInsumosDuplicados(List<InsumoUsadoRequest> filas) {
        if (filas == null || filas.isEmpty()) return Map.of();
        Map<UUID, BigDecimal> porInsumoId = new LinkedHashMap<>();
        for (InsumoUsadoRequest fila : filas) {
            if (porInsumoId.put(fila.insumoId(), fila.cantidad()) != null) {
                throw new ValidacionNegocioException("El insumo " + fila.insumoId() + " esta repetido en el mismo informe");
            }
        }
        return porInsumoId;
    }

    private Usuario usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth.getName();
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));
    }

    private InformeProduccionDiarioResponse toResponse(InformeProduccionDiario informe) {
        List<InsumoUsadoResponse> insumos = informe.getInsumos().stream()
                .map(d -> new InsumoUsadoResponse(d.getInsumo().getId(), d.getInsumo().getNombre(),
                        d.getInsumo().getUnidadMedida(), d.getCantidad()))
                .toList();
        return new InformeProduccionDiarioResponse(
                informe.getId(), informe.getUuidCliente(), informe.getFecha(),
                informe.getQuesosDiarios(), informe.getQuesosReparados(), informe.getQuesosDeRecorte(),
                informe.getQuesosDiarios() + informe.getQuesosReparados() + informe.getQuesosDeRecorte(),
                informe.getRecorteEnKilos(), informe.getResponsable().getNombre(), informe.getCreadoEn(),
                informe.getActualizadoPor() != null ? informe.getActualizadoPor().getNombre() : null,
                informe.getActualizadoEn(), insumos);
    }
}
