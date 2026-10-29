package com.planta.acopio.controller;

import com.planta.acopio.dto.DuplicadosInsumoResponse;
import com.planta.acopio.dto.FusionInsumoResponse;
import com.planta.acopio.dto.FusionarInsumosRequest;
import com.planta.acopio.dto.VistaPreviaFusionResponse;
import com.planta.acopio.service.FusionInsumoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Fusion de insumos duplicados (V41). Solo ADMIN.
@Tag(name = "Fusión de insumos", description = "Detecta insumos duplicados (mismo nombre sin mayúsculas ni tildes) y los fusiona sin perder historial. Solo ADMIN.")
@RestController
@RequestMapping("/api/admin/insumos")
@PreAuthorize("hasRole('ADMIN')")
public class FusionInsumoController {

    private final FusionInsumoService service;

    public FusionInsumoController(FusionInsumoService service) {
        this.service = service;
    }

    @Operation(summary = "Insumos duplicados", description = "Grupos de insumos cuyo nombre normalizado coincide.")
    @GetMapping("/duplicados")
    public ResponseEntity<DuplicadosInsumoResponse> duplicados() {
        return ResponseEntity.ok(service.duplicados());
    }

    @Operation(summary = "Vista previa de una fusión", description = "Qué se reasignaría y qué impide la fusión, sin cambiar nada.")
    @GetMapping("/fusion/vista-previa")
    public ResponseEntity<VistaPreviaFusionResponse> vistaPrevia(@RequestParam UUID origenId, @RequestParam UUID destinoId) {
        return ResponseEntity.ok(service.vistaPrevia(origenId, destinoId));
    }

    @Operation(summary = "Fusionar un insumo en otro", description = "El origen queda inactivo (stock 0) y su historial pasa al destino. Queda auditado.")
    @PostMapping("/fusion")
    public ResponseEntity<FusionInsumoResponse> fusionar(@RequestBody @Valid FusionarInsumosRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.fusionar(dto.origenId(), dto.destinoId()));
    }

    @Operation(summary = "Historial de fusiones", description = "Las 50 fusiones más recientes.")
    @GetMapping("/fusiones")
    public ResponseEntity<List<FusionInsumoResponse>> historial() {
        return ResponseEntity.ok(service.historial());
    }
}
