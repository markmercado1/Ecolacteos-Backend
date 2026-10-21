package com.planta.acopio.controller;

import com.planta.acopio.dto.ArchivoResponse;
import com.planta.acopio.service.ArchivoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

// Bloque 3 (confirmado): carga real de archivo, almacenamiento local en disco
// (ver ArchivoService -- decision de infraestructura de produccion pendiente
// de confirmar, no resuelta aqui).
@Tag(name = "Archivos", description = "Carga de fotografías para RegistroAcopio.fotoUrl. Almacenamiento local en disco.")
@RestController
@RequestMapping("/api/archivos")
public class ArchivoController {

    private final ArchivoService archivoService;

    public ArchivoController(ArchivoService archivoService) {
        this.archivoService = archivoService;
    }

    @Operation(
            summary = "Subir una fotografía",
            description = "Rol requerido: ACOPIADOR o ADMIN. multipart/form-data, campo `file`. Solo imágenes "
                    + "(jpeg/png/webp). Devuelve una URL relativa a este backend, usable directamente en "
                    + "RegistroAcopioDTO.fotoUrl (GET a esa URL devuelve el contenido real del archivo)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Archivo guardado", content = @Content(schema = @Schema(implementation = ArchivoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Archivo vacío, tipo de contenido no permitido, o excede el tamaño máximo")
    })
    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('ACOPIADOR','ADMIN')")
    public ResponseEntity<ArchivoResponse> subir(@Parameter(description = "Archivo de imagen") @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(archivoService.subir(file));
    }

    @Operation(
            summary = "Descargar/ver una fotografía",
            description = "Rol requerido: ACOPIADOR, ADMIN o CALIDAD (mismos roles que pueden ver el detalle de un RegistroAcopio)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Contenido del archivo"),
            @ApiResponse(responseCode = "404", description = "No existe un archivo con ese nombre")
    })
    @GetMapping("/{nombre}")
    @PreAuthorize("hasAnyRole('ACOPIADOR','ADMIN','CALIDAD')")
    public ResponseEntity<Resource> obtener(@PathVariable String nombre) {
        Path archivo = archivoService.resolver(nombre);
        Resource recurso = new FileSystemResource(archivo);
        return ResponseEntity.ok()
                .contentType(MediaTypeFactory.getMediaType(recurso).orElse(MediaType.APPLICATION_OCTET_STREAM))
                .body(recurso);
    }
}
