package com.planta.acopio.controller;

import com.planta.acopio.dto.RevisionZonasResponse;
import com.planta.acopio.service.RevisionZonasService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Fase A (zonas historicas). Endpoint EXCLUSIVO de la web (el movil no usa /api/admin/*), solo lectura.
@Tag(name = "Zonas históricas", description = "Informe de revisión de las zonas que el sistema no pudo decidir.")
@RestController
@RequestMapping("/api/admin/zonas")
public class RevisionZonasController {

    private final RevisionZonasService service;

    public RevisionZonasController(RevisionZonasService service) {
        this.service = service;
    }

    @Operation(summary = "Informe de revisión de zonas", description = "Rol requerido: ADMIN. Solo lectura. Lista los periodos de zona "
            + "con fechas invertidas (reasignaciones el mismo día con el código anterior) y las entregas cuya zona de ese momento no se "
            + "pudo determinar (día de cambio de zona sin hora registrada, o anteriores a la historia). No corrige nada.")
    @ApiResponse(responseCode = "200", description = "Casos a revisar", content = @Content(schema = @Schema(implementation = RevisionZonasResponse.class)))
    @GetMapping("/revision")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RevisionZonasResponse> revision() {
        return ResponseEntity.ok(service.informe());
    }
}
