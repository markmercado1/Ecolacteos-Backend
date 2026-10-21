package com.planta.acopio.controller;

import com.planta.acopio.service.FotoCatalogoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Duration;

// Lectura PUBLICA (sin JWT, ver SecurityConfig) de las fotos del catalogo. Solo sirve el directorio de
// FotoCatalogoService: las fotos de acopio (/api/archivos) siguen protegidas por rol. Los nombres son UUID
// generados por el servidor y nunca se reutilizan, asi que el navegador puede cachearlos "para siempre".
@Tag(name = "Fotos del catálogo", description = "Lectura pública de las fotos de productos y presentaciones.")
@RestController
@RequestMapping("/api/catalogo/fotos")
public class CatalogoFotosController {

    private final FotoCatalogoService fotoCatalogoService;

    public CatalogoFotosController(FotoCatalogoService fotoCatalogoService) {
        this.fotoCatalogoService = fotoCatalogoService;
    }

    @Operation(summary = "Ver una foto del catálogo", description = "Pública: no requiere autenticación.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Contenido de la imagen"),
            @ApiResponse(responseCode = "404", description = "No existe una foto con ese nombre")
    })
    @GetMapping("/{nombre}")
    public ResponseEntity<Resource> obtener(@Parameter(description = "Nombre del archivo (UUID + extensión)") @PathVariable String nombre) {
        Path archivo = fotoCatalogoService.resolver(nombre);
        Resource recurso = new FileSystemResource(archivo);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaTypeFactory.getMediaType(recurso).orElse(MediaType.APPLICATION_OCTET_STREAM))
                .body(recurso);
    }
}
