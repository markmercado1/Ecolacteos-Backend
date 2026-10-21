package com.planta.acopio.controller;

import com.planta.acopio.dto.DefinirRutaZonaRequest;
import com.planta.acopio.dto.RutaProveedorOrdenResponse;
import com.planta.acopio.service.RutaZonaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// RF-RUT-04 (CORE_DESIGN.md §3 / §7): entidad RutaProveedorOrden existente en
// el schema desde Fase 1, sin service/controller hasta esta auditoria.
// "Definir" reemplaza la ruta completa de la zona (no upsert item por item)
// -- ver RutaZonaService.definirRuta.
@Tag(name = "Rutas de Zona", description = "Orden de visita a los proveedores de una zona, con hora estimada.")
@RestController
@RequestMapping("/api/zonas/{zonaId}/ruta")
public class RutaProveedorController {

    private final RutaZonaService rutaZonaService;

    public RutaProveedorController(RutaZonaService rutaZonaService) {
        this.rutaZonaService = rutaZonaService;
    }

    @Operation(
            summary = "Definir la ruta de una zona",
            description = "Rol requerido: ADMIN. Reemplaza la ruta COMPLETA de la zona en cada llamada "
                    + "(borra el orden anterior e inserta el nuevo) — no es un upsert item por item. "
                    + "Cada proveedor incluido debe pertenecer actualmente a esa zona, y los valores de "
                    + "`orden` no pueden repetirse dentro del mismo request."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ruta definida", content = @Content(array = @ArraySchema(schema = @Schema(implementation = RutaProveedorOrdenResponse.class)))),
            @ApiResponse(responseCode = "400", description = "Ruta inválida: orden o proveedor repetido dentro del request"),
            @ApiResponse(responseCode = "404", description = "La zona no existe, o algún proveedor no existe"),
            @ApiResponse(responseCode = "422", description = "Algún proveedor incluido no pertenece actualmente a esta zona")
    })
    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<RutaProveedorOrdenResponse>> definir(
            @Parameter(description = "Id de la zona") @PathVariable UUID zonaId,
            @RequestBody @Valid DefinirRutaZonaRequest dto) {
        return ResponseEntity.ok(rutaZonaService.definirRuta(zonaId, dto));
    }

    // Lectura tambien para ACOPIADOR: RF-RUT-04 existe para que el acopiador
    // sepa el orden de visita en campo.
    @Operation(
            summary = "Consultar la ruta vigente de una zona",
            description = "Rol requerido: ADMIN o ACOPIADOR. Devuelve los puntos de la ruta en orden ascendente."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ruta de la zona", content = @Content(array = @ArraySchema(schema = @Schema(implementation = RutaProveedorOrdenResponse.class)))),
            @ApiResponse(responseCode = "404", description = "La zona no existe")
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','ACOPIADOR')")
    public ResponseEntity<List<RutaProveedorOrdenResponse>> listar(@Parameter(description = "Id de la zona") @PathVariable UUID zonaId) {
        return ResponseEntity.ok(rutaZonaService.listarPorZona(zonaId));
    }
}
