package com.planta.acopio.controller;

import com.planta.acopio.dto.DisponibilidadProduccionResponse;
import com.planta.acopio.dto.RecetaRequest;
import com.planta.acopio.dto.RecetaResponse;
import com.planta.acopio.service.RecetaService;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// Recetas de produccion (V18). Permisos con los roles existentes, sin crear nuevos: administrar el catalogo
// (crear/editar/desactivar/reactivar) es ADMIN, igual que Productos/Insumos; consultar y calcular es
// PRODUCCION o ADMIN, los mismos roles que operan /api/lotes-produccion.
@Tag(name = "Recetas", description = "Recetas/formulaciones de producción escalables. Consulta y cálculo: PRODUCCION o ADMIN. Administración: ADMIN.")
@RestController
@RequestMapping("/api/recetas")
@PreAuthorize("hasAnyRole('PRODUCCION','ADMIN')")
public class RecetaController {

    private final RecetaService recetaService;

    public RecetaController(RecetaService recetaService) {
        this.recetaService = recetaService;
    }

    @Operation(summary = "Listar recetas", description = "Por defecto solo activas; `incluirInactivas=true` lista todas.")
    @ApiResponse(responseCode = "200", description = "Recetas", content = @Content(array = @ArraySchema(schema = @Schema(implementation = RecetaResponse.class))))
    @GetMapping
    public ResponseEntity<List<RecetaResponse>> listar(
            @Parameter(description = "Incluir recetas desactivadas") @RequestParam(defaultValue = "false") boolean incluirInactivas) {
        return ResponseEntity.ok(recetaService.listar(incluirInactivas));
    }

    @Operation(summary = "Obtener una receta")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Receta", content = @Content(schema = @Schema(implementation = RecetaResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe una receta con ese id")
    })
    @GetMapping("/{id}")
    public ResponseEntity<RecetaResponse> obtener(@PathVariable UUID id) {
        return ResponseEntity.ok(recetaService.obtener(id));
    }

    @Operation(
            summary = "Calcular una producción con esta receta",
            description = "Escala la receta a `litros` (factor = litros / litrosBase), calcula leche e insumos "
                    + "requeridos, la cantidad ESPERADA y los compara con el stock actual línea por línea. Si falta "
                    + "algo, informa los litros máximos producibles hoy. No modifica nada ni ajusta la cantidad."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cálculo", content = @Content(schema = @Schema(implementation = DisponibilidadProduccionResponse.class))),
            @ApiResponse(responseCode = "400", description = "litros ausente o no mayor que 0"),
            @ApiResponse(responseCode = "404", description = "No existe una receta con ese id")
    })
    @GetMapping("/{id}/calculo")
    public ResponseEntity<DisponibilidadProduccionResponse> calcular(
            @PathVariable UUID id,
            @Parameter(description = "Litros de leche a utilizar") @RequestParam BigDecimal litros) {
        return ResponseEntity.ok(recetaService.calcular(id, litros));
    }

    @Operation(summary = "Crear receta", description = "Solo ADMIN. Insumos del catálogo existente; cantidades por litrosBase.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Receta creada", content = @Content(schema = @Schema(implementation = RecetaResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, presentación de otro producto o insumo repetido"),
            @ApiResponse(responseCode = "404", description = "Producto, presentación o insumo inexistente"),
            @ApiResponse(responseCode = "409", description = "Ya existe una receta con ese nombre para el producto"),
            @ApiResponse(responseCode = "422", description = "Producto, presentación o insumo desactivado")
    })
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RecetaResponse> crear(@RequestBody @Valid RecetaRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(recetaService.crear(dto));
    }

    @Operation(summary = "Editar receta", description = "Solo ADMIN. Producto y presentación no se cambian; los lotes ya planificados conservan su plan.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Receta actualizada", content = @Content(schema = @Schema(implementation = RecetaResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos o cambio de producto/presentación"),
            @ApiResponse(responseCode = "404", description = "Receta o insumo inexistente"),
            @ApiResponse(responseCode = "409", description = "Nombre repetido para el producto")
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RecetaResponse> actualizar(@PathVariable UUID id, @RequestBody @Valid RecetaRequest dto) {
        return ResponseEntity.ok(recetaService.actualizar(id, dto));
    }

    @Operation(summary = "Desactivar receta", description = "Solo ADMIN. Baja lógica: no se puede planificar con ella.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Receta desactivada"),
            @ApiResponse(responseCode = "404", description = "No existe una receta con ese id")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> desactivar(@PathVariable UUID id) {
        recetaService.desactivar(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Reactivar receta", description = "Solo ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Receta reactivada", content = @Content(schema = @Schema(implementation = RecetaResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe una receta con ese id")
    })
    @PatchMapping("/{id}/reactivar")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RecetaResponse> reactivar(@PathVariable UUID id) {
        return ResponseEntity.ok(recetaService.reactivar(id));
    }
}
