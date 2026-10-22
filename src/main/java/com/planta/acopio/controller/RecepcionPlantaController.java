package com.planta.acopio.controller;

import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.dto.RecepcionPlantaRequest;
import com.planta.acopio.dto.RecepcionPlantaResponse;
import com.planta.acopio.service.ConciliacionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@Tag(name = "Recepción y Conciliación", description = "Contraste de litros de campo vs. litros pesados en planta, por unidad/fecha/turno. Rol requerido: RECEPCION o ADMIN.")
@RestController
@RequestMapping("/api/recepcion-planta")
@PreAuthorize("hasAnyRole('RECEPCION','ADMIN')")
public class RecepcionPlantaController {

    private final ConciliacionService conciliacionService;

    public RecepcionPlantaController(ConciliacionService conciliacionService) {
        this.conciliacionService = conciliacionService;
    }

    // RF-REC-01/02/03: registra y concilia campo vs. planta en la misma operacion.
    @Operation(
            summary = "Registrar y conciliar una recepción",
            description = "RF-REC-01/02/03: registra y concilia en la misma operación. `estado` (OK/ALERTA) "
                    + "se calcula por un umbral configurable (`UMBRAL_CONCILIACION_PCT`), con un caso especial: "
                    + "si `litrosCampo=0` pero `litrosPlanta>0`, se fuerza ALERTA sin importar `diferenciaPct` "
                    + "(que en ese caso la columna generada de PostgreSQL reporta en 0 para evitar división por cero)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Recepción registrada y conciliada", content = @Content(schema = @Schema(implementation = RecepcionPlantaResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "No existe una unidad con ese unidadId"),
            @ApiResponse(responseCode = "409", description = "Ya existe una recepción para esa unidad/fecha/turno")
    })
    @PostMapping
    public ResponseEntity<RecepcionPlantaResponse> crear(@RequestBody @Valid RecepcionPlantaRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(conciliacionService.registrarYConciliar(dto));
    }

    // Auditoria CRUD (prioridad ALTA): el modulo no tenia ningun listado --
    // RECEPCION no podia revisar el historial de conciliaciones desde el
    // frontend. unidadId es opcional para filtrar por unidad.
    // Bloque 5 (confirmado): agrega filtro opcional por fecha, combinable con
    // el de unidad ya existente. Sin parametros, mismo resultado que antes.
    @Operation(
            summary = "Listar conciliaciones (paginado)",
            description = "`unidadId`, `fecha` y `busqueda` son opcionales y combinables. `busqueda` busca en la placa de la "
                    + "unidad o el responsable, entre TODAS las recepciones (contiene, sin distinguir mayúsculas ni acentos). "
                    + "`page` (default 0) y `size` (default 20, máx. 100) controlan la paginación real. Orden: fecha más "
                    + "reciente primero y, dentro del mismo día, la recepción creada más recientemente primero."
    )
    @ApiResponse(responseCode = "200", description = "Página de conciliaciones", content = @Content(schema = @Schema(implementation = PaginaResponse.class)))
    @GetMapping
    public ResponseEntity<PaginaResponse<RecepcionPlantaResponse>> listar(
            @Parameter(description = "Filtrar por unidad (opcional)") @RequestParam(required = false) UUID unidadId,
            @Parameter(description = "Filtrar por fecha exacta (opcional)") @RequestParam(required = false) LocalDate fecha,
            @Parameter(description = "Placa o responsable (contiene, sin distinguir mayúsculas ni acentos)") @RequestParam(required = false) String busqueda,
            @Parameter(description = "Número de página, empieza en 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 100)") @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return ResponseEntity.ok(PaginaResponse.de(conciliacionService.listar(unidadId, fecha, busqueda, pageable)));
    }

    @Operation(summary = "Obtener el detalle de una conciliación")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Conciliación encontrada", content = @Content(schema = @Schema(implementation = RecepcionPlantaResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe una conciliación con ese id")
    })
    @GetMapping("/{id}")
    public ResponseEntity<RecepcionPlantaResponse> obtener(@Parameter(description = "Id de la conciliación") @PathVariable UUID id) {
        return ResponseEntity.ok(conciliacionService.obtener(id));
    }
}
