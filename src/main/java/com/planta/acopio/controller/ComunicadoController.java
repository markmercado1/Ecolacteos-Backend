package com.planta.acopio.controller;

import com.planta.acopio.dto.ComunicadoConfirmacionResponse;
import com.planta.acopio.dto.ComunicadoResponse;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.dto.ConfirmarComunicadoRequest;
import com.planta.acopio.dto.CrearComunicadoRequest;
import com.planta.acopio.service.ComunicadoService;
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

import java.util.List;
import java.util.UUID;

@Tag(name = "Comunicados", description = "Mensajes dirigidos a una o más zonas, confirmados por el acopiador en nombre del proveedor.")
@RestController
@RequestMapping("/api/comunicados")
public class ComunicadoController {

    private final ComunicadoService comunicadoService;

    public ComunicadoController(ComunicadoService comunicadoService) {
        this.comunicadoService = comunicadoService;
    }

    @Operation(summary = "Crear comunicado", description = "Rol requerido: ADMIN. Dirigido a una o varias zonas (`zonaIds`).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Comunicado creado", content = @Content(schema = @Schema(implementation = ComunicadoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos (mensaje vacío o sin zonas)"),
            @ApiResponse(responseCode = "404", description = "Alguna de las zonaIds indicadas no existe")
    })
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ComunicadoResponse> crear(@RequestBody @Valid CrearComunicadoRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(comunicadoService.crear(dto));
    }

    // Auditoria de bajo riesgo (Fase 1): listado global -- antes solo se
    // podia consultar comunicado por zona, uno a la vez.
    @Operation(summary = "Listar todos los comunicados (paginado)", description = "Rol requerido: ADMIN. Todas las zonas, "
            + "orden descendente por fecha (desempate por id). page empieza en 0; size se acota a 1..100.")
    @ApiResponse(responseCode = "200", description = "Página de comunicados", content = @Content(schema = @Schema(implementation = PaginaResponse.class)))
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PaginaResponse<ComunicadoResponse>> listar(
            @Parameter(description = "Número de página, empieza en 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 100)") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PaginaResponse.de(comunicadoService.listar(Paginacion.de(page, size))));
    }

    @Operation(
            summary = "Listar comunicados de una zona",
            description = "Sin restricción de rol adicional (cualquier usuario autenticado) — decisión "
                    + "explícita de diseño, no un descuido: todos los roles operativos pueden necesitar leer "
                    + "comunicados. Orden descendente por fecha."
    )
    @ApiResponse(responseCode = "200", description = "Comunicados de la zona", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ComunicadoResponse.class))))
    @GetMapping("/zona/{zonaId}")
    public ResponseEntity<List<ComunicadoResponse>> porZona(@Parameter(description = "Id de la zona") @PathVariable UUID zonaId) {
        return ResponseEntity.ok(comunicadoService.listarPorZona(zonaId));
    }

    // Segunda auditoria (flujos end-to-end §14-F): antes devolvia void -- el
    // unico POST de creacion de todo el API que no devolvia el recurso
    // creado. Se alinea con el resto (ej. POST /correcciones) devolviendo la
    // confirmacion creada.
    @Operation(
            summary = "Confirmar entrega de un comunicado",
            description = "Rol requerido: ACOPIADOR o ADMIN. El acopiador confirma que transmitió el "
                    + "comunicado a un proveedor en persona. El `acopiador` no viaja en el body: se resuelve "
                    + "del usuario autenticado en el JWT."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Confirmación registrada", content = @Content(schema = @Schema(implementation = ComunicadoConfirmacionResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "El comunicado o el proveedor indicados no existen")
    })
    @PostMapping("/{id}/confirmaciones")
    @PreAuthorize("hasAnyRole('ACOPIADOR','ADMIN')")
    public ResponseEntity<ComunicadoConfirmacionResponse> confirmar(@Parameter(description = "Id del comunicado") @PathVariable UUID id,
                                                                       @RequestBody @Valid ConfirmarComunicadoRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(comunicadoService.confirmar(id, dto));
    }

    // Auditoria CRUD (prioridad MEDIA): ComunicadoConfirmacionRepository.findByComunicadoId
    // ya existia pero no se exponia -- ADMIN no podia ver quien confirmo un comunicado.
    @Operation(summary = "Ver quién confirmó un comunicado", description = "Rol requerido: ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Confirmaciones del comunicado", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ComunicadoConfirmacionResponse.class)))),
            @ApiResponse(responseCode = "404", description = "No existe un comunicado con ese id")
    })
    @GetMapping("/{id}/confirmaciones")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<ComunicadoConfirmacionResponse>> confirmaciones(@Parameter(description = "Id del comunicado") @PathVariable UUID id) {
        return ResponseEntity.ok(comunicadoService.listarConfirmaciones(id));
    }
}
