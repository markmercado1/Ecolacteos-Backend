package com.planta.acopio.controller;

import com.planta.acopio.dto.AcopioPorJornadaResponse;
import com.planta.acopio.dto.CorreccionRegistroRequest;
import com.planta.acopio.dto.CorreccionRegistroResponse;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.dto.RegistroAcopioDTO;
import com.planta.acopio.dto.RegistroAcopioResponse;
import com.planta.acopio.dto.RegistroAcopioResumenResponse;
import com.planta.acopio.dto.TotalAcopioProveedorResponse;
import com.planta.acopio.service.CorreccionRegistroService;
import com.planta.acopio.service.RegistroAcopioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/registros-acopio")
public class RegistroAcopioController {

    private final RegistroAcopioService registroAcopioService;
    private final CorreccionRegistroService correccionRegistroService;

    public RegistroAcopioController(RegistroAcopioService registroAcopioService, CorreccionRegistroService correccionRegistroService) {
        this.registroAcopioService = registroAcopioService;
        this.correccionRegistroService = correccionRegistroService;
    }

    @Operation(
            summary = "Registrar una entrega de leche",
            description = "Rol requerido: ACOPIADOR o ADMIN. Idempotente por `uuidCliente` (generado en el "
                    + "teléfono): reenviar el mismo `uuidCliente` nunca duplica, devuelve el registro ya "
                    + "existente con 201. El campo `acopiador` no viaja en el body: se resuelve del usuario "
                    + "autenticado en el JWT. `fechaHora` debe estar dentro de tolerancia (-30 días / +5 min).",
            tags = "Registro de Acopio"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Registro creado (o el existente, si el uuidCliente ya se había recibido)",
                    content = @Content(schema = @Schema(implementation = RegistroAcopioResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, o fechaHora fuera de tolerancia"),
            @ApiResponse(responseCode = "404", description = "El proveedor, la unidad o el motivoObservacionId indicados no existen"),
            @ApiResponse(responseCode = "422", description = "El proveedor está inactivo, o el motivo de observación está desactivado")
    })
    @PostMapping
    @PreAuthorize("hasAnyRole('ACOPIADOR','ADMIN')")
    public ResponseEntity<RegistroAcopioResponse> crear(@RequestBody @Valid RegistroAcopioDTO dto) {
        var registro = registroAcopioService.registrarOIgnorarSiDuplicado(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(registro);
    }

    // Bloque 2 (confirmado): bandeja operativa -- objetivo principal que
    // CALIDAD obtenga de un solo llamado los registros del dia sin analisis
    // (sinAnalisis=true), en vez de descargarlos uno por uno. Sin paginacion
    // en este bloque (decision explicita). Mismos roles que el resto de
    // lecturas de este recurso.
    //
    // Fase 4 PRODUCCION (docs/PROMPTS_FINALIZAR_MOVIL.md): agregado PRODUCCION
    // -- necesita seleccionar registros de acopio de distintos proveedores
    // para armar un lote (CrearLoteRequest.registroAcopioIds) y hoy recibia
    // 403 en las tres lecturas de este controller. Cambio minimo: solo lectura
    // (el POST/las correcciones siguen sin PRODUCCION), y RegistroAcopioResponse
    // ya no trae DNI/telefono del proveedor (solo nombre) -- no hace falta un
    // DTO nuevo para no exponer datos personales. `disponibleParaLote` (nuevo
    // campo) es la forma verificable de saber, antes del POST, si un registro
    // ya esta vinculado a otro lote (la regla real la sigue aplicando
    // LoteProduccionService.crear(), esto es solo para no ofrecerlo en la UI).
    @Operation(
            summary = "Buscar registros de acopio con filtros",
            description = "Rol requerido: ACOPIADOR, ADMIN, CALIDAD o PRODUCCION. Todos los filtros son "
                    + "opcionales y combinables: `fecha` (día calendario completo), `zonaId` (vía "
                    + "proveedor.zonaActual), `sinAnalisis=true` (registros que todavía no tienen ningún "
                    + "AnalisisCalidad asociado). Sin filtros devuelve todos los registros. Orden descendente "
                    + "por fecha. Cada registro incluye `disponibleParaLote`: `false` si ya pertenece a un "
                    + "LoteProduccion (v1: un registro pertenece a un único lote) -- útil para que PRODUCCION "
                    + "no ofrezca seleccionar uno ya usado, aunque la regla real la aplica POST /api/lotes-produccion.",
            tags = "Registro de Acopio"
    )
    @ApiResponse(responseCode = "200", description = "Registros encontrados", content = @Content(array = @ArraySchema(schema = @Schema(implementation = RegistroAcopioResponse.class))))
    @GetMapping
    @PreAuthorize("hasAnyRole('ACOPIADOR','ADMIN','CALIDAD','PRODUCCION')")
    public ResponseEntity<List<RegistroAcopioResponse>> buscar(
            @Parameter(description = "Filtrar por fecha (día calendario completo)") @RequestParam(required = false) LocalDate fecha,
            @Parameter(description = "Filtrar por zona del proveedor") @RequestParam(required = false) UUID zonaId,
            @Parameter(description = "true = solo registros sin ningún análisis de calidad") @RequestParam(required = false) Boolean sinAnalisis) {
        return ResponseEntity.ok(registroAcopioService.buscar(fecha, zonaId, sinAnalisis));
    }

    // ---------------------------------------------------------------------
    // Paginacion pendiente (web). GET /api/registros-acopio y
    // GET /api/registros-acopio/proveedor/{id} siguen devolviendo la lista
    // completa: son contrato de la app movil (y de la bandeja de CALIDAD).
    // Mismos roles y mismos filtros que el listado original.

    @Operation(
            summary = "Buscar registros de acopio (paginado)",
            description = "Rol requerido: ACOPIADOR, ADMIN, CALIDAD o PRODUCCION. Mismos filtros que GET "
                    + "/api/registros-acopio más `busqueda` (nombre del proveedor o placa, sin distinguir "
                    + "mayúsculas), resuelta en el servidor antes de paginar. Orden: fechaHora descendente, "
                    + "desempate por id. page empieza en 0; size se acota a 1..100.",
            tags = "Registro de Acopio"
    )
    @ApiResponse(responseCode = "200", description = "Página de registros", content = @Content(schema = @Schema(implementation = PaginaResponse.class)))
    @GetMapping("/paginado")
    @PreAuthorize("hasAnyRole('ACOPIADOR','ADMIN','CALIDAD','PRODUCCION')")
    public ResponseEntity<PaginaResponse<RegistroAcopioResponse>> buscarPaginado(
            @Parameter(description = "Filtrar por fecha (día calendario completo)") @RequestParam(required = false) LocalDate fecha,
            @Parameter(description = "Filtrar por zona del proveedor") @RequestParam(required = false) UUID zonaId,
            @Parameter(description = "true = solo registros sin ningún análisis de calidad") @RequestParam(required = false) Boolean sinAnalisis,
            @Parameter(description = "Texto a buscar en nombre del proveedor o placa") @RequestParam(required = false) String busqueda,
            @Parameter(description = "Número de página, empieza en 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 100)") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PaginaResponse.de(registroAcopioService.buscarPaginado(
                fecha, zonaId, sinAnalisis, busqueda, Paginacion.de(page, size))));
    }

    @Operation(
            summary = "Registros de acopio agrupados por jornada (paginado)",
            description = "Rol requerido: ACOPIADOR, ADMIN, CALIDAD o PRODUCCION. Una fila por jornada (o por "
                    + "registro sin jornada), agrupada en el servidor sobre todos los registros del filtro y "
                    + "después paginada, para que una jornada nunca quede partida entre páginas. Mismos "
                    + "filtros que /paginado. Orden: primer acopio de la jornada descendente.",
            tags = "Registro de Acopio"
    )
    @ApiResponse(responseCode = "200", description = "Página de jornadas", content = @Content(schema = @Schema(implementation = PaginaResponse.class)))
    @GetMapping("/por-jornada")
    @PreAuthorize("hasAnyRole('ACOPIADOR','ADMIN','CALIDAD','PRODUCCION')")
    public ResponseEntity<PaginaResponse<AcopioPorJornadaResponse>> porJornada(
            @Parameter(description = "Filtrar por fecha (día calendario completo)") @RequestParam(required = false) LocalDate fecha,
            @Parameter(description = "Filtrar por zona del proveedor") @RequestParam(required = false) UUID zonaId,
            @Parameter(description = "true = solo registros sin ningún análisis de calidad") @RequestParam(required = false) Boolean sinAnalisis,
            @Parameter(description = "Texto a buscar en nombre del proveedor o placa") @RequestParam(required = false) String busqueda,
            @Parameter(description = "Número de página, empieza en 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 100)") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PaginaResponse.de(registroAcopioService.agruparPorJornada(
                fecha, zonaId, sinAnalisis, busqueda, Paginacion.de(page, size))));
    }

