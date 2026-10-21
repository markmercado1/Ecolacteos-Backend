package com.planta.acopio.controller;

import com.planta.acopio.dto.CrearLoteRequest;
import com.planta.acopio.dto.DisponibilidadProduccionResponse;
import com.planta.acopio.dto.FinalizarLoteRequest;
import com.planta.acopio.dto.LoteProduccionResponse;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.dto.PlanificarLoteRequest;
import com.planta.acopio.entity.EstadoLote;
import com.planta.acopio.service.LoteProduccionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@Tag(name = "Lotes de Producción", description = "Producción de queso a partir de leche acopiada. Rol requerido: PRODUCCION o ADMIN.")
@RestController
@RequestMapping("/api/lotes-produccion")
@PreAuthorize("hasAnyRole('PRODUCCION','ADMIN')")
public class LoteProduccionController {

    private final LoteProduccionService loteProduccionService;

    public LoteProduccionController(LoteProduccionService loteProduccionService) {
        this.loteProduccionService = loteProduccionService;
    }

    @Operation(
            summary = "Crear un lote de producción",
            description = "Refactor fase 2 (nuevo modelo funcional): consume LECHE DISPONIBLE (saldo de "
                    + "recepciones de planta conciliadas), no registros de acopio directamente. `litrosUsados` "
                    + "se descuenta automáticamente por FIFO de las recepciones con saldo remanente, más "
                    + "antiguas primero — el cliente no elige de cuáles. Rechaza si el saldo no alcanza. "
                    + "Idempotente por `uuidCliente`: reenviar el mismo valor tras un intento anterior ya "
                    + "confirmado devuelve el lote existente sin duplicar. En una carrera real (dos peticiones "
                    + "concurrentes con el mismo `uuidCliente`) responde 409 — reintentar con el mismo "
                    + "`uuidCliente` en una petición nueva recupera el lote que sí se creó. "
                    + "Calcula `rendimientoPct` real = unidadesObtenidas / litrosUsados × 100, y "
                    + "`unidadesEsperadas`/`rendimientoEsperadoPctSnapshot` a partir del rendimiento esperado "
                    + "del tipo de queso al momento de producir (snapshot, no se recalcula después)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Lote creado (o el existente, si el uuidCliente ya se había recibido)",
                    content = @Content(schema = @Schema(implementation = LoteProduccionResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, o leche disponible insuficiente para litrosUsados"),
            @ApiResponse(responseCode = "404", description = "El productoId indicado no existe"),
            @ApiResponse(responseCode = "409", description = "Carrera concurrente con el mismo uuidCliente — reintentar con el mismo valor devuelve el lote ya creado")
    })
    @PostMapping
    public ResponseEntity<LoteProduccionResponse> crear(@RequestBody @Valid CrearLoteRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(loteProduccionService.crear(dto));
    }

    // Auditoria CRUD (prioridad ALTA): el modulo no tenia ningun listado --
    // PRODUCCION no podia revisar el historial de lotes desde el frontend.
    // Bloque 5 (confirmado): filtros opcionales combinables -- sin parametros
    // se comporta exactamente igual que antes (no rompe al frontend actual).
    @Operation(
            summary = "Listar lotes de producción (paginado)",
            description = "`fecha`, `productoId` y `estado` son opcionales y combinables. `page` (default 0) y "
                    + "`size` (default 20, máx. 100) controlan la paginación real."
    )
    @ApiResponse(responseCode = "200", description = "Página de lotes encontrados", content = @Content(schema = @Schema(implementation = PaginaResponse.class)))
    @GetMapping
    public ResponseEntity<PaginaResponse<LoteProduccionResponse>> listar(
            @Parameter(description = "Filtrar por fecha exacta") @RequestParam(required = false) LocalDate fecha,
            @Parameter(description = "Filtrar por producto") @RequestParam(required = false) UUID productoId,
            @Parameter(description = "Filtrar por estado: PLANIFICADA, EN_PRODUCCION, FINALIZADA o CANCELADA") @RequestParam(required = false) EstadoLote estado,
            @Parameter(description = "Número de página, empieza en 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 100)") @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return ResponseEntity.ok(PaginaResponse.de(loteProduccionService.buscar(fecha, productoId, estado, pageable)));
    }

    // --- V18: producción planificada desde receta ---

    @Operation(
            summary = "Planificar una producción desde una receta",
            description = "Crea un lote PLANIFICADA: congela litros, insumos escalados (factor = litros / litrosBase "
                    + "de la receta) y la cantidad ESPERADA. No consume leche ni insumos y no registra producto "
                    + "terminado. Se puede planificar aunque hoy falte stock; iniciar sí lo exige. Idempotente por "
                    + "`uuidCliente`."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Lote planificado (o el existente con el mismo uuidCliente y datos)",
                    content = @Content(schema = @Schema(implementation = LoteProduccionResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos o un insumo resulta 0 al escalar"),
            @ApiResponse(responseCode = "404", description = "La receta no existe"),
            @ApiResponse(responseCode = "409", description = "Mismo uuidCliente con datos distintos"),
            @ApiResponse(responseCode = "422", description = "Receta, producto, presentación o insumo desactivado")
    })
    @PostMapping("/planificar")
    public ResponseEntity<LoteProduccionResponse> planificar(@RequestBody @Valid PlanificarLoteRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(loteProduccionService.planificar(dto));
    }

    @Operation(
            summary = "Validar el stock de un lote planificado",
            description = "Compara el plan del lote (leche + insumos) contra el stock actual, línea por línea, "
                    + "con faltantes. Informativo, sin bloqueo: la autoridad es `iniciar`."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Disponibilidad calculada",
                    content = @Content(schema = @Schema(implementation = DisponibilidadProduccionResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe un lote con ese id")
    })
    @GetMapping("/{id}/disponibilidad")
    public ResponseEntity<DisponibilidadProduccionResponse> disponibilidad(@PathVariable UUID id) {
        return ResponseEntity.ok(loteProduccionService.disponibilidad(id));
    }

    @Operation(
            summary = "Iniciar un lote planificado",
            description = "PLANIFICADA → EN_PRODUCCION. Confirma el consumo definitivo: descuenta la leche (FIFO "
                    + "sobre recepciones, con bloqueo) y los insumos del plan (descuento atómico condicionado). "
                    + "Si falta cualquier stock responde 400 con todos los faltantes y no consume nada. "
                    + "Reintentar sobre un lote ya EN_PRODUCCION devuelve el lote sin volver a consumir."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lote en producción",
                    content = @Content(schema = @Schema(implementation = LoteProduccionResponse.class))),
            @ApiResponse(responseCode = "400", description = "Stock insuficiente de leche o insumos"),
            @ApiResponse(responseCode = "404", description = "No existe un lote con ese id"),
            @ApiResponse(responseCode = "409", description = "El lote no está PLANIFICADA"),
            @ApiResponse(responseCode = "422", description = "Un insumo del plan está desactivado")
    })
    @PostMapping("/{id}/iniciar")
    public ResponseEntity<LoteProduccionResponse> iniciar(@PathVariable UUID id) {
        return ResponseEntity.ok(loteProduccionService.iniciar(id));
    }

    @Operation(
            summary = "Finalizar un lote en producción",
            description = "EN_PRODUCCION → FINALIZADA. Registra la salida REAL (`detalles`, vacío = nada "
                    + "aprovechable) y recién aquí ingresa el producto terminado al inventario. La leche e insumos "
                    + "utilizados son los consumidos al iniciar. Mismas reglas de presentaciones y rango de "
                    + "rendimiento que el registro directo. Un reintento con el mismo resultado devuelve el lote "
                    + "sin duplicar inventario; con otro resultado, 409."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lote finalizado",
                    content = @Content(schema = @Schema(implementation = LoteProduccionResponse.class))),
            @ApiResponse(responseCode = "400", description = "Salidas inválidas o fuera de rango sin motivo"),
            @ApiResponse(responseCode = "404", description = "Lote o presentación inexistente"),
            @ApiResponse(responseCode = "409", description = "El lote no está EN_PRODUCCION o ya fue finalizado con otro resultado")
    })
    @PostMapping("/{id}/finalizar")
    public ResponseEntity<LoteProduccionResponse> finalizar(@PathVariable UUID id, @RequestBody @Valid FinalizarLoteRequest dto) {
        return ResponseEntity.ok(loteProduccionService.finalizar(id, dto));
    }

    @Operation(
            summary = "Cancelar un lote planificado",
            description = "PLANIFICADA → CANCELADA. Como todavía no se consumió nada, no hay stock que revertir. "
                    + "Un lote EN_PRODUCCION no se cancela (se finaliza registrando lo obtenido)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lote cancelado",
                    content = @Content(schema = @Schema(implementation = LoteProduccionResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe un lote con ese id"),
            @ApiResponse(responseCode = "409", description = "El lote no está PLANIFICADA")
    })
    @PostMapping("/{id}/cancelar")
    public ResponseEntity<LoteProduccionResponse> cancelar(@PathVariable UUID id) {
        return ResponseEntity.ok(loteProduccionService.cancelar(id));
    }

    @Operation(summary = "Obtener el detalle de un lote")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lote encontrado", content = @Content(schema = @Schema(implementation = LoteProduccionResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe un lote con ese id")
    })
    @GetMapping("/{id}")
    public ResponseEntity<LoteProduccionResponse> obtener(@Parameter(description = "Id del lote") @PathVariable UUID id) {
        return ResponseEntity.ok(loteProduccionService.obtener(id));
    }
}
