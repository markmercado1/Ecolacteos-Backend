package com.planta.acopio.controller;

import com.planta.acopio.dto.AdjuntoResponse;
import com.planta.acopio.dto.TrazabilidadResponse;
import com.planta.acopio.service.AdjuntoService;
import com.planta.acopio.service.TrazabilidadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

// Trazabilidad por lote (busqueda) y adjuntos del recibo de un ingreso (V46). Consulta: PRODUCCION, CALIDAD o ADMIN. Adjuntar: PRODUCCION o ADMIN.
@Tag(name = "Trazabilidad y adjuntos", description = "Búsqueda por código de lote (origen, proveedor, documento, usos) y fotos o PDF del recibo de un ingreso.")
@RestController
public class TrazabilidadController {

    private final TrazabilidadService trazabilidadService;
    private final AdjuntoService adjuntoService;

    public TrazabilidadController(TrazabilidadService trazabilidadService, AdjuntoService adjuntoService) {
        this.trazabilidadService = trazabilidadService;
        this.adjuntoService = adjuntoService;
    }

    @Operation(summary = "Buscar la trazabilidad de un lote", description = "`q`: código del lote de insumo (o el que puso el proveedor), o el id de un lote de producción. Mínimo 2 caracteres.")
    @GetMapping("/api/inventario/trazabilidad/lotes")
    @PreAuthorize("hasAnyRole('PRODUCCION','CALIDAD','ADMIN')")
    public ResponseEntity<TrazabilidadResponse> buscar(@RequestParam String q) {
        return ResponseEntity.ok(trazabilidadService.buscar(q));
    }

    @Operation(summary = "Adjuntar el recibo (foto o PDF) a un documento de ingreso", description = "multipart/form-data, campo `file`. JPG, PNG, WEBP o PDF, máximo 10 MB; el tipo se valida por el contenido.")
    @PostMapping(value = "/api/inventario/ingresos/{documentoId}/adjuntos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('PRODUCCION','ADMIN')")
    public ResponseEntity<AdjuntoResponse> adjuntar(@PathVariable UUID documentoId, @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adjuntoService.adjuntarADocumentoIngreso(documentoId, file));
    }

    @Operation(summary = "Adjuntos de un documento de ingreso")
    @GetMapping("/api/inventario/ingresos/{documentoId}/adjuntos")
    @PreAuthorize("hasAnyRole('PRODUCCION','CALIDAD','ADMIN')")
    public ResponseEntity<List<AdjuntoResponse>> adjuntos(@PathVariable UUID documentoId) {
        return ResponseEntity.ok(adjuntoService.listarDeDocumentoIngreso(documentoId));
    }

    @Operation(summary = "Descargar o ver el contenido de un adjunto")
    @GetMapping("/api/adjuntos/{id}/contenido")
    @PreAuthorize("hasAnyRole('PRODUCCION','CALIDAD','ADMIN')")
    public ResponseEntity<Resource> contenido(@PathVariable UUID id) {
        AdjuntoService.Contenido c = adjuntoService.contenido(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(c.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(c.nombre(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(c.archivo()));
    }
}
