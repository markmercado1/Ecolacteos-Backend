package com.planta.acopio.controller;

import com.planta.acopio.dto.RevisionDocumentosResponse;
import com.planta.acopio.service.RevisionDocumentosService;
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

// Fase G (documentos del proveedor). Endpoint EXCLUSIVO de la web (el movil no usa /api/admin/*), solo lectura.
@Tag(name = "Proveedores", description = "Productores de leche de la zona.")
@RestController
@RequestMapping("/api/admin/proveedores")
public class RevisionDocumentosController {

    private final RevisionDocumentosService service;

    public RevisionDocumentosController(RevisionDocumentosService service) {
        this.service = service;
    }

    @Operation(summary = "Informe de revisión de documentos", description = "Rol requerido: ADMIN. Solo lectura. Lista los proveedores "
            + "cuyo DNI o RUC no cumple las reglas actuales (DNI sin 8 dígitos, RUC en el campo DNI, DNI repetidos salvo espacios o "
            + "ceros, RUC con verificador o prefijo incorrecto, RUC 10 que no contiene el DNI, RUC que contiene el DNI de otra ficha). "
            + "No corrige nada.")
    @ApiResponse(responseCode = "200", description = "Casos a revisar",
            content = @Content(schema = @Schema(implementation = RevisionDocumentosResponse.class)))
    @GetMapping("/revision-documentos")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RevisionDocumentosResponse> revision() {
        return ResponseEntity.ok(service.informe());
    }
}
