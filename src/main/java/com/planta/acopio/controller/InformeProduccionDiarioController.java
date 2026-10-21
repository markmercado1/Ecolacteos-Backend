package com.planta.acopio.controller;

import com.planta.acopio.dto.CrearInformeProduccionRequest;
import com.planta.acopio.dto.EditarInformeProduccionRequest;
import com.planta.acopio.dto.InformeProduccionDiarioResponse;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.service.InformeProduccionDiarioService;
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

// Informe diario de produccion de quesos (area de produccion): virtualiza el cuadro en papel "INFORME DE
// PRODUCCION DE QUESOS DIARIO". Modulo independiente de /api/lotes-produccion (ver comentario de clase de
// InformeProduccionDiarioService). Mismos roles que Lotes de Produccion.
@Tag(name = "Producción Diaria", description = "Informe diario de producción de quesos (quesos, recorte, insumos usados). Rol requerido: PRODUCCION o ADMIN.")
@RestController
@RequestMapping("/api/informes-produccion")
@PreAuthorize("hasAnyRole('PRODUCCION','ADMIN')")
public class InformeProduccionDiarioController {

    private final InformeProduccionDiarioService service;

    public InformeProduccionDiarioController(InformeProduccionDiarioService service) {
        this.service = service;
    }

    @Operation(
            summary = "Registrar el informe de producción de un día",
            description = "Idempotente por `uuidCliente`. Un solo informe por `fecha` (UNIQUE): si ya existe "
                    + "uno para esa fecha, usa PUT /{id} para editarlo en vez de crear otro. Solo se puede "
                    + "crear para la fecha de HOY (ni pasada ni futura)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Informe creado (o el existente, si el uuidCliente ya se había recibido)",
                    content = @Content(schema = @Schema(implementation = InformeProduccionDiarioResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, `fecha` distinta de hoy, o un insumoId repetido en `insumos`"),
            @ApiResponse(responseCode = "404", description = "Algún insumoId indicado no existe"),
            @ApiResponse(responseCode = "409", description = "Ya existe un informe para esa fecha (incluye la carrera concurrente)")
    })
    @PostMapping
    public ResponseEntity<InformeProduccionDiarioResponse> crear(@RequestBody @Valid CrearInformeProduccionRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.crear(dto));
    }

    @Operation(
            summary = "Editar el informe de un día ya registrado",
            description = "Reemplaza por completo quesos/recorte/insumos (no un parche parcial). La fecha no "
                    + "se reasigna acá. Solo se puede editar el informe cuya `fecha` sea HOY -- uno de una "
                    + "fecha pasada (o futura) se rechaza."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Informe actualizado", content = @Content(schema = @Schema(implementation = InformeProduccionDiarioResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, el informe no es de hoy, o un insumoId repetido en `insumos`"),
            @ApiResponse(responseCode = "404", description = "No existe un informe con ese id, o algún insumoId indicado no existe")
    })
    @PutMapping("/{id}")
    public ResponseEntity<InformeProduccionDiarioResponse> editar(@Parameter(description = "Id del informe") @PathVariable UUID id,
                                                                    @RequestBody @Valid EditarInformeProduccionRequest dto) {
        return ResponseEntity.ok(service.editar(id, dto));
    }

    @Operation(
            summary = "Listar informes de producción (paginado)",
            description = "`desde`/`hasta` son opcionales y combinables (filtran por rango de fecha, ambos "
                    + "inclusive). `page` (default 0) y `size` (default 20, máx. 100) controlan la paginación."
    )
    @ApiResponse(responseCode = "200", description = "Página de informes encontrados", content = @Content(schema = @Schema(implementation = PaginaResponse.class)))
    @GetMapping
    public ResponseEntity<PaginaResponse<InformeProduccionDiarioResponse>> listar(
            @Parameter(description = "Filtrar desde esta fecha (inclusive)") @RequestParam(required = false) LocalDate desde,
            @Parameter(description = "Filtrar hasta esta fecha (inclusive)") @RequestParam(required = false) LocalDate hasta,
            @Parameter(description = "Número de página, empieza en 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 100)") @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return ResponseEntity.ok(PaginaResponse.de(service.buscar(desde, hasta, pageable)));
    }

    @Operation(summary = "Obtener el detalle de un informe")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Informe encontrado", content = @Content(schema = @Schema(implementation = InformeProduccionDiarioResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe un informe con ese id")
    })
    @GetMapping("/{id}")
    public ResponseEntity<InformeProduccionDiarioResponse> obtener(@Parameter(description = "Id del informe") @PathVariable UUID id) {
        return ResponseEntity.ok(service.obtener(id));
    }

    @Operation(
            summary = "Obtener el informe de una fecha exacta",
            description = "Pensado para que la web consulte, antes de mostrar el formulario, si ya existe un "
                    + "informe para el día seleccionado (y en ese caso lo precargue para editar)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Informe encontrado", content = @Content(schema = @Schema(implementation = InformeProduccionDiarioResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe un informe para esa fecha")
    })
    @GetMapping("/fecha/{fecha}")
    public ResponseEntity<InformeProduccionDiarioResponse> obtenerPorFecha(
            @Parameter(description = "Fecha exacta (yyyy-MM-dd)") @PathVariable LocalDate fecha) {
        return ResponseEntity.ok(service.obtenerPorFecha(fecha));
    }
}
