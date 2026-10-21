package com.planta.acopio.controller;

import com.planta.acopio.dto.CatalogoPublicoResponse;
import com.planta.acopio.service.CatalogoPublicoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

// Fase 3: catalogo PUBLICO (sin JWT, ver SecurityConfig), solo lectura. Es el unico endpoint de datos sin
// autenticacion ademas de las fotos del catalogo: devuelve unicamente lo que un cliente necesita (nombre, foto,
// presentacion, precio PUBLICO y un estado de disponibilidad) y nunca stock, lotes, fechas ni costos.
@Tag(name = "Catálogo público", description = "Lectura pública de los productos que se pueden separar: precio público y disponibilidad.")
@RestController
@RequestMapping("/api/catalogo")
public class CatalogoPublicoController {

    private final CatalogoPublicoService service;

    public CatalogoPublicoController(CatalogoPublicoService service) {
        this.service = service;
    }

    @Operation(summary = "Catálogo público de productos",
            description = "Pública: no requiere autenticación. Solo productos que controlan vencimiento y presentaciones con precio PUBLICO "
                    + "vigente. `disponibilidad` (DISPONIBLE, POCAS_UNIDADES, AGOTADO) refleja el stock vendible real (lotes vigentes), "
                    + "no el físico. Informativo: la comprobación autoritativa ocurre al reservar.")
    @ApiResponse(responseCode = "200", description = "Productos publicables", content = @Content(schema = @Schema(implementation = CatalogoPublicoResponse.class)))
    @GetMapping("/productos")
    public ResponseEntity<CatalogoPublicoResponse> productos() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(10)).cachePublic())
                .body(service.obtener());
    }
}
