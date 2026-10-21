package com.planta.acopio.service;

import com.planta.acopio.dto.AlertaAnomaliaResponse;
import com.planta.acopio.dto.PrediccionProveedorResponse;
import com.planta.acopio.entity.*;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.*;
import com.planta.acopio.service.util.EstadisticaSimple;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// RF-INN-03 (CORE_DESIGN.md §11, flujos 7 y 6 / documento fuente §2.10.3, §3.15).
// Deteccion (reactiva): z-score al sincronizar. Prediccion (proactiva): rango
// esperado por proveedor, recalculado semanalmente.
@Service
public class AnomaliaService {

    private final RegistroAcopioRepository registroAcopioRepository;
    private final AlertaAnomaliaRepository alertaAnomaliaRepository;
    private final ConfiguracionSistemaService configuracionSistemaService;
    private final PrediccionProveedorRepository prediccionProveedorRepository;
    private final AnalisisCalidadRepository analisisCalidadRepository;
    private final ProveedorRepository proveedorRepository;

    public AnomaliaService(RegistroAcopioRepository registroAcopioRepository,
                            AlertaAnomaliaRepository alertaAnomaliaRepository,
                            ConfiguracionSistemaService configuracionSistemaService,
                            PrediccionProveedorRepository prediccionProveedorRepository,
                            AnalisisCalidadRepository analisisCalidadRepository,
                            ProveedorRepository proveedorRepository) {
        this.registroAcopioRepository = registroAcopioRepository;
        this.alertaAnomaliaRepository = alertaAnomaliaRepository;
        this.configuracionSistemaService = configuracionSistemaService;
        this.prediccionProveedorRepository = prediccionProveedorRepository;
        this.analisisCalidadRepository = analisisCalidadRepository;
        this.proveedorRepository = proveedorRepository;
    }

    // Deteccion reactiva: se invoca desde RegistroAcopioService justo despues
    // de persistir un registro nuevo (misma transaccion del sync).
    @Transactional
    public void evaluarNuevoRegistro(RegistroAcopio registro) {
        UUID proveedorId = registro.getProveedor().getId();
        LocalDateTime desde = LocalDateTime.now().minusDays(90);

        // Se excluye el propio registro recien creado del calculo de media/desviacion
        // (si no, un solo registro atipico se "normaliza a si mismo").
        List<BigDecimal> historico = registroAcopioRepository
                .findLitrosPorProveedorDesdeExcluyendo(proveedorId, desde, registro.getId());

        if (historico.size() < 3) {
            return; // histórico insuficiente, no se evalua aun (CORE_DESIGN.md §19, riesgo documentado)
        }

        double media = EstadisticaSimple.media(historico);
        double desviacion = EstadisticaSimple.desviacionEstandar(historico, media);
        if (desviacion == 0) {
            return;
        }

        double z = (registro.getLitros().doubleValue() - media) / desviacion;
        double umbral = Double.parseDouble(configuracionSistemaService.valorDe("UMBRAL_ANOMALIA_Z_SCORE"));

        if (Math.abs(z) > umbral) {
            AlertaAnomalia alerta = new AlertaAnomalia();
            alerta.setRegistroAcopio(registro);
            alerta.setTipo(TipoAlerta.VOLUMEN_ATIPICO);
            alerta.setZScore(EstadisticaSimple.redondear(z, 3));
            alerta.setSeveridad(Math.abs(z) > umbral * 1.5 ? Severidad.ALTA : Severidad.MEDIA);
            alertaAnomaliaRepository.save(alerta);
        }

        // RIESGO_ADULTERACION: heuristica simplificada y documentada. El
        // documento fuente (§2.10.3) condiciona esto ademas a "temporada seca",
        // un concepto que NO esta modelado en el SQL aprobado (no hay tabla de
        // estacionalidad ni columna de temporada) -- por regla fundamental de
        // Fase 1 ("no inventar"), se implementa solo la parte verificable con
        // datos reales: agua_anadida=true reciente para ese proveedor.
        boolean aguaAnadidaReciente = analisisCalidadRepository
                .existeAguaAnadidaRecienteParaProveedor(proveedorId, LocalDateTime.now().minusDays(30));
        if (aguaAnadidaReciente) {
            AlertaAnomalia alertaAdulteracion = new AlertaAnomalia();
            alertaAdulteracion.setRegistroAcopio(registro);
            alertaAdulteracion.setTipo(TipoAlerta.RIESGO_ADULTERACION);
            alertaAdulteracion.setSeveridad(Severidad.ALTA);
            alertaAnomaliaRepository.save(alertaAdulteracion);
        }
    }

