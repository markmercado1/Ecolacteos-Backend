package com.planta.acopio.controller;

import com.planta.acopio.dto.KardexResponse;
import com.planta.acopio.service.KardexService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

// Kardex por cantidades (V19), solo lectura. Permisos con los roles existentes: PRODUCCION y ADMIN consultan
// los tres kardex; VENTAS solo el de producto terminado (es lo unico que vende), igual que su acceso actual a
// /api/inventario/stock. No hay endpoints para editar ni borrar movimientos.
@Tag(name = "Kardex", description = "Kardex por cantidades de leche, insumos y producto terminado. Leche e insumos: PRODUCCION o ADMIN. Producto terminado: PRODUCCION, VENTAS o ADMIN.")
@RestController
@RequestMapping("/api/inventario/kardex")
public class KardexController {

    private static final int TAMANO_DEFECTO = 50;
    private static final int TAMANO_MAXIMO = 200;

    private final KardexService kardexService;

    public KardexController(KardexService kardexService) {
        this.kardexService = kardexService;
    }

    @Operation(summary = "Kardex de leche", description = "Entradas por recepción conciliada y salidas por producción, en orden cronológico, con saldo acumulado. `desde`/`hasta` (inclusive) filtran por fecha de registro; los totales cubren todo el rango.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Kardex", content = @Content(schema = @Schema(implementation = KardexResponse.class))),
            @ApiResponse(responseCode = "400", description = "desde posterior a hasta")
    })
    @GetMapping("/leche")
    @PreAuthorize("hasAnyRole('PRODUCCION','ADMIN')")
    public ResponseEntity<KardexResponse> leche(
            @Parameter(description = "Desde (inclusive)") @RequestParam(required = false) LocalDate desde,
            @Parameter(description = "Hasta (inclusive)") @RequestParam(required = false) LocalDate hasta,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "" + TAMANO_DEFECTO) int size) {
        return ResponseEntity.ok(kardexService.leche(desde, hasta, pagina(page, size)));
    }

    @Operation(summary = "Kardex de un insumo", description = "Saldo inicial, ingresos, salidas por producción y ajustes por conteo, con saldo acumulado.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Kardex", content = @Content(schema = @Schema(implementation = KardexResponse.class))),
            @ApiResponse(responseCode = "400", description = "desde posterior a hasta"),
            @ApiResponse(responseCode = "404", description = "No existe el insumo")
    })
    @GetMapping("/insumos/{insumoId}")
    @PreAuthorize("hasAnyRole('PRODUCCION','ADMIN')")
    public ResponseEntity<KardexResponse> insumo(
            @PathVariable UUID insumoId,
            @RequestParam(required = false) LocalDate desde, @RequestParam(required = false) LocalDate hasta,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "" + TAMANO_DEFECTO) int size) {
        return ResponseEntity.ok(kardexService.insumo(insumoId, desde, hasta, pagina(page, size)));
    }

    @Operation(summary = "Kardex de producto terminado por presentación", description = "Entradas por producción, salidas por venta y ajustes por conteo, con saldo acumulado. Solo cantidades: no incluye precios.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Kardex", content = @Content(schema = @Schema(implementation = KardexResponse.class))),
            @ApiResponse(responseCode = "400", description = "desde posterior a hasta"),
            @ApiResponse(responseCode = "404", description = "No existe la presentación")
    })
    @GetMapping("/presentaciones/{presentacionId}")
    @PreAuthorize("hasAnyRole('PRODUCCION','VENTAS','ADMIN')")
    public ResponseEntity<KardexResponse> presentacion(
            @PathVariable UUID presentacionId,
            @RequestParam(required = false) LocalDate desde, @RequestParam(required = false) LocalDate hasta,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "" + TAMANO_DEFECTO) int size) {
        return ResponseEntity.ok(kardexService.presentacion(presentacionId, desde, hasta, pagina(page, size)));
    }

    private static Pageable pagina(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), TAMANO_MAXIMO));
    }
}
