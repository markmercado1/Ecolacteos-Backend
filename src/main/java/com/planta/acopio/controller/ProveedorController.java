package com.planta.acopio.controller;

import com.planta.acopio.dto.HistorialZonaResponse;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.dto.ProveedorAdminResponse;
import com.planta.acopio.dto.ProveedorPublicoResponse;
import com.planta.acopio.dto.ProveedorRequest;
import com.planta.acopio.dto.ProveedorUpdateRequest;
import com.planta.acopio.service.ProveedorService;
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

@Tag(name = "Proveedores", description = "Productores de leche de la zona.")
@RestController
@RequestMapping("/api/proveedores")
public class ProveedorController {

    private final ProveedorService proveedorService;

    public ProveedorController(ProveedorService proveedorService) {
        this.proveedorService = proveedorService;
    }

    @Operation(summary = "Crear proveedor", description = "Rol requerido: ADMIN. El DNI debe ser único; la zona es opcional.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Proveedor creado",
                    content = @Content(schema = @Schema(implementation = ProveedorAdminResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "La zonaActualId indicada no existe"),
            @ApiResponse(responseCode = "409", description = "Ya existe un proveedor con ese DNI")
    })
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProveedorAdminResponse> crear(@RequestBody @Valid ProveedorRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(proveedorService.crear(dto));
    }

