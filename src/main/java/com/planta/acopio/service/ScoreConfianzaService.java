package com.planta.acopio.service;

import com.planta.acopio.dto.ScoreConfianzaResponse;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.ScoreConfianzaProveedor;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.AlertaAnomaliaRepository;
import com.planta.acopio.repository.AnalisisCalidadRepository;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.RegistroAcopioRepository;
import com.planta.acopio.repository.ScoreConfianzaProveedorRepository;
import com.planta.acopio.service.util.EstadisticaSimple;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// RF-INN-01 (CORE_DESIGN.md §11, flujo 6 / documento fuente §2.10.1, §3.14).
// Formula (0-100): 0.5*calidad + 0.3*regularidad + 0.2*anomalias, sobre los
// ultimos 90 dias. Se recalcula semanalmente por InnovacionScheduler; tambien
// se puede invocar puntualmente para un proveedor (ej. tras alta manual).
@Service
public class ScoreConfianzaService {

    private final RegistroAcopioRepository registroAcopioRepository;
    private final AnalisisCalidadRepository analisisCalidadRepository;
    private final AlertaAnomaliaRepository alertaAnomaliaRepository;
    private final ScoreConfianzaProveedorRepository scoreRepository;
    private final ProveedorRepository proveedorRepository;

    public ScoreConfianzaService(RegistroAcopioRepository registroAcopioRepository,
                                  AnalisisCalidadRepository analisisCalidadRepository,
                                  AlertaAnomaliaRepository alertaAnomaliaRepository,
                                  ScoreConfianzaProveedorRepository scoreRepository,
                                  ProveedorRepository proveedorRepository) {
        this.registroAcopioRepository = registroAcopioRepository;
        this.analisisCalidadRepository = analisisCalidadRepository;
        this.alertaAnomaliaRepository = alertaAnomaliaRepository;
        this.scoreRepository = scoreRepository;
        this.proveedorRepository = proveedorRepository;
    }

    @Transactional
    public void recalcularScoreSemanal(UUID proveedorId, LocalDate periodo) {
        Proveedor proveedor = proveedorRepository.findById(proveedorId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + proveedorId));

        LocalDateTime desde = periodo.minusDays(90).atStartOfDay();

        long total = analisisCalidadRepository.contarTotalPorProveedorDesde(proveedorId, desde);
        long aprobados = analisisCalidadRepository.contarAprobadosPorProveedorDesde(proveedorId, desde);
        double pctAprobados = total == 0 ? 100.0 : (aprobados * 100.0 / total); // sin historico: no penaliza

        var litros = registroAcopioRepository.findLitrosPorProveedorDesde(proveedorId, desde);
        double coefVariacion = EstadisticaSimple.coeficienteVariacion(litros);

        long alertas90d = alertaAnomaliaRepository.contarPorProveedorDesde(proveedorId, desde);

        double componenteCalidad = pctAprobados;
        double componenteRegularidad = Math.max(0, 100 - (coefVariacion * 100));
        double componenteAnomalias = Math.max(0, 100 - (alertas90d * 10));

        double score = 0.5 * componenteCalidad + 0.3 * componenteRegularidad + 0.2 * componenteAnomalias;

        ScoreConfianzaProveedor entidad = scoreRepository.findByProveedorIdAndPeriodo(proveedorId, periodo)
                .orElse(new ScoreConfianzaProveedor());
        entidad.setProveedor(proveedor);
        entidad.setPeriodo(periodo);
        entidad.setScore(EstadisticaSimple.redondear(score, 2));
        entidad.setComponenteCalidad(EstadisticaSimple.redondear(componenteCalidad, 2));
        entidad.setComponenteRegularidad(EstadisticaSimple.redondear(componenteRegularidad, 2));
        entidad.setComponenteAnomalias(EstadisticaSimple.redondear(componenteAnomalias, 2));
        scoreRepository.save(entidad);
    }

    // Recalcula el score de todos los proveedores -- invocado por el scheduler semanal.
    @Transactional
    public void recalcularScoreSemanalTodos(LocalDate periodo) {
        for (Proveedor p : proveedorRepository.findAll()) {
            if (Boolean.TRUE.equals(p.getActivo())) {
                recalcularScoreSemanal(p.getId(), periodo);
            }
        }
    }

    public ScoreConfianzaResponse obtenerVigente(UUID proveedorId) {
        var score = scoreRepository.findTopByProveedorIdOrderByPeriodoDesc(proveedorId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Sin score calculado aun para el proveedor: " + proveedorId));
        return new ScoreConfianzaResponse(proveedorId, score.getPeriodo(), score.getScore(),
                score.getComponenteCalidad(), score.getComponenteRegularidad(), score.getComponenteAnomalias());
    }
}
