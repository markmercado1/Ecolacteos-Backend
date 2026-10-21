package com.planta.acopio.scheduler;

import com.planta.acopio.entity.Zona;
import com.planta.acopio.repository.ZonaRepository;
import com.planta.acopio.service.AnomaliaService;
import com.planta.acopio.service.ForecastingService;
import com.planta.acopio.service.ScoreConfianzaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

// CORE_DESIGN.md §9 / §11 (flujos 6-8): jobs programados del modulo de
// innovacion. Separados de service/ porque tienen un ciclo de disparo propio
// (@Scheduled), no invocable por HTTP directo de un usuario -- mezclar esto
// dentro de los Services de negocio confundiria ambos ciclos de vida.
//
// Frecuencia: semanal, lunes 03:00 (hora de baja actividad operativa), acorde
// a "score de confianza... recalculada semanalmente" y "ForecastingService
// corre semanalmente" del documento fuente (§2.10.1 / §2.10.4).
@Component
public class InnovacionScheduler {

    private static final Logger log = LoggerFactory.getLogger(InnovacionScheduler.class);

    private final ScoreConfianzaService scoreConfianzaService;
    private final AnomaliaService anomaliaService;
    private final ForecastingService forecastingService;
    private final ZonaRepository zonaRepository;

    public InnovacionScheduler(ScoreConfianzaService scoreConfianzaService, AnomaliaService anomaliaService,
                                ForecastingService forecastingService, ZonaRepository zonaRepository) {
        this.scoreConfianzaService = scoreConfianzaService;
        this.anomaliaService = anomaliaService;
        this.forecastingService = forecastingService;
        this.zonaRepository = zonaRepository;
    }

    // cron: seg min hora dia-mes mes dia-semana -> lunes 03:00
    @Scheduled(cron = "0 0 3 * * MON")
    public void recalcularScoreConfianzaSemanal() {
        log.info("Job semanal: recalculando score de confianza de todos los proveedores activos (RF-INN-01)");
        scoreConfianzaService.recalcularScoreSemanalTodos(LocalDate.now());
    }

    @Scheduled(cron = "0 5 3 * * MON")
    public void recalcularPrediccionProveedorSemanal() {
        log.info("Job semanal: recalculando prediccion de proxima entrega por proveedor (RF-INN-03, parte proactiva)");
        anomaliaService.recalcularPrediccionTodos();
    }

    @Scheduled(cron = "0 10 3 * * MON")
    public void recalcularForecastingZonaSemanal() {
        log.info("Job semanal: recalculando forecasting estacional por zona (RF-INN-04)");
        for (Zona zona : zonaRepository.findByActivaTrue()) {
            try {
                forecastingService.proyectarSemana(zona.getId());
            } catch (Exception ex) {
                // Una zona sin historico suficiente no debe tumbar el job para
                // las demas zonas (CORE_DESIGN.md §19, riesgo de histórico corto).
                log.warn("No se pudo proyectar forecasting para la zona {}: {}", zona.getId(), ex.getMessage());
            }
        }
    }
}
