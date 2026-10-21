package com.planta.acopio.controller;

import com.planta.acopio.dto.AlertaAnomaliaResponse;
import com.planta.acopio.dto.PanelEconomicoResponse;
import com.planta.acopio.dto.PrediccionProveedorResponse;
import com.planta.acopio.dto.ScoreConfianzaResponse;
import com.planta.acopio.service.AnomaliaService;
import com.planta.acopio.service.PanelEconomicoService;
import com.planta.acopio.service.ScoreConfianzaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// CORE_DESIGN.md §12: modulo de innovacion, solo lectura desde la API (los
// datos se generan por los jobs de scheduler/InnovacionScheduler).
@Tag(name = "Innovación y Analítica", description = "Score de confianza, alertas de anomalía, predicción de entregas, panel económico. Todo de solo lectura: se calcula en jobs semanales (lunes 03:00) o al sincronizar un registro.")
@RestController
@RequestMapping("/api/innovacion")
public class InnovacionController {

    private final ScoreConfianzaService scoreConfianzaService;
    private final PanelEconomicoService panelEconomicoService;
    private final AnomaliaService anomaliaService;

    public InnovacionController(ScoreConfianzaService scoreConfianzaService,
                                 PanelEconomicoService panelEconomicoService,
                                 AnomaliaService anomaliaService) {
        this.scoreConfianzaService = scoreConfianzaService;
        this.panelEconomicoService = panelEconomicoService;
        this.anomaliaService = anomaliaService;
    }

    @Operation(
            summary = "Score de confianza vigente de un proveedor",
            description = "Rol requerido: ADMIN o CALIDAD. RF-INN-01: 0.5·calidad + 0.3·regularidad + "
                    + "0.2·anomalías, sobre los últimos 90 días, recalculado semanalmente. Si el proveedor "
                    + "no tiene histórico suficiente aún no hay score calculado: responde 404, nunca un "
                    + "número inventado."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Score vigente", content = @Content(schema = @Schema(implementation = ScoreConfianzaResponse.class))),
            @ApiResponse(responseCode = "404", description = "Sin score calculado aún para ese proveedor")
    })
    @GetMapping("/score/{proveedorId}")
    @PreAuthorize("hasAnyRole('ADMIN','CALIDAD')")
    public ResponseEntity<ScoreConfianzaResponse> score(@Parameter(description = "Id del proveedor") @PathVariable UUID proveedorId) {
        return ResponseEntity.ok(scoreConfianzaService.obtenerVigente(proveedorId));
    }

    // Auditoria CRUD (prioridad BAJA): consulta puntual de la prediccion
    // proactiva de un proveedor (RF-INN-03), mismo patron que /score/{proveedorId}.
    // El rango completo de todos los proveedores ya viaja por /api/sync/cambios;
    // esto es para consultarlo puntualmente desde un panel administrativo.
    @Operation(
            summary = "Predicción de próxima entrega de un proveedor",
            description = "Rol requerido: ADMIN o CALIDAD. RF-INN-03 (parte proactiva): rango estimado de "
                    + "litros para la próxima entrega, media móvil de las últimas 6 entregas ± 1 desviación. "
                    + "El mismo rango, para todos los proveedores, ya viaja en GET /api/sync/cambios; este "
                    + "endpoint es para consultarlo puntualmente."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Predicción vigente", content = @Content(schema = @Schema(implementation = PrediccionProveedorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Sin predicción calculada aún para ese proveedor")
    })
    @GetMapping("/prediccion/{proveedorId}")
    @PreAuthorize("hasAnyRole('ADMIN','CALIDAD')")
    public ResponseEntity<PrediccionProveedorResponse> prediccion(@Parameter(description = "Id del proveedor") @PathVariable UUID proveedorId) {
        return ResponseEntity.ok(anomaliaService.obtenerPrediccionVigente(proveedorId));
    }

    @Operation(
            summary = "Alertas de anomalía de una zona",
            description = "Rol requerido: ADMIN o CALIDAD. RF-INN-03 (parte reactiva): alertas de los últimos "
                    + "30 días, de tipo VOLUMEN_ATIPICO (z-score) o RIESGO_ADULTERACION (agua añadida reciente)."
    )
    @ApiResponse(responseCode = "200", description = "Alertas de la zona", content = @Content(array = @ArraySchema(schema = @Schema(implementation = AlertaAnomaliaResponse.class))))
    @GetMapping("/alertas")
    @PreAuthorize("hasAnyRole('ADMIN','CALIDAD')")
    public ResponseEntity<List<AlertaAnomaliaResponse>> alertasPorZona(@Parameter(description = "Id de la zona") @RequestParam UUID zonaId) {
        return ResponseEntity.ok(anomaliaService.listarAlertasPorZona(zonaId, 30));
    }

    // RF-INN-05
    @Operation(
            summary = "Panel económico de una zona",
            description = "Rol requerido: ADMIN. RF-INN-05: combina litros en riesgo valorizados, forecast de "
                    + "la semana, margen por canal de venta y un simulador \"qué pasa si el score sube 10 "
                    + "puntos\". De solo lectura: no recalcula el forecast, lee el último ya generado por el job semanal."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Panel económico", content = @Content(schema = @Schema(implementation = PanelEconomicoResponse.class))),
            @ApiResponse(responseCode = "404", description = "La zona no existe")
    })
    @GetMapping("/panel-economico")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PanelEconomicoResponse> panelEconomico(@Parameter(description = "Id de la zona") @RequestParam UUID zonaId) {
        return ResponseEntity.ok(panelEconomicoService.construirPanel(zonaId));
    }
}
