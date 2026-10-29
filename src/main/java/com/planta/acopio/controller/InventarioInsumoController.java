package com.planta.acopio.controller;

import com.planta.acopio.dto.AjusteInsumoRequest;
import com.planta.acopio.dto.AnulacionIngresoRequest;
import com.planta.acopio.dto.ConteoInsumoRequest;
import com.planta.acopio.dto.ConteoInsumoResponse;
import com.planta.acopio.dto.LoteInsumoResponse;
import com.planta.acopio.service.LoteInsumoConsultaService;
import com.planta.acopio.dto.IngresoInsumoRequest;
import com.planta.acopio.dto.InsumoResponse;
import com.planta.acopio.dto.MovimientoInsumoResponse;
import com.planta.acopio.service.InventarioInsumoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// Stock de insumos (V19). Movimientos que cambian el stock fuera de produccion: ingreso (PRODUCCION o ADMIN)
// y ajuste por conteo fisico (solo ADMIN, mismo criterio que POST /api/inventario/ajustes de producto
// terminado). Cada uno queda en el kardex con usuario, fecha y motivo/referencia. VENTAS no accede.
@Tag(name = "Inventario de insumos", description = "Stock de insumos, ingresos (PRODUCCION o ADMIN) y ajustes por conteo físico (ADMIN).")
@RestController
@RequestMapping("/api/inventario/insumos")
@PreAuthorize("hasAnyRole('PRODUCCION','ADMIN')")
public class InventarioInsumoController {

    private final InventarioInsumoService inventarioInsumoService;
    private final LoteInsumoConsultaService loteConsultaService;

    public InventarioInsumoController(InventarioInsumoService inventarioInsumoService, LoteInsumoConsultaService loteConsultaService) {
        this.inventarioInsumoService = inventarioInsumoService;
        this.loteConsultaService = loteConsultaService;
    }

    @Operation(summary = "Stock actual de insumos", description = "Activos, más los desactivados que todavía tienen stock.")
    @ApiResponse(responseCode = "200", description = "Insumos con su stock", content = @Content(array = @ArraySchema(schema = @Schema(implementation = InsumoResponse.class))))
    @GetMapping
    public ResponseEntity<List<InsumoResponse>> listar() {
        return ResponseEntity.ok(inventarioInsumoService.listarParaInventario());
    }

    @Operation(summary = "Registrar un ingreso (reposición) de insumo", description = "Suma la cantidad al stock y deja un movimiento INGRESO en el kardex. Solo cantidades (sin costo).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Ingreso registrado", content = @Content(schema = @Schema(implementation = MovimientoInsumoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "No existe el insumo"),
            @ApiResponse(responseCode = "422", description = "Insumo desactivado")
    })
    @PostMapping("/{insumoId}/ingresos")
    public ResponseEntity<MovimientoInsumoResponse> registrarIngreso(@PathVariable UUID insumoId,
                                                                     @RequestBody @Valid IngresoInsumoRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventarioInsumoService.registrarIngreso(insumoId, dto));
    }

    @Operation(summary = "Ajustar el stock de un insumo por conteo físico", description = "Solo ADMIN. `cantidadFisica` es el conteo real; se registra la variación contra el stock del sistema con usuario, fecha y motivo.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Ajuste registrado", content = @Content(schema = @Schema(implementation = MovimientoInsumoResponse.class))),
            @ApiResponse(responseCode = "400", description = "El conteo coincide con el sistema, o datos inválidos"),
            @ApiResponse(responseCode = "404", description = "No existe el insumo")
    })
    @PostMapping("/{insumoId}/ajustes")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MovimientoInsumoResponse> ajustar(@PathVariable UUID insumoId,
                                                            @RequestBody @Valid AjusteInsumoRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventarioInsumoService.ajustar(insumoId, dto));
    }

    @Operation(summary = "Lotes de un insumo", description = "Con su origen (proveedor, documento) y estado calculado (VIGENTE, POR_VENCER, VENCIDO, AGOTADO). `soloConExistencia` oculta los agotados.")
    @GetMapping("/{insumoId}/lotes")
    public ResponseEntity<List<LoteInsumoResponse>> lotes(@PathVariable UUID insumoId, @RequestParam(defaultValue = "false") boolean soloConExistencia) {
        return ResponseEntity.ok(loteConsultaService.listar(insumoId, soloConExistencia));
    }

    @Operation(summary = "Conteo físico por lote", description = "Solo ADMIN. La cantidad contada de cada lote; la diferencia de cada uno queda como un movimiento AJUSTE en ese lote, con el motivo obligatorio.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Conteo registrado", content = @Content(schema = @Schema(implementation = ConteoInsumoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Sin diferencias, lote ajeno o datos inválidos"),
            @ApiResponse(responseCode = "404", description = "No existe el insumo o el lote")
    })
    @PostMapping("/{insumoId}/conteos")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ConteoInsumoResponse> contar(@PathVariable UUID insumoId, @RequestBody @Valid ConteoInsumoRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventarioInsumoService.registrarConteoPorLote(insumoId, dto));
    }

    @Operation(summary = "Anular un ingreso (su lote)", description = "Solo ADMIN. Reversa el lote completo con un movimiento ANULACION_INGRESO; solo si el lote no se ha usado.")
    @PostMapping("/lotes/{loteId}/anulacion")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MovimientoInsumoResponse> anular(@PathVariable UUID loteId, @RequestBody @Valid AnulacionIngresoRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventarioInsumoService.anularIngreso(loteId, dto.motivo()));
    }
}
