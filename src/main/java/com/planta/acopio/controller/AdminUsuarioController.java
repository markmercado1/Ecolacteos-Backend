package com.planta.acopio.controller;

import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.dto.UsuarioRequest;
import com.planta.acopio.dto.UsuarioResponse;
import com.planta.acopio.dto.UsuarioUpdateRequest;
import com.planta.acopio.service.UsuarioService;
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

@Tag(name = "Usuarios", description = "Cuentas de acceso al sistema. Rol requerido: ADMIN en todos los endpoints.")
@RestController
@RequestMapping("/api/admin/usuarios")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUsuarioController {

    private final UsuarioService usuarioService;

    public AdminUsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    @Operation(summary = "Crear usuario", description = "Rol requerido: ADMIN. El email debe ser único.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Usuario creado",
                    content = @Content(schema = @Schema(implementation = UsuarioResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos (email mal formado, password < 8 caracteres, etc.)"),
            @ApiResponse(responseCode = "404", description = "El rolNombre indicado no existe"),
            @ApiResponse(responseCode = "409", description = "Ya existe un usuario con ese email")
    })
    @PostMapping
    public ResponseEntity<UsuarioResponse> crear(@RequestBody @Valid UsuarioRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(usuarioService.crear(dto));
    }

    @Operation(summary = "Listar usuarios", description = "Rol requerido: ADMIN.")
    @ApiResponse(responseCode = "200", description = "Listado completo",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = UsuarioResponse.class))))
    @GetMapping
    public ResponseEntity<List<UsuarioResponse>> listar() {
        return ResponseEntity.ok(usuarioService.listar());
    }

    // Paginacion pendiente: la lista completa de arriba queda para selectores
    // (responsable de unidad); las pantallas de usuarios/acopiadores usan esta.
    @Operation(summary = "Listar usuarios (paginado)", description = "Rol requerido: ADMIN. Filtros opcionales y "
            + "combinables, aplicados antes de paginar: `rol` (nombre exacto), `activo` y `busqueda` (nombre o "
            + "correo, sin distinguir mayúsculas). Orden por nombre, desempate por id. page empieza en 0; "
            + "size se acota a 1..100.")
    @ApiResponse(responseCode = "200", description = "Página de usuarios",
            content = @Content(schema = @Schema(implementation = PaginaResponse.class)))
    @GetMapping("/paginado")
    public ResponseEntity<PaginaResponse<UsuarioResponse>> listarPaginado(
            @Parameter(description = "Filtrar por rol (ADMIN, ACOPIADOR, ...)") @RequestParam(required = false) String rol,
            @Parameter(description = "Filtrar por estado activo/inactivo") @RequestParam(required = false) Boolean activo,
            @Parameter(description = "Buscar por nombre o correo") @RequestParam(required = false) String busqueda,
            @Parameter(description = "Número de página, empieza en 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 100)") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PaginaResponse.de(usuarioService.buscar(rol, activo, busqueda, Paginacion.de(page, size))));
    }

    @Operation(summary = "Obtener usuario por id", description = "Rol requerido: ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Usuario encontrado",
                    content = @Content(schema = @Schema(implementation = UsuarioResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe un usuario con ese id")
    })
    @GetMapping("/{id}")
    public ResponseEntity<UsuarioResponse> obtener(@Parameter(description = "Id del usuario") @PathVariable UUID id) {
        return ResponseEntity.ok(usuarioService.obtener(id));
    }

    // Auditoria CRUD (prioridad ALTA): antes no habia forma de corregir un
    // typo, cambiar el rol o resetear la contraseña de un usuario ya creado.
    @Operation(
            summary = "Editar usuario",
            description = "Rol requerido: ADMIN. `nuevaPassword` es opcional: si viene vacío o ausente, "
                    + "la contraseña actual no se modifica; si viene, debe tener al menos 8 caracteres."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Usuario actualizado",
                    content = @Content(schema = @Schema(implementation = UsuarioResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "El usuario o el rolNombre indicado no existen"),
            @ApiResponse(responseCode = "409", description = "Ya existe otro usuario con ese email")
    })
    @PutMapping("/{id}")
    public ResponseEntity<UsuarioResponse> actualizar(@Parameter(description = "Id del usuario") @PathVariable UUID id,
                                                        @RequestBody @Valid UsuarioUpdateRequest dto) {
        return ResponseEntity.ok(usuarioService.actualizar(id, dto));
    }

    @Operation(
            summary = "Desactivar usuario",
            description = "Rol requerido: ADMIN. Baja lógica (activo=false): revoca el acceso de inmediato, "
                    + "sin esperar a que expire su JWT. No elimina al usuario ni su historial de acciones."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Usuario desactivado"),
            @ApiResponse(responseCode = "404", description = "No existe un usuario con ese id")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> desactivar(@Parameter(description = "Id del usuario") @PathVariable UUID id) {
        usuarioService.desactivar(id);
        return ResponseEntity.noContent().build();
    }

    // Auditoria de bajo riesgo (Fase 3): la baja logica no tenia forma de revertirse.
    @Operation(summary = "Reactivar usuario", description = "Rol requerido: ADMIN. Revierte la baja lógica: restaura el acceso al sistema.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Usuario reactivado",
                    content = @Content(schema = @Schema(implementation = UsuarioResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe un usuario con ese id")
    })
    @PatchMapping("/{id}/reactivar")
    public ResponseEntity<UsuarioResponse> reactivar(@Parameter(description = "Id del usuario") @PathVariable UUID id) {
        return ResponseEntity.ok(usuarioService.reactivar(id));
    }
}