    // Prediccion proactiva: media movil de las ultimas 6 entregas +/- 1 desviacion.
    @Transactional
    public void recalcularPrediccionProveedor(UUID proveedorId) {
        List<RegistroAcopio> ultimas6 = registroAcopioRepository.findTop6ByProveedorIdOrderByFechaHoraDesc(proveedorId);
        if (ultimas6.size() < 3) {
            return; // muy poco historico para proyectar
        }

        List<BigDecimal> litros = ultimas6.stream().map(RegistroAcopio::getLitros).toList();
        double media = EstadisticaSimple.media(litros);
        double desviacion = EstadisticaSimple.desviacionEstandar(litros, media);

        Proveedor proveedor = proveedorRepository.findById(proveedorId)
                .orElseThrow();

        PrediccionProveedor prediccion = new PrediccionProveedor();
        prediccion.setProveedor(proveedor);
        prediccion.setFechaPrevista(LocalDate.now().plusDays(7));
        prediccion.setLitrosEstimadosMin(EstadisticaSimple.redondear(Math.max(0, media - desviacion), 2));
        prediccion.setLitrosEstimadosMax(EstadisticaSimple.redondear(media + desviacion, 2));
        prediccionProveedorRepository.save(prediccion);
        // Este rango viaja en /api/sync/cambios y se muestra ANTES de registrar
        // (proactivo, no solo reactivo) -- documento fuente §2.10.3.
    }

    // Auditoria CRUD (prioridad BAJA): PrediccionProveedorRepository.findTopByProveedorIdOrderByGeneradoEnDesc
    // ya existia sin usarse. El rango completo ya viaja en /api/sync/cambios
    // para descarga masiva, pero faltaba una consulta puntual (mismo patron
    // que /api/innovacion/score/{proveedorId}) para el panel de un proveedor.
    public PrediccionProveedorResponse obtenerPrediccionVigente(UUID proveedorId) {
        PrediccionProveedor prediccion = prediccionProveedorRepository.findTopByProveedorIdOrderByGeneradoEnDesc(proveedorId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Sin prediccion calculada aun para el proveedor: " + proveedorId));
        return new PrediccionProveedorResponse(proveedorId, prediccion.getFechaPrevista(),
                prediccion.getLitrosEstimadosMin(), prediccion.getLitrosEstimadosMax());
    }

    // Segunda auditoria (flujos end-to-end §14-F): esta consulta y su mapeo a
    // DTO vivian antes directamente en InnovacionController (inyectando
    // AlertaAnomaliaRepository en el Controller) -- unica ruptura del patron
    // Controller -> Service -> Repository de todo el API. Se mueve aqui, que
    // es el service dueño de AlertaAnomalia.
    public List<AlertaAnomaliaResponse> listarAlertasPorZona(UUID zonaId, int diasAtras) {
        return alertaAnomaliaRepository.findPorZonaDesde(zonaId, LocalDateTime.now().minusDays(diasAtras)).stream()
                .map(a -> new AlertaAnomaliaResponse(
                        a.getId(), a.getRegistroAcopio().getId(), a.getRegistroAcopio().getProveedor().getId(),
                        a.getRegistroAcopio().getProveedor().getNombre(), a.getTipo().name(), a.getZScore(),
                        a.getSeveridad().name(), a.getCreadoEn()))
                .toList();
    }

    @Transactional
    public void recalcularPrediccionTodos() {
        for (Proveedor p : proveedorRepository.findAll()) {
            if (Boolean.TRUE.equals(p.getActivo())) {
                recalcularPrediccionProveedor(p.getId());
            }
        }
    }
}
