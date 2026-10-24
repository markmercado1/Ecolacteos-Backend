package com.planta.acopio.controller;

import com.planta.acopio.dto.ConfiguracionIgvDtos.AfectacionIgvRequest;
import com.planta.acopio.dto.ConfiguracionIgvDtos.AfectacionIgvResponse;
import com.planta.acopio.dto.ConfiguracionIgvDtos.EstadoIgvResponse;
import com.planta.acopio.dto.ConfiguracionIgvDtos.FilaAfectacionIgvResponse;
import com.planta.acopio.dto.ConfiguracionIgvDtos.TasaIgvRequest;
import com.planta.acopio.dto.ConfiguracionIgvDtos.TasaIgvResponse;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.service.ConfiguracionIgvService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Fase G (IGV configurable). Endpoints EXCLUSIVOS de la web (el movil no usa /api/admin/*), solo ADMIN. Preparar la configuracion no
// activa ningun calculo: eso depende de app.tributario.igv.calculo-habilitado (apagado por defecto hasta que confirme el contador).
@Tag(name = "IGV", description = "Configuración del IGV de las ventas: tasa con vigencia y afectación por presentación.")
@RestController
@RequestMapping("/api/admin/tributario")
public class ConfiguracionIgvController {

    private final ConfiguracionIgvService service;

    public ConfiguracionIgvController(ConfiguracionIgvService service) {
        this.service = service;
    }

    @Operation(summary = "Estado de la configuración del IGV", description = "Rol requerido: ADMIN. Indica si el cálculo está activo "
            + "(PENDIENTE mientras el contador no lo confirme: las ventas se registran sin desglose), la tasa vigente y programada, "
            + "cuántas presentaciones activas tienen cada afectación y avisos de lo que falta.")
    @GetMapping("/estado")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<EstadoIgvResponse> estado() {
        return ResponseEntity.ok(service.estado());
    }

    @Operation(summary = "Tasas de IGV", description = "Rol requerido: ADMIN. Serie con vigencia, la más reciente primero.")
    @GetMapping("/tasas-igv")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<TasaIgvResponse>> tasas() {
        return ResponseEntity.ok(service.listarTasas());
    }

    @Operation(summary = "Registrar tasa de IGV", description = "Rol requerido: ADMIN. Porcentaje mayor que 0 y menor que 100 (hasta 2 "
            + "decimales), con fecha de vigencia y motivo. No modifica tasas anteriores ni ventas ya registradas.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Tasa registrada"),
            @ApiResponse(responseCode = "400", description = "Tasa fuera de rango o sin motivo"),
            @ApiResponse(responseCode = "409", description = "Ya hay una tasa con esa fecha de vigencia")
    })
    @PostMapping("/tasas-igv")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TasaIgvResponse> registrarTasa(@RequestBody @Valid TasaIgvRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registrarTasa(dto));
    }

    @Operation(summary = "Afectación al IGV por presentación (paginado)", description = "Rol requerido: ADMIN. Presentaciones activas "
            + "con su afectación vigente hoy (SIN_DEFINIR si no tienen) y la próxima programada. `busqueda` (producto o presentación) opcional.")
    @GetMapping("/afectaciones")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PaginaResponse<FilaAfectacionIgvResponse>> afectaciones(
            @Parameter(description = "Producto o presentación (contiene, sin distinguir mayúsculas ni acentos)") @RequestParam(required = false) String busqueda,
            @Parameter(description = "Número de página, empieza en 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 100)") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.listarAfectaciones(busqueda, Paginacion.de(page, size)));
    }

    @Operation(summary = "Historial de afectación de una presentación", description = "Rol requerido: ADMIN. La más reciente primero.")
    @GetMapping("/presentaciones/{presentacionId}/afectaciones")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<AfectacionIgvResponse>> historial(@PathVariable UUID presentacionId) {
        return ResponseEntity.ok(service.historialAfectacion(presentacionId));
    }

    @Operation(summary = "Registrar afectación de una presentación", description = "Rol requerido: ADMIN. GRAVADO, EXONERADO, INAFECTO "
            + "o SIN_DEFINIR desde una fecha, con motivo. Abre una vigencia nueva (no edita las anteriores) y no cambia ventas ya registradas.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Afectación registrada"),
            @ApiResponse(responseCode = "400", description = "Afectación no válida o sin motivo"),
            @ApiResponse(responseCode = "404", description = "La presentación no existe"),
            @ApiResponse(responseCode = "409", description = "Ya hay una afectación con esa fecha para la presentación")
    })
    @PostMapping("/presentaciones/{presentacionId}/afectaciones")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AfectacionIgvResponse> registrarAfectacion(@PathVariable UUID presentacionId,
                                                                      @RequestBody @Valid AfectacionIgvRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registrarAfectacion(presentacionId, dto));
    }
}
