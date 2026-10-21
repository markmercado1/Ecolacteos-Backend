package com.planta.acopio.service;

import com.planta.acopio.dto.PanelEconomicoResponse;
import com.planta.acopio.repository.AlertaAnomaliaRepository;
import com.planta.acopio.repository.AnalisisCalidadRepository;
import com.planta.acopio.repository.PrecioLitroRepository;
import com.planta.acopio.repository.ScoreConfianzaProveedorRepository;
import com.planta.acopio.repository.VentaRepository;
import com.planta.acopio.repository.ZonaRepository;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.service.util.RegresionLineal;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// RF-INN-05 (CORE_DESIGN.md §11, flujo 9 / documento fuente §2.10.5, §3.17).
// Combina las salidas de los demas servicios de innovacion en KPIs accionables.
// Es de SOLO LECTURA: no genera una nueva PrediccionZona en cada consulta,
// lee la ultima ya calculada por el scheduler (ver ForecastingService.obtenerUltima).
@Service
public class PanelEconomicoService {

    private final AlertaAnomaliaRepository alertaAnomaliaRepository;
    private final PrecioLitroRepository precioLitroRepository;
    private final ForecastingService forecastingService;
    private final VentaRepository ventaRepository;
    private final ScoreConfianzaProveedorRepository scoreRepository;
    private final AnalisisCalidadRepository analisisCalidadRepository;
    private final ZonaRepository zonaRepository;

    public PanelEconomicoService(AlertaAnomaliaRepository alertaAnomaliaRepository,
                                  PrecioLitroRepository precioLitroRepository,
                                  ForecastingService forecastingService,
                                  VentaRepository ventaRepository,
                                  ScoreConfianzaProveedorRepository scoreRepository,
                                  AnalisisCalidadRepository analisisCalidadRepository,
                                  ZonaRepository zonaRepository) {
        this.alertaAnomaliaRepository = alertaAnomaliaRepository;
        this.precioLitroRepository = precioLitroRepository;
        this.forecastingService = forecastingService;
        this.ventaRepository = ventaRepository;
        this.scoreRepository = scoreRepository;
        this.analisisCalidadRepository = analisisCalidadRepository;
        this.zonaRepository = zonaRepository;
    }

    public PanelEconomicoResponse construirPanel(UUID zonaId) {
        if (!zonaRepository.existsById(zonaId)) {
            throw new RecursoNoEncontradoException("Zona no encontrada: " + zonaId);
        }

        // KPI 1: respaldo economico de la deteccion temprana (litros en alerta
        // de los ultimos 30 dias, valorizados al precio vigente).
        BigDecimal precioVigente = precioLitroRepository.findVigenteHoy().map(p -> p.getPrecio()).orElse(BigDecimal.ZERO);
        BigDecimal litrosEnAlerta = alertaAnomaliaRepository
                .sumarLitrosEnAlertaPorZonaDesde(zonaId, LocalDateTime.now().minusDays(30));
        BigDecimal litrosEnRiesgoValorizados = litrosEnAlerta.multiply(precioVigente).setScale(2, RoundingMode.HALF_UP);

        // KPI 2: flujo de caja proyectado (viene de RF-INN-04, solo lectura).
        var forecast = forecastingService.obtenerUltima(zonaId);

        // KPI 3: margen por canal. LIMITACION DOCUMENTADA (ver VentaRepository):
        // es un dato GLOBAL de planta, no filtrado por zona, porque venta no
        // tiene relacion con zona en el SQL aprobado.
        List<PanelEconomicoResponse.MargenPorCanal> margenPorCanal = ventaRepository.margenAgrupadoPorTipoCliente().stream()
                .map(row -> new PanelEconomicoResponse.MargenPorCanal(
                        row[0].toString(), (BigDecimal) row[1]))
                .toList();

        // KPI 4: simulador "que pasa si" -- regresion lineal simple entre score
        // promedio semanal de la zona y % historico de rechazo de calidad.
        Double pctRechazoSiScoreSube10 = simularEfectoScore(zonaId);

        return new PanelEconomicoResponse(zonaId, litrosEnRiesgoValorizados, forecast, margenPorCanal,
                pctRechazoSiScoreSube10 == null ? null : BigDecimal.valueOf(pctRechazoSiScoreSube10).setScale(2, RoundingMode.HALF_UP));
    }

    private Double simularEfectoScore(UUID zonaId) {
        List<Object[]> scorePorPeriodo = scoreRepository.promedioScorePorPeriodoZona(zonaId);
        List<Object[]> rechazoPorSemana = analisisCalidadRepository.pctRechazoPorSemanaZona(zonaId);

        if (scorePorPeriodo.isEmpty() || rechazoPorSemana.isEmpty()) {
            return null; // datos insuficientes -- se documenta como tal, no se inventa un numero
        }

        // Se alinean ambas series por fecha exacta (periodo del score == semana
        // truncada del analisis de calidad). Si no coinciden exactamente, ese
        // punto simplemente no entra a la regresion -- limitacion documentada
        // en CORE_DESIGN.md (los periodos de ScoreConfianzaProveedor y las
        // semanas de AnalisisCalidad se calculan de forma independiente).
        Map<java.sql.Date, Double> rechazoPorFecha = new HashMap<>();
        for (Object[] fila : rechazoPorSemana) {
            rechazoPorFecha.put((java.sql.Date) fila[0], ((Number) fila[1]).doubleValue());
        }

        java.util.List<Double> xs = new java.util.ArrayList<>();
        java.util.List<Double> ys = new java.util.ArrayList<>();
        Double ultimoScore = null;
        for (Object[] fila : scorePorPeriodo) {
            java.sql.Date periodo = (java.sql.Date) fila[0];
            double scorePromedio = ((Number) fila[1]).doubleValue();
            ultimoScore = scorePromedio;
            Double rechazo = rechazoPorFecha.get(periodo);
            if (rechazo != null) {
                xs.add(scorePromedio);
                ys.add(rechazo);
            }
        }

        if (xs.size() < 2 || ultimoScore == null) {
            return null;
        }

        RegresionLineal modelo = RegresionLineal.ajustar(
                xs.stream().mapToDouble(Double::doubleValue).toArray(),
                ys.stream().mapToDouble(Double::doubleValue).toArray());

        if (!modelo.valida) {
            return null;
        }

        double prediccion = modelo.predecir(ultimoScore + 10);
        return Math.max(0, Math.min(100, prediccion)); // acotado a un % valido
    }
}
