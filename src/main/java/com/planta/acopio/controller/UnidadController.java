package com.planta.acopio.controller;

import com.planta.acopio.dto.UnidadResponse;
import com.planta.acopio.service.UnidadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Bloque 4 (confirmado): catalogo operativo separado de /api/admin/unidades.
// IMPORTANTE (documentado, no inventado): "unidad" NO tiene columna `activo`
// en schema.sql (ver comentario en UnidadService.actualizar) -- no existe el
// concepto de unidad inactiva en el modelo actual, asi que este endpoint
// devuelve TODAS las unidades, igual que ya hace GET /api/sync/cambios.
// Si se necesita esa distincion, es un cambio de esquema que requiere
// decision aparte (agregar columna `activo` a `unidad`), no se agrega aqui.
// Reutiliza UnidadService.listar() tal cual -- cero logica nueva.
@Tag(name = "Unidades", description = "Catálogo operativo de unidades (camiones), para roles de campo (sin pasar por /api/admin).")
@RestController
@RequestMapping("/api/unidades")
public class UnidadController {

    private final UnidadService unidadService;

    public UnidadController(UnidadService unidadService) {
        this.unidadService = unidadService;
    }

    @Operation(
            summary = "Listar unidades (operativo)",
            description = "Rol requerido: ADMIN, ACOPIADOR, RECEPCION o CALIDAD. Devuelve TODAS las unidades: "
                    + "`unidad` no tiene columna `activo` en el esquema actual (no existe el concepto de unidad "
                    + "inactiva), mismos datos que GET /api/admin/unidades, distinta matriz de roles."
    )
    @ApiResponse(responseCode = "200", description = "Todas las unidades", content = @Content(array = @ArraySchema(schema = @Schema(implementation = UnidadResponse.class))))
    @GetMapping("/operativo")
    @PreAuthorize("hasAnyRole('ADMIN','ACOPIADOR','RECEPCION','CALIDAD')")
    public ResponseEntity<List<UnidadResponse>> listarOperativo() {
        return ResponseEntity.ok(unidadService.listar());
    }
}