    @Operation(
            summary = "Total de litros por proveedor del filtro",
            description = "Rol requerido: ACOPIADOR, ADMIN, CALIDAD o PRODUCCION. Agregado global de todos los "
                    + "registros que cumplen fecha/zona/sinAnalisis (no depende de la página visible). Una fila "
                    + "por proveedor con entregas, orden por litros descendente.",
            tags = "Registro de Acopio"
    )
    @ApiResponse(responseCode = "200", description = "Totales por proveedor", content = @Content(array = @ArraySchema(schema = @Schema(implementation = TotalAcopioProveedorResponse.class))))
    @GetMapping("/resumen-por-proveedor")
    @PreAuthorize("hasAnyRole('ACOPIADOR','ADMIN','CALIDAD','PRODUCCION')")
    public ResponseEntity<List<TotalAcopioProveedorResponse>> resumenPorProveedor(
            @Parameter(description = "Filtrar por fecha (día calendario completo)") @RequestParam(required = false) LocalDate fecha,
            @Parameter(description = "Filtrar por zona del proveedor") @RequestParam(required = false) UUID zonaId,
            @Parameter(description = "true = solo registros sin ningún análisis de calidad") @RequestParam(required = false) Boolean sinAnalisis) {
        return ResponseEntity.ok(registroAcopioService.totalesPorProveedor(fecha, zonaId, sinAnalisis));
    }

