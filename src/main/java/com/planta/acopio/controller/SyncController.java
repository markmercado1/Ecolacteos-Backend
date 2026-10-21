package com.planta.acopio.controller;

import com.planta.acopio.dto.*;
import com.planta.acopio.service.SyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

// Cada endpoint de subida batch replica exactamente la misma restriccion de
// rol que su equivalente sincrono (RegistroAcopioController, AnalisisCalidadController,
// LoteProduccionController, VentaController) -- CORE_DESIGN.md §15. Sin esto,
// un JWT de un rol podria subir datos de otro modulo via /sync/*.
@Tag(name = "Sincronización Offline", description = "Subida en lote de lo capturado sin señal, y descarga de catálogos. Consumido por la app móvil offline-first.")
@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    @Operation(
            summary = "Subir lote de registros de acopio",
            description = "Rol requerido: ACOPIADOR o ADMIN. Cada ítem se procesa con el mismo flujo "
                    + "idempotente de POST /api/registros-acopio. Sin `@Valid` a nivel de controller a propósito: "
                    + "la validación es manual dentro del service para que un ítem inválido se reporte como "
                    + "error individual en `errores[]` sin abortar el resto del lote — nunca todo-o-nada."
    )
    @ApiResponse(responseCode = "200", description = "Resultado del lote (confirmados + errores, siempre 200 aunque haya ítems fallidos)",
            content = @Content(schema = @Schema(implementation = SyncResultResponse.class)))
    @PostMapping("/registros-acopio")
    @PreAuthorize("hasAnyRole('ACOPIADOR','ADMIN')")
    public ResponseEntity<SyncResultResponse> subirRegistrosAcopio(@RequestBody List<RegistroAcopioDTO> lote) {
        return ResponseEntity.ok(syncService.procesarLoteRegistrosAcopio(lote));
    }

    @Operation(
            summary = "Subir lote de jornadas (creación)",
            description = "Rol requerido: ACOPIADOR o ADMIN. Idempotente por `uuidCliente`. Si `estado` viaja "
                    + "ya como `REGRESANDO_A_PLANTA` o `CANCELADA` (recorrido completo capturado offline), la "
                    + "jornada se crea directamente en ese estado, sin pasar por `EN_RECOLECCION` de cara al "
                    + "servidor. Un conflicto (ya existe una jornada activa para este acopiador o vehículo) se "
                    + "reporta como error individual del ítem (409 a nivel de negocio, 200 a nivel de lote)."
    )
    @ApiResponse(responseCode = "200", description = "Resultado del lote", content = @Content(schema = @Schema(implementation = SyncResultResponse.class)))
    @PostMapping("/jornadas")
    @PreAuthorize("hasAnyRole('ACOPIADOR','ADMIN')")
    public ResponseEntity<SyncResultResponse> subirJornadas(@RequestBody List<JornadaDTO> lote) {
        return ResponseEntity.ok(syncService.procesarLoteJornadas(lote));
    }

    @Operation(
            summary = "Subir lote de transiciones de estado de jornada",
            description = "Rol requerido: ACOPIADOR o ADMIN. Cada ítem identifica la jornada por su "
                    + "`uuidCliente` (no por id de servidor) y aplica FINALIZAR (EN_RECOLECCION → "
                    + "REGRESANDO_A_PLANTA) o CANCELAR (EN_RECOLECCION → CANCELADA, solo si todavía no tiene "
                    + "acopios asociados). Idempotente: reenviar la misma transición cuando el estado actual "
                    + "ya es el destino no falla, no la vuelve a aplicar."
    )
    @ApiResponse(responseCode = "200", description = "Resultado del lote", content = @Content(schema = @Schema(implementation = SyncResultResponse.class)))
    @PostMapping("/jornadas/estado")
    @PreAuthorize("hasAnyRole('ACOPIADOR','ADMIN')")
    public ResponseEntity<SyncResultResponse> subirJornadaEstados(@RequestBody List<JornadaEstadoRequest> lote) {
        return ResponseEntity.ok(syncService.procesarLoteJornadaEstados(lote));
    }

    @Operation(
            summary = "Subir lote de recepciones vinculadas a jornada",
            description = "Rol requerido: RECEPCION o ADMIN. Idempotente por `uuidCliente`. Cada ítem debe "
                    + "referenciar una jornada existente en estado REGRESANDO_A_PLANTA; el backend calcula "
                    + "los litros esperados sumando sus RegistroAcopio asociados (nunca un total editable "
                    + "enviado por el móvil) y, en la misma transacción, marca la jornada como RECIBIDA. Un "
                    + "conflicto (jornada ya recibida, o estado inválido) se reporta como error individual del ítem."
    )
    @ApiResponse(responseCode = "200", description = "Resultado del lote", content = @Content(schema = @Schema(implementation = SyncResultResponse.class)))
    @PostMapping("/recepciones-jornada")
    @PreAuthorize("hasAnyRole('RECEPCION','ADMIN')")
    public ResponseEntity<SyncResultResponse> subirRecepcionesJornada(@RequestBody List<RecepcionJornadaRequest> lote) {
        return ResponseEntity.ok(syncService.procesarLoteRecepcionesJornada(lote));
    }

    @Operation(
            summary = "Subir lote de puntos GPS de recorrido",
            description = "Rol requerido: ACOPIADOR o ADMIN. Idempotente por `uuidCliente`. `jornadaId` ya es "
                    + "el id de servidor de la jornada, resuelto por el motor de sync del móvil antes de "
                    + "enviar (mismo criterio que `jornadaId` en el lote de registros de acopio) -- un punto "
                    + "nunca se sincroniza antes que su jornada. Aislamiento por usuario: la jornada indicada "
                    + "debe pertenecer al acopiador autenticado."
    )
    @ApiResponse(responseCode = "200", description = "Resultado del lote", content = @Content(schema = @Schema(implementation = SyncResultResponse.class)))
    @PostMapping("/puntos-gps")
    @PreAuthorize("hasAnyRole('ACOPIADOR','ADMIN')")
    public ResponseEntity<SyncResultResponse> subirPuntosGps(@RequestBody List<PuntoGpsJornadaDTO> lote) {
        return ResponseEntity.ok(syncService.procesarLotePuntosGps(lote));
    }

    @Operation(
            summary = "Subir lote de análisis de calidad",
            description = "Rol requerido: CALIDAD o ADMIN. Mismo flujo tolerante a fallos parciales que el resto de los endpoints de sync."
    )
    @ApiResponse(responseCode = "200", description = "Resultado del lote", content = @Content(schema = @Schema(implementation = SyncResultResponse.class)))
    @PostMapping("/analisis-calidad")
    @PreAuthorize("hasAnyRole('CALIDAD','ADMIN')")
    public ResponseEntity<SyncResultResponse> subirAnalisisCalidad(@RequestBody List<AnalisisCalidadRequest> lote) {
        return ResponseEntity.ok(syncService.procesarLoteAnalisisCalidad(lote));
    }

    @Operation(
            summary = "Subir lote de lotes de producción",
            description = "Rol requerido: PRODUCCION o ADMIN. Mismo flujo tolerante a fallos parciales que el resto de los endpoints de sync."
    )
    @ApiResponse(responseCode = "200", description = "Resultado del lote", content = @Content(schema = @Schema(implementation = SyncResultResponse.class)))
    @PostMapping("/lotes-produccion")
    @PreAuthorize("hasAnyRole('PRODUCCION','ADMIN')")
    public ResponseEntity<SyncResultResponse> subirLotesProduccion(@RequestBody List<CrearLoteRequest> lote) {
        return ResponseEntity.ok(syncService.procesarLoteLotesProduccion(lote));
    }

    @Operation(
            summary = "Subir lote de ventas",
            description = "Rol requerido: VENTAS o ADMIN. Mismo flujo tolerante a fallos parciales que el resto de los endpoints de sync."
    )
    @ApiResponse(responseCode = "200", description = "Resultado del lote", content = @Content(schema = @Schema(implementation = SyncResultResponse.class)))
    @PostMapping("/ventas")
    @PreAuthorize("hasAnyRole('VENTAS','ADMIN')")
    public ResponseEntity<SyncResultResponse> subirVentas(@RequestBody List<VentaRequest> lote) {
        return ResponseEntity.ok(syncService.procesarLoteVentas(lote));
    }

    // Descarga incremental de catalogos, precio vigente, comunicados y
    // predicciones (CORE_DESIGN.md §12).
    @Operation(
            summary = "Descargar catálogos y datos de referencia",
            description = "Cualquier rol autenticado (todo dispositivo offline necesita catálogos, sin "
                    + "importar el rol de quien lo usa). Devuelve de una vez: proveedores (sin datos "
                    + "personales), precio vigente, comunicados, predicciones por proveedor, motivos de "
                    + "observación, tipos de queso y unidades. El parámetro `desde` se recibe para uso futuro "
                    + "de filtrado incremental; en v1 siempre devuelve el estado completo de cada catálogo "
                    + "(volumen bajo, no justifica paginación)."
    )
    @ApiResponse(responseCode = "200", description = "Catálogos vigentes", content = @Content(schema = @Schema(implementation = CambiosResponse.class)))
    @GetMapping("/cambios")
    public ResponseEntity<CambiosResponse> descargarCambios(
            @Parameter(description = "Reservado para filtrado incremental futuro; sin efecto en v1") @RequestParam(required = false) Instant desde) {
        return ResponseEntity.ok(syncService.obtenerCambiosDesde(desde));
    }
}
