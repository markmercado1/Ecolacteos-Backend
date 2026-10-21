package com.planta.acopio.controller;

import com.planta.acopio.dto.AjusteInventarioRequest;
import com.planta.acopio.dto.AjusteInventarioResponse;
import com.planta.acopio.dto.ExistenciasPresentacionResponse;
import com.planta.acopio.dto.FilaStockResponse;
import com.planta.acopio.dto.FamiliaResponse;
import com.planta.acopio.service.AjusteInventarioService;
import com.planta.acopio.service.FamiliaService;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.dto.SaldoLecheResponse;
import com.planta.acopio.dto.StockPresentacionResponse;
import com.planta.acopio.service.InventarioLecheService;
import com.planta.acopio.service.InventarioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.List;

// Solo lectura (V7__inventario_precio_queso.sql, V8/V9__inventario_leche/consumo, V12 fase 3): ni el stock
// de producto terminado ni el saldo de leche se modifican directamente por API, unicamente como efecto de
// operaciones validas ya existentes (LoteProduccionService.crear(), ConciliacionService,
// VentaService.registrarOIgnorarSiDuplicado()). Sin POST/PUT/DELETE.
@Tag(name = "Inventario", description = "Stock de producto terminado (por presentación) y saldo de leche, calculados desde sus libros mayores de movimientos. Rol requerido: PRODUCCION, VENTAS o ADMIN.")
@RestController
@RequestMapping("/api/inventario")
@PreAuthorize("hasAnyRole('PRODUCCION','VENTAS','ADMIN')")
public class InventarioController {

    private final InventarioService inventarioService;
    private final InventarioLecheService inventarioLecheService;
    private final FamiliaService familiaService;
    private final AjusteInventarioService ajusteInventarioService;

    public InventarioController(InventarioService inventarioService, InventarioLecheService inventarioLecheService,
                                FamiliaService familiaService, AjusteInventarioService ajusteInventarioService) {
        this.inventarioService = inventarioService;
        this.inventarioLecheService = inventarioLecheService;
        this.familiaService = familiaService;
        this.ajusteInventarioService = ajusteInventarioService;
    }

    @Operation(summary = "Familias para filtrar inventario, incluidas las inactivas con posible saldo")
    @GetMapping("/familias")
    public ResponseEntity<List<FamiliaResponse>> familias() {
        return ResponseEntity.ok(familiaService.listarParaInventario());
    }

    // Fase 3 (Inventario por presentacion, ampliacion: listado paginado organizado por familia, mismo
    // patron que /admin/precios-queso/presentaciones de Fase 2). Reemplaza el antiguo GET /stock (por
    // producto, sin paginar): el stock ahora se controla por presentacion.
    @Operation(
            summary = "Consultar el stock disponible por presentación (paginado, organizado por familia)",
            description = "Calculado como SUM(cantidad) del libro mayor de movimientos de inventario -- no "
                    + "existe una tabla de stock mutable. `familiaId` y `busqueda` (coincide con el nombre "
                    + "del producto o de la presentación) son opcionales y se aplican en el servidor antes "
                    + "de paginar. Incluye presentaciones/productos desactivados si conservan stock "
                    + "remanente distinto de cero."
    )
    @ApiResponse(responseCode = "200", description = "Página de stock por presentación", content = @Content(schema = @Schema(implementation = PaginaResponse.class)))
    @GetMapping("/stock/presentaciones")
    public ResponseEntity<PaginaResponse<FilaStockResponse>> stockPorPresentacion(
            @Parameter(description = "Filtrar por familia") @RequestParam(required = false) UUID familiaId,
            @Parameter(description = "Busca en el nombre del producto o de la presentación") @RequestParam(required = false) String busqueda,
            @Parameter(description = "Número de página, empieza en 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 100)") @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        String busquedaNormalizada = (busqueda == null || busqueda.isBlank()) ? null : busqueda.trim();
        return ResponseEntity.ok(inventarioService.listarStockPaginado(familiaId, busquedaNormalizada, pageable));
    }

    // Fase 3: consulta puntual usada por VentaFormDialog al seleccionar una presentacion (sin bloqueo,
    // informativa -- la autoridad real de stock suficiente ocurre en POST /api/ventas).
    @Operation(
            summary = "Consultar el stock disponible de una presentación puntual",
            description = "Informativo, sin bloqueo -- la validación autoritativa de stock suficiente ocurre "
                    + "en POST /api/ventas al confirmar, con su propio bloqueo pesimista."
    )
    @ApiResponse(responseCode = "200", description = "Stock de la presentación", content = @Content(schema = @Schema(implementation = StockPresentacionResponse.class)))
    @GetMapping("/stock/presentaciones/{presentacionId}")
    public ResponseEntity<StockPresentacionResponse> stockDePresentacion(
            @Parameter(description = "Id de la presentación") @PathVariable UUID presentacionId) {
        return ResponseEntity.ok(inventarioService.obtenerStockDePresentacion(presentacionId));
    }

