package com.planta.acopio.controller;

import com.planta.acopio.dto.AnalisisBandejaResponse;
import com.planta.acopio.dto.AnalisisCalidadRequest;
import com.planta.acopio.dto.AnalisisCalidadResponse;
import com.planta.acopio.service.AnalisisCalidadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Análisis de Calidad", description = "Resultados del lactoscan (agua, proteína, lactosa, densidad, temperatura, pH). Rol requerido: CALIDAD o ADMIN.")
@RestController
@RequestMapping("/api/analisis-calidad")
@PreAuthorize("hasAnyRole('CALIDAD','ADMIN')")
public class AnalisisCalidadController {

    private final AnalisisCalidadService service;

    public AnalisisCalidadController(AnalisisCalidadService service) {
        this.service = service;
    }

    @Operation(
            summary = "Registrar un análisis de calidad",
            description = "Idempotente por `uuidCliente` (capturable offline, igual que el registro de "
                    + "acopio). `resultado` se calcula solo: `aguaAnadida=true` → RECHAZADO, si no → APROBADO."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Análisis creado (o el existente, si el uuidCliente ya se había recibido)",
                    content = @Content(schema = @Schema(implementation = AnalisisCalidadResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "No existe un registro de acopio con ese registroAcopioId")
    })
    @PostMapping
    public ResponseEntity<AnalisisCalidadResponse> crear(@RequestBody @Valid AnalisisCalidadRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registrarOIgnorarSiDuplicado(dto));
    }

    @Operation(summary = "Listar entregas analizadas en una fecha", description = "Fecha de la entrega. Devuelve el ultimo resultado confirmado de cada registro, incluido RECHAZADO. Rol CALIDAD o ADMIN.")
    @GetMapping("/bandeja")
    public ResponseEntity<List<AnalisisBandejaResponse>> bandeja(
            @Parameter(description = "Fecha calendario de la entrega") @RequestParam LocalDate fecha) {
        return ResponseEntity.ok(service.listarBandeja(fecha));
    }

    // RF-CAL-02: contraste de doble muestra.
    @Operation(summary = "Buscar análisis por folio de muestra", description = "RF-CAL-02: contraste de doble muestra. Puede devolver más de un análisis si el folio se reutilizó.")
    @ApiResponse(responseCode = "200", description = "Análisis con ese folio", content = @Content(array = @ArraySchema(schema = @Schema(implementation = AnalisisCalidadResponse.class))))
    @GetMapping("/folio/{folio}")
    public ResponseEntity<List<AnalisisCalidadResponse>> porFolio(@Parameter(description = "Folio de la muestra") @PathVariable String folio) {
        return ResponseEntity.ok(service.buscarPorFolio(folio));
    }

    // Auditoria CRUD (prioridad MEDIA): faltaba ver los analisis asociados a
    // un registro de acopio puntual (vista de detalle del registro).
    @Operation(summary = "Listar análisis de un registro de acopio", description = "Orden descendente por fecha de creación.")
    @ApiResponse(responseCode = "200", description = "Análisis del registro", content = @Content(array = @ArraySchema(schema = @Schema(implementation = AnalisisCalidadResponse.class))))
    @GetMapping("/registro/{registroAcopioId}")
    public ResponseEntity<List<AnalisisCalidadResponse>> porRegistro(@Parameter(description = "Id del registro de acopio") @PathVariable UUID registroAcopioId) {
        return ResponseEntity.ok(service.buscarPorRegistro(registroAcopioId));
    }
}