    // Auditoria CRUD (prioridad BAJA): historialPorProveedor solo trae un
    // resumen liviano (RF-TRA-01); faltaba el detalle completo (gps, foto,
    // motivo) de un registro puntual, util antes de anexar una correccion.
    @Operation(
            summary = "Obtener el detalle completo de un registro",
            description = "Rol requerido: ACOPIADOR, ADMIN, CALIDAD o PRODUCCION. Incluye GPS, foto y motivo "
                    + "de observación (el historial por proveedor solo trae un resumen liviano).",
            tags = "Registro de Acopio"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Registro encontrado", content = @Content(schema = @Schema(implementation = RegistroAcopioResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe un registro con ese id")
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ACOPIADOR','ADMIN','CALIDAD','PRODUCCION')")
    public ResponseEntity<RegistroAcopioResponse> obtener(@Parameter(description = "Id del registro de acopio") @PathVariable UUID id) {
        return ResponseEntity.ok(registroAcopioService.obtener(id));
    }

    // RF-TRA-01: el acopiador muestra el historial al proveedor en su propio telefono.
    // Fase 4 PRODUCCION: tambien es el punto de entrada para elegir registros
    // de UN proveedor puntual al armar un lote (BuscarProveedorScreen -> este
    // endpoint, del lado movil). disponibleParaLote sigue el mismo criterio
    // que en buscar()/obtener().
    @Operation(
            summary = "Historial de entregas de un proveedor",
            description = "Rol requerido: ACOPIADOR, ADMIN, CALIDAD o PRODUCCION. Versión resumida "
                    + "(RF-TRA-01): pensada para que el acopiador se la muestre al proveedor en el teléfono, "
                    + "y para que PRODUCCION elija qué entregas de este proveedor entran en un lote. Orden "
                    + "descendente por fecha.",
            tags = "Registro de Acopio"
    )
    @ApiResponse(responseCode = "200", description = "Historial de entregas", content = @Content(array = @ArraySchema(schema = @Schema(implementation = RegistroAcopioResumenResponse.class))))
    @GetMapping("/proveedor/{proveedorId}")
    @PreAuthorize("hasAnyRole('ACOPIADOR','ADMIN','CALIDAD','PRODUCCION')")
    public ResponseEntity<List<RegistroAcopioResumenResponse>> porProveedor(@Parameter(description = "Id del proveedor") @PathVariable UUID proveedorId) {
        return ResponseEntity.ok(registroAcopioService.historialPorProveedor(proveedorId));
    }

