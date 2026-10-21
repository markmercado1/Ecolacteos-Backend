package com.planta.acopio.service;

import com.planta.acopio.dto.PrediccionZonaResponse;
import com.planta.acopio.entity.PrediccionZona;
import com.planta.acopio.entity.Zona;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.PrecioLitroRepository;
import com.planta.acopio.repository.PrediccionZonaRepository;
import com.planta.acopio.repository.RegistroAcopioRepository;
import com.planta.acopio.repository.ZonaRepository;
import com.planta.acopio.service.util.EstadisticaSimple;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

// RF-INN-04 (CORE_DESIGN.md §11, flujo 8 / documento fuente §2.10.4, §3.16).
// litros_estimados_semana = media_movil_4_semanas * factor_estacional_mes.
// Se ejecuta semanalmente por zona y alimenta 3 salidas: recomendacion de
// mezcla de produccion, flujo de caja proyectado, alerta de capacidad.
@Service
public class ForecastingService {

    private final RegistroAcopioRepository registroAcopioRepository;
    private final PrediccionZonaRepository prediccionZonaRepository;
    private final ConfiguracionSistemaService configuracionSistemaService;
    private final PrecioLitroRepository precioLitroRepository;
    private final ZonaRepository zonaRepository;

    public ForecastingService(RegistroAcopioRepository registroAcopioRepository,
                               PrediccionZonaRepository prediccionZonaRepository,
                               ConfiguracionSistemaService configuracionSistemaService,
                               PrecioLitroRepository precioLitroRepository,
                               ZonaRepository zonaRepository) {
        this.registroAcopioRepository = registroAcopioRepository;
        this.prediccionZonaRepository = prediccionZonaRepository;
        this.configuracionSistemaService = configuracionSistemaService;
        this.precioLitroRepository = precioLitroRepository;
        this.zonaRepository = zonaRepository;
    }

    @Transactional
    public PrediccionZonaResponse proyectarSemana(UUID zonaId) {
        Zona zona = zonaRepository.findById(zonaId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Zona no encontrada: " + zonaId));

        LocalDate inicioSemanaSiguiente = proximoLunes();

        // Antes de proyectar la semana nueva, se completa litros_reales de la
        // semana YA CERRADA (si existe una prediccion pendiente de cerrar).
        cerrarSemanaAnterior(zonaId, inicioSemanaSiguiente);

        LocalDateTime ahora = LocalDateTime.now();
        BigDecimal sumaUltimas4Semanas = registroAcopioRepository
                .findLitrosPorZonaYRango(zonaId, ahora.minusWeeks(4), ahora)
                .stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        double mediaMovil4Semanas = sumaUltimas4Semanas.doubleValue() / 4.0;

        int mes = inicioSemanaSiguiente.getMonthValue();
        double promedioMes = registroAcopioRepository.promedioLitrosPorZonaYMes(zonaId, mes).doubleValue();
        double promedioGeneral = registroAcopioRepository.promedioHistoricoGeneralPorZona(zonaId).doubleValue();
        // factor = 1.0 (neutro) si aun no hay suficiente historico para evitar
        // division por cero o proyecciones absurdas con datos insuficientes.
        double factorEstacional = promedioGeneral == 0 ? 1.0 : (promedioMes / promedioGeneral);

        double litrosEstimados = mediaMovil4Semanas * factorEstacional;

        PrediccionZona prediccion = prediccionZonaRepository.findByZonaIdAndSemanaInicio(zonaId, inicioSemanaSiguiente)
                .orElse(new PrediccionZona());
        prediccion.setZona(zona);
        prediccion.setSemanaInicio(inicioSemanaSiguiente);
        prediccion.setLitrosEstimados(EstadisticaSimple.redondear(litrosEstimados, 2));
        prediccionZonaRepository.save(prediccion);

        // (a) recomendacion de mezcla de produccion
        String recomendacion;
        if (promedioGeneral > 0 && litrosEstimados > promedioGeneral * 1.10) {
            recomendacion = "Aumentar proporcion de queso MADURACION (excedente proyectado)";
        } else if (promedioGeneral > 0 && litrosEstimados < promedioGeneral * 0.90) {
            recomendacion = "Priorizar queso RAPIDO (escasez proyectada)";
        } else {
            recomendacion = "Mantener mezcla actual";
        }

        // (b) flujo de caja proyectado
        BigDecimal precioVigente = precioLitroRepository.findVigenteHoy()
                .map(p -> p.getPrecio())
                .orElse(BigDecimal.ZERO);
        BigDecimal flujoCajaProyectado = BigDecimal.valueOf(litrosEstimados).multiply(precioVigente)
                .setScale(2, RoundingMode.HALF_UP);

        // (c) alerta de capacidad
        double capacidadDiaria = Double.parseDouble(
                configuracionSistemaService.valorDe("CAPACIDAD_PROCESAMIENTO_DIARIA_LITROS"));
        boolean alertaCapacidad = (litrosEstimados / 7.0) > capacidadDiaria;

        return new PrediccionZonaResponse(zonaId, inicioSemanaSiguiente,
                EstadisticaSimple.redondear(litrosEstimados, 2), prediccion.getLitrosReales(),
                recomendacion, flujoCajaProyectado, alertaCapacidad);
    }

    // Solo lectura -- para el endpoint GET y para PanelEconomicoService (que
    // NO debe regenerar una prediccion nueva en cada consulta del panel).
    public PrediccionZonaResponse obtenerUltima(UUID zonaId) {
        PrediccionZona p = prediccionZonaRepository.findTopByZonaIdOrderBySemanaInicioDesc(zonaId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Sin prediccion calculada aun para la zona: " + zonaId));

        double capacidadDiaria = Double.parseDouble(
                configuracionSistemaService.valorDe("CAPACIDAD_PROCESAMIENTO_DIARIA_LITROS"));
        boolean alertaCapacidad = (p.getLitrosEstimados().doubleValue() / 7.0) > capacidadDiaria;

        BigDecimal precioVigente = precioLitroRepository.findVigenteHoy().map(pl -> pl.getPrecio()).orElse(BigDecimal.ZERO);
        BigDecimal flujoCaja = p.getLitrosEstimados().multiply(precioVigente).setScale(2, RoundingMode.HALF_UP);

        return new PrediccionZonaResponse(zonaId, p.getSemanaInicio(), p.getLitrosEstimados(),
                p.getLitrosReales(), "Ver recomendacion en la ultima proyeccion semanal", flujoCaja, alertaCapacidad);
    }

    private void cerrarSemanaAnterior(UUID zonaId, LocalDate inicioSemanaSiguiente) {
        LocalDate inicioSemanaAnterior = inicioSemanaSiguiente.minusWeeks(1);
        prediccionZonaRepository.findByZonaIdAndSemanaInicio(zonaId, inicioSemanaAnterior)
                .ifPresent(anterior -> {
                    BigDecimal reales = registroAcopioRepository.sumLitrosPorZonaYSemana(
                            zonaId, inicioSemanaAnterior.atStartOfDay(), inicioSemanaSiguiente.atStartOfDay());
                    anterior.setLitrosReales(reales);
                });
    }

    private LocalDate proximoLunes() {
        return LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    }
}