    // Segunda auditoria (flujos end-to-end §14-F, RNF-12): restringido a ADMIN.
    // Devuelve ProveedorAdminResponse (incluye dni/telefono, datos personales)
    // -- antes tambien lo veian ACOPIADOR/RECEPCION, contradiciendo RNF-12 y
    // la recomendacion explicita de CORE_DESIGN.md §15 de usar dos DTOs.
    // Bloque 5 (confirmado): filtros opcionales combinables -- sin parametros
    // se comporta exactamente igual que antes (no rompe al frontend actual).
    @Operation(
            summary = "Listar proveedores (completo, paginado)",
            description = "Rol requerido: ADMIN. Incluye datos personales (DNI, teléfono) — para el listado "
                    + "sin datos personales, apto para roles operativos, usar GET /api/proveedores/operativo. "
                    + "`zonaId`, `activo` y `busqueda` (nombre o DNI) son opcionales y combinables, y se aplican antes de paginar. `page` (default 0) y `size` (default 20, "
                    + "máx. 100) controlan la paginación real."
    )
    @ApiResponse(responseCode = "200", description = "Página de proveedores encontrados",
            content = @Content(schema = @Schema(implementation = PaginaResponse.class)))
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PaginaResponse<ProveedorAdminResponse>> listar(
            @Parameter(description = "Filtrar por zona actual (opcional)") @RequestParam(required = false) UUID zonaId,
            @Parameter(description = "Filtrar por estado activo/inactivo (opcional)") @RequestParam(required = false) Boolean activo,
            @Parameter(description = "Buscar por nombre o DNI en todo el padrón (opcional, sin distinguir mayúsculas)") @RequestParam(required = false) String busqueda,
            @Parameter(description = "Número de página, empieza en 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 100)") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PaginaResponse.de(proveedorService.buscar(zonaId, activo, busqueda, Paginacion.de(page, size))));
    }

    // Segunda auditoria (flujos end-to-end §14-F): version operativa --
    // ProveedorPublicoResponse (sin dni/telefono), solo proveedores activos.
    // Es la misma lista que viaja por /api/sync/cambios, expuesta tambien de
    // forma sincrona/puntual. Roles: los tres necesitan descubrir proveedorId
    // en algun flujo propio -- ACOPIADOR (registrar acopio), RECEPCION
    // (GET /api/pagos/proveedor/{id}), CALIDAD (GET /api/registros-acopio/proveedor/{id}
    // y GET /api/innovacion/score|prediccion/{proveedorId}).
    @Operation(
            summary = "Listar proveedores (operativo)",
            description = "Rol requerido: ADMIN, ACOPIADOR, RECEPCION o CALIDAD. Solo proveedores activos, "
                    + "sin DNI ni teléfono (RNF-12). Es la misma lista que viaja por GET /api/sync/cambios."
    )
    @ApiResponse(responseCode = "200", description = "Listado operativo",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ProveedorPublicoResponse.class))))
    @GetMapping("/operativo")
    @PreAuthorize("hasAnyRole('ADMIN','ACOPIADOR','RECEPCION','CALIDAD')")
    public ResponseEntity<List<ProveedorPublicoResponse>> listarOperativo() {
        return ResponseEntity.ok(proveedorService.listarPublico());
    }

    @Operation(
            summary = "Obtener proveedor por id (completo)",
            description = "Rol requerido: ADMIN. Incluye datos personales (DNI, teléfono)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Proveedor encontrado",
                    content = @Content(schema = @Schema(implementation = ProveedorAdminResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe un proveedor con ese id")
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProveedorAdminResponse> obtener(@Parameter(description = "Id del proveedor") @PathVariable UUID id) {
        return ResponseEntity.ok(proveedorService.obtener(id));
    }

    // Auditoria CRUD (prioridad BAJA): ProveedorRepository.findByCodigoQr ya
    // existia sin usarse. CORE_DESIGN.md §1 describe la camara del telefono
    // leyendo el QR del proveedor -- este es el endpoint que resuelve esa
    // lectura a los datos del proveedor en campo.
    // Segunda auditoria (§14-F): devuelve ProveedorPublicoResponse -- quien
    // escanea un QR en campo es ACOPIADOR, no debe recibir dni/telefono.
    @Operation(
            summary = "Buscar proveedor por código QR",
            description = "Rol requerido: ADMIN o ACOPIADOR. Usado por la app móvil al escanear el QR "
                    + "impreso/entregado al proveedor. Devuelve el DTO operativo (sin datos personales)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Proveedor encontrado",
                    content = @Content(schema = @Schema(implementation = ProveedorPublicoResponse.class))),
            @ApiResponse(responseCode = "404", description = "Ningún proveedor tiene ese código QR")
    })
    @GetMapping("/qr/{codigoQr}")
    @PreAuthorize("hasAnyRole('ADMIN','ACOPIADOR')")
    public ResponseEntity<ProveedorPublicoResponse> obtenerPorCodigoQr(
            @Parameter(description = "Código QR único del proveedor") @PathVariable String codigoQr) {
        return ResponseEntity.ok(proveedorService.obtenerPorCodigoQr(codigoQr));
    }

    // Auditoria CRUD (prioridad ALTA): antes la unica "actualizacion" posible
    // era reasignar zona (PUT /{id}/zona, que se mantiene intacto abajo).
    @Operation(
            summary = "Editar proveedor",
            description = "Rol requerido: ADMIN. Edita nombre/DNI/teléfono. Para cambiar la zona usar "
                    + "PUT /{id}/zona, que además cierra el período de historial de zona anterior."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Proveedor actualizado",
                    content = @Content(schema = @Schema(implementation = ProveedorAdminResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "No existe un proveedor con ese id"),
            @ApiResponse(responseCode = "409", description = "Ya existe otro proveedor con ese DNI")
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProveedorAdminResponse> actualizar(@Parameter(description = "Id del proveedor") @PathVariable UUID id,
                                                               @RequestBody @Valid ProveedorUpdateRequest dto) {
        return ResponseEntity.ok(proveedorService.actualizar(id, dto));
    }

    // Auditoria CRUD (prioridad ALTA -- hallazgo critico): Proveedor.activo ya
    // existia y RegistroAcopioService ya lo validaba, pero era imposible
    // desactivar un proveedor desde la API. Baja logica, nunca fisica.
    @Operation(
            summary = "Desactivar proveedor",
            description = "Rol requerido: ADMIN. Baja lógica: un proveedor inactivo ya no admite nuevos "
                    + "registros de acopio (RecursoInactivoException), pero conserva todo su historial."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Proveedor desactivado"),
            @ApiResponse(responseCode = "404", description = "No existe un proveedor con ese id")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> desactivar(@Parameter(description = "Id del proveedor") @PathVariable UUID id) {
        proveedorService.desactivar(id);
        return ResponseEntity.noContent().build();
    }

    // Auditoria de bajo riesgo (Fase 3): la baja logica no tenia forma de revertirse.
    @Operation(
            summary = "Reactivar proveedor",
            description = "Rol requerido: ADMIN. Revierte la baja lógica: vuelve a admitir nuevos registros de acopio."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Proveedor reactivado",
                    content = @Content(schema = @Schema(implementation = ProveedorAdminResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe un proveedor con ese id")
    })
    @PatchMapping("/{id}/reactivar")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProveedorAdminResponse> reactivar(@Parameter(description = "Id del proveedor") @PathVariable UUID id) {
        return ResponseEntity.ok(proveedorService.reactivar(id));
    }

    // Auditoria CRUD (prioridad BAJA): HistorialZonaRepository ya tenia el
    // query, no se exponia en ningun endpoint.
    @Operation(summary = "Historial de zonas de un proveedor", description = "Rol requerido: ADMIN. Orden descendente por fecha de inicio.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Historial de zonas",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = HistorialZonaResponse.class)))),
            @ApiResponse(responseCode = "404", description = "No existe un proveedor con ese id")
    })
    @GetMapping("/{id}/historial-zona")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<HistorialZonaResponse>> historialZona(@Parameter(description = "Id del proveedor") @PathVariable UUID id) {
        return ResponseEntity.ok(proveedorService.historialZona(id));
    }

    // RF-RUT-02: reasignar zona sin perder historial (ProveedorService cierra
    // automaticamente el periodo anterior de HistorialZona).
    @Operation(
            summary = "Reasignar zona",
            description = "Rol requerido: ADMIN. No es un simple update: cierra automáticamente el período "
                    + "de HistorialZona abierto (si existe) antes de abrir uno nuevo con la zona indicada."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Zona reasignada",
                    content = @Content(schema = @Schema(implementation = ProveedorAdminResponse.class))),
            @ApiResponse(responseCode = "404", description = "El proveedor o la zona indicados no existen")
    })
    @PutMapping("/{id}/zona")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProveedorAdminResponse> reasignarZona(
            @Parameter(description = "Id del proveedor") @PathVariable UUID id,
            @Parameter(description = "Id de la nueva zona") @RequestParam UUID zonaId) {
        return ResponseEntity.ok(proveedorService.reasignarZona(id, zonaId));
    }
}