    @Operation(
            summary = "Historial de entregas de un proveedor (paginado)",
            description = "Rol requerido: ACOPIADOR, ADMIN, CALIDAD o PRODUCCION. Mismo resumen que "
                    + "/proveedor/{proveedorId}, paginado en el servidor. Orden: fechaHora descendente, "
                    + "desempate por id. page empieza en 0; size se acota a 1..100.",
            tags = "Registro de Acopio"
    )
    @ApiResponse(responseCode = "200", description = "Página del historial", content = @Content(schema = @Schema(implementation = PaginaResponse.class)))
    @GetMapping("/proveedor/{proveedorId}/paginado")
    @PreAuthorize("hasAnyRole('ACOPIADOR','ADMIN','CALIDAD','PRODUCCION')")
    public ResponseEntity<PaginaResponse<RegistroAcopioResumenResponse>> porProveedorPaginado(
            @Parameter(description = "Id del proveedor") @PathVariable UUID proveedorId,
            @Parameter(description = "Número de página, empieza en 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 100)") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PaginaResponse.de(registroAcopioService.historialPorProveedor(proveedorId, Paginacion.de(page, size))));
    }

    // RegistroAcopio es inmutable: esta es la UNICA forma de "cambiarlo" --
    // anexar una correccion con trazabilidad (RNF-03/05), nunca un PUT/PATCH.
    @Operation(
            summary = "Anexar una corrección al registro",
            description = "Rol requerido: CALIDAD o ADMIN. `RegistroAcopio` es inmutable por diseño (RNF-03/05): "
                    + "esta es la ÚNICA forma de \"cambiarlo\" — se anexa una corrección con trazabilidad "
                    + "completa (litros anterior y corregido, motivo, usuario, fecha), nunca se sobreescribe "
                    + "el original. No existe PUT/PATCH sobre este recurso.",
            tags = "Correcciones de Registro"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Corrección anexada", content = @Content(schema = @Schema(implementation = CorreccionRegistroResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "No existe un registro de acopio con ese id")
    })
    @PostMapping("/{id}/correcciones")
    @PreAuthorize("hasAnyRole('CALIDAD','ADMIN')")
    public ResponseEntity<CorreccionRegistroResponse> corregir(@Parameter(description = "Id del registro de acopio") @PathVariable UUID id,
                                                                @RequestBody @Valid CorreccionRegistroRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(correccionRegistroService.anexar(id, dto));
    }

    @Operation(
            summary = "Historial de correcciones de un registro",
            description = "Rol requerido: CALIDAD o ADMIN. Orden descendente por fecha de creación.",
            tags = "Correcciones de Registro"
    )
    @ApiResponse(responseCode = "200", description = "Correcciones del registro", content = @Content(array = @ArraySchema(schema = @Schema(implementation = CorreccionRegistroResponse.class))))
    @GetMapping("/{id}/correcciones")
    @PreAuthorize("hasAnyRole('CALIDAD','ADMIN')")
    public ResponseEntity<List<CorreccionRegistroResponse>> listarCorrecciones(@Parameter(description = "Id del registro de acopio") @PathVariable UUID id) {
        return ResponseEntity.ok(correccionRegistroService.listarPorRegistro(id));
    }
}
