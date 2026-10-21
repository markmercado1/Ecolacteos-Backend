package com.planta.acopio.controller;

import com.planta.acopio.dto.ControlVencimientoResponse;
import com.planta.acopio.dto.CorregirVencimientoRequest;
import com.planta.acopio.dto.ProductosConControlResponse;
import com.planta.acopio.dto.RegularizacionRequest;
import com.planta.acopio.dto.RegularizacionResponse;
import com.planta.acopio.dto.VencimientoLoteResponse;
import com.planta.acopio.service.ControlVencimientoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Fase 1 (control de vencimiento). Endpoints EXCLUSIVOS de la web (el movil no usa /api/admin/* ni estas
// rutas): activar/desactivar el control de un producto y corregir una fecha de vencimiento. Controlador
// aparte para no tocar los existentes.
@Tag(name = "Control de vencimiento", description = "Activación del control por producto y corrección de fechas de vencimiento de lotes.")
@RestController
@RequestMapping("/api")
public class ControlVencimientoController {

    private final ControlVencimientoService service;

    public ControlVencimientoController(ControlVencimientoService service) {
        this.service = service;
    }

    @Operation(summary = "Productos que controlan vencimiento", description = "Roles requeridos: PRODUCCION, VENTAS o ADMIN. Solo ids; "
            + "la web lo usa para pedir fecha de vencimiento al finalizar un lote y para mostrar el estado del control.")
    @ApiResponse(responseCode = "200", description = "Ids de productos con control", content = @Content(schema = @Schema(implementation = ProductosConControlResponse.class)))
    @GetMapping("/control-vencimiento/productos")
    @PreAuthorize("hasAnyRole('PRODUCCION','VENTAS','ADMIN')")
    public ResponseEntity<ProductosConControlResponse> productosConControl() {
        return ResponseEntity.ok(service.productosConControl());
    }

    @Operation(summary = "Activar el control de vencimiento de un producto", description = "Rol requerido: ADMIN. Deshabilitado por "
            + "defecto en entornos reales (app.vencimiento.activacion-habilitada). Las existencias actuales quedan como \"sin trazabilidad\".")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Control activo", content = @Content(schema = @Schema(implementation = ControlVencimientoResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe el producto"),
            @ApiResponse(responseCode = "409", description = "Activación deshabilitada en este entorno")
    })
    @PutMapping("/admin/productos/{id}/control-vencimiento")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ControlVencimientoResponse> activar(@Parameter(description = "Id del producto") @PathVariable UUID id) {
        return ResponseEntity.ok(service.activar(id));
    }

    @Operation(summary = "Desactivar el control de vencimiento de un producto", description = "Rol requerido: ADMIN. Solo si no existen "
            + "movimientos asignados a lotes desde la activación y el stock sigue igual al saldo inicial.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Control desactivado", content = @Content(schema = @Schema(implementation = ControlVencimientoResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe el producto"),
            @ApiResponse(responseCode = "409", description = "Ya hay movimientos asignados a lotes o el stock cambió")
    })
    @DeleteMapping("/admin/productos/{id}/control-vencimiento")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ControlVencimientoResponse> desactivar(@Parameter(description = "Id del producto") @PathVariable UUID id) {
        return ResponseEntity.ok(service.desactivar(id));
    }

    @Operation(summary = "Regularizar existencias sin trazabilidad", description = "Rol requerido: ADMIN. Reclasifica N unidades del "
            + "saldo sin trazabilidad de la presentación a un lote con la fecha de vencimiento de la etiqueta. No escribe en el libro "
            + "ni en el kardex. Motivo obligatorio; N no puede superar lo que sigue sin trazabilidad.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Regularización registrada", content = @Content(schema = @Schema(implementation = RegularizacionResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, el producto no controla vencimiento o N supera lo sin trazabilidad"),
            @ApiResponse(responseCode = "404", description = "No existe la presentación")
    })
    @PostMapping("/admin/presentaciones/{presentacionId}/regularizaciones")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RegularizacionResponse> regularizar(@Parameter(description = "Id de la presentación") @PathVariable UUID presentacionId,
                                                                @RequestBody @Valid RegularizacionRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.regularizar(presentacionId, dto));
    }

    @Operation(summary = "Corregir la fecha de vencimiento de una salida de lote", description = "Roles requeridos: PRODUCCION o ADMIN. "
            + "Solo mientras el lote no se haya usado. Motivo obligatorio; se conserva la fecha original.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Fecha corregida", content = @Content(schema = @Schema(implementation = VencimientoLoteResponse.class))),
            @ApiResponse(responseCode = "400", description = "Fecha inválida, producto sin control o sin fecha registrada"),
            @ApiResponse(responseCode = "404", description = "No existe la salida de lote"),
            @ApiResponse(responseCode = "409", description = "El lote ya fue utilizado")
    })
    @PatchMapping("/lotes-produccion/detalles/{detalleId}/vencimiento")
    @PreAuthorize("hasAnyRole('PRODUCCION','ADMIN')")
    public ResponseEntity<VencimientoLoteResponse> corregir(@Parameter(description = "Id de la salida del lote") @PathVariable UUID detalleId,
                                                              @RequestBody @Valid CorregirVencimientoRequest dto) {
        return ResponseEntity.ok(service.corregirVencimiento(detalleId, dto));
    }
}
