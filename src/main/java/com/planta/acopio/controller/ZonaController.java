package com.planta.acopio.controller;

import com.planta.acopio.dto.ZonaResponse;
import com.planta.acopio.service.ZonaService;
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

// Bloque 4 (confirmado): catalogo operativo separado de /api/admin/zonas --
// mismos datos (Zona no tiene campos sensibles que redactar, a diferencia de
// Proveedor), distinta matriz de roles. Reutiliza ZonaService.listarActivas()
// tal cual -- cero logica nueva, solo una ruta y un @PreAuthorize mas amplio.
// No reemplaza ni modifica AdminCatalogosController.
@Tag(name = "Zonas", description = "Catálogo operativo de zonas activas, para roles de campo (sin pasar por /api/admin).")
@RestController
@RequestMapping("/api/zonas")
public class ZonaController {

    private final ZonaService zonaService;

    public ZonaController(ZonaService zonaService) {
        this.zonaService = zonaService;
    }

    @Operation(
            summary = "Listar zonas activas (operativo)",
            description = "Rol requerido: ADMIN, ACOPIADOR, RECEPCION o CALIDAD. Solo zonas activas -- mismos "
                    + "datos que GET /api/admin/zonas (Zona no tiene datos personales que redactar), pensado "
                    + "para que roles de campo no dependan de un endpoint ADMIN-only."
    )
    @ApiResponse(responseCode = "200", description = "Zonas activas", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ZonaResponse.class))))
    @GetMapping("/operativo")
    @PreAuthorize("hasAnyRole('ADMIN','ACOPIADOR','RECEPCION','CALIDAD')")
    public ResponseEntity<List<ZonaResponse>> listarOperativo() {
        return ResponseEntity.ok(zonaService.listarActivas());
    }
}