    // Fase 2 (solo web): existencias de una presentacion separando lo fisico de lo vendible, con el detalle
    // por lote (FEFO). El formulario de venta lo usa para mostrar el stock VENDIBLE y la vista de lotes de
    // Inventario para mostrar fechas de vencimiento. Informativo, sin bloqueo.
    @Operation(
            summary = "Existencias de una presentación: físico, vendible, vencido y por lote",
            description = "Para un producto con control de vencimiento: stockFisico = stockVendible + stockVencido + "
                    + "stockSinTrazabilidad, y `lotes` lista los lotes con saldo en orden FEFO (vence antes primero). El día de "
                    + "vencimiento es inclusive (diasParaVencer = 0 sigue siendo vendible). Sin control: stockVendible = stockFisico "
                    + "y `lotes` vacío. La validación autoritativa ocurre en POST /api/ventas."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Existencias de la presentación", content = @Content(schema = @Schema(implementation = ExistenciasPresentacionResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe la presentación")
    })
    @GetMapping("/presentaciones/{presentacionId}/existencias")
    public ResponseEntity<ExistenciasPresentacionResponse> existenciasDePresentacion(
            @Parameter(description = "Id de la presentación") @PathVariable UUID presentacionId) {
        return ResponseEntity.ok(inventarioService.obtenerExistencias(presentacionId));
    }

    // Refactor de Produccion, fase 3: pensado para que el formulario de "Registrar producción" muestre
    // "Leche disponible" antes de que el usuario indique cuanta usar. Lectura informativa -- la validacion
    // autoritativa de saldo suficiente (con su propio bloqueo pesimista sobre las recepciones candidatas)
    // ocurre en POST /api/lotes-produccion, no aqui.
    @Operation(
            summary = "Consultar el saldo de leche disponible para producir",
            description = "Calculado como SUM(cantidad) del libro mayor de movimientos de leche -- no existe "
                    + "una tabla de saldo mutable. Un unico pool de planta (no se agrupa por unidad ni por "
                    + "producto): litrosPlanta de cualquier recepcion conciliada lo alimenta por igual."
    )
    @ApiResponse(responseCode = "200", description = "Saldo de leche disponible", content = @Content(schema = @Schema(implementation = SaldoLecheResponse.class)))
    @GetMapping("/leche/saldo")
    public ResponseEntity<SaldoLecheResponse> saldoLeche() {
        return ResponseEntity.ok(new SaldoLecheResponse(inventarioLecheService.obtenerSaldoDisponibleLitros()));
    }

    // Ajustar stock (V14__ajuste_inventario.sql): corrige diferencias encontradas al contar fisicamente
    // (perdidas, roturas, errores de conteo). Nunca devuelve ni consume leche, nunca reescribe como se
    // produjo -- solo un movimiento nuevo en el libro mayor con motivo/usuario/fecha. Gate mas estricto que
    // el resto del controlador (solo ADMIN), mismo criterio que las correcciones de
    // RegistroAcopioController (mas restrictivo que el nivel de clase).
    @Operation(
            summary = "Ajustar el stock de una presentación por conteo físico",
            description = "Registra la diferencia entre el stock del sistema y un conteo físico real como un "
                    + "movimiento AJUSTE en el libro mayor -- nunca sobrescribe el saldo ni toca leche. "
                    + "`cantidadFisica` es el conteo real (no un delta); el backend calcula la diferencia "
                    + "contra el stock actual bajo el mismo bloqueo pesimista que usan ventas/producción.",
            tags = "Ajustes de Inventario"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Ajuste registrado", content = @Content(schema = @Schema(implementation = AjusteInventarioResponse.class))),
            @ApiResponse(responseCode = "400", description = "El conteo físico coincide con el stock del sistema (nada que ajustar), o datos inválidos"),
            @ApiResponse(responseCode = "404", description = "No existe una presentación con ese id")
    })
    @PostMapping("/ajustes")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AjusteInventarioResponse> crearAjuste(@RequestBody @Valid AjusteInventarioRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ajusteInventarioService.crear(dto));
    }

    @Operation(summary = "Historial de ajustes de stock de una presentación (paginado)", description = "Orden descendente "
            + "por fecha, desempate por id. page empieza en 0; size se acota a 1..100.", tags = "Ajustes de Inventario")
    @ApiResponse(responseCode = "200", description = "Página del historial de ajustes", content = @Content(schema = @Schema(implementation = PaginaResponse.class)))
    @GetMapping("/ajustes")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PaginaResponse<AjusteInventarioResponse>> listarAjustes(
            @Parameter(description = "Id de la presentación") @RequestParam UUID presentacionId,
            @Parameter(description = "Número de página, empieza en 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 100)") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PaginaResponse.de(ajusteInventarioService.listarPorPresentacion(presentacionId, Paginacion.de(page, size))));
    }
}
