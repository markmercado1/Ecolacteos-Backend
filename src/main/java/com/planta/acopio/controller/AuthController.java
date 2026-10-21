package com.planta.acopio.controller;

import com.planta.acopio.dto.CambiarPasswordRequest;
import com.planta.acopio.dto.LoginRequest;
import com.planta.acopio.dto.LoginResponse;
import com.planta.acopio.dto.RefreshTokenRequest;
import com.planta.acopio.dto.UsuarioMeResponse;
import com.planta.acopio.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Autenticación", description = "Login y renovación de sesión. Único módulo con un endpoint público.")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(
            summary = "Iniciar sesión",
            description = "Público, sin JWT. Autentica por email + password y emite un JWT válido por 8 horas "
                    + "con el rol del usuario embebido. Un usuario desactivado no puede autenticarse."
    )
    @SecurityRequirements // sin seguridad: es el unico endpoint publico de toda la API
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login correcto",
                    content = @Content(schema = @Schema(implementation = LoginResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos de entrada inválidos (email/password faltante o email mal formado)"),
            @ApiResponse(responseCode = "401", description = "Credenciales inválidas o usuario inactivo")
    })
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    // Segunda auditoria (flujos end-to-end §11/§14-E): requiere un JWT
    // todavia valido en el header Authorization (SecurityConfig ya no incluye
    // /refresh en el permitAll de /api/auth/**).
    @Operation(
            summary = "Renovar sesión",
            description = "Requiere un JWT todavía vigente en el header Authorization (no expirado). "
                    + "Emite un token nuevo con el mismo rol y otras 8 horas de vigencia. "
                    + "Un token YA expirado no se puede renovar por esta vía: eso es un login nuevo, no un refresh."
    )
    @ApiResponse(responseCode = "200", description = "Token renovado",
            content = @Content(schema = @Schema(implementation = LoginResponse.class)))
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refrescar() {
        return ResponseEntity.ok(authService.refrescar());
    }

    // Bloque "Token offline" (confirmado): mecanismo para cuando el access
    // token YA ESTA VENCIDO (varios dias sin conexion) -- /refresh de arriba
    // no sirve en ese caso porque exige un JWT todavia valido. Este endpoint
    // es publico (no requiere Authorization): la credencial es el propio
    // refreshToken en el body. Rotativo: el refresh token usado queda
    // invalidado, se devuelve uno nuevo junto con el access token nuevo.
    @Operation(
            summary = "Renovar sesión con refresh token (offline)",
            description = "Público, sin JWT -- la credencial es el `refreshToken` del body (obtenido en login). "
                    + "Uso principal: el access token ya expiró (varios días sin conexión) y `/refresh` no sirve "
                    + "para ese caso. Rotativo: el refresh token enviado queda invalidado; la respuesta trae uno "
                    + "nuevo junto con el access token nuevo."
    )
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sesión renovada",
                    content = @Content(schema = @Schema(implementation = LoginResponse.class))),
            @ApiResponse(responseCode = "400", description = "Refresh token inválido, expirado, ya usado, o usuario inactivo")
    })
    @PostMapping("/refresh-token")
    public ResponseEntity<LoginResponse> refrescarConRefreshToken(@RequestBody @Valid RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refrescarConRefreshToken(request));
    }

    // Auditoria de bajo riesgo (Fases 1/4): resuelve el usuario dueño del JWT
    // contra la base de datos (nombre/activo actualizados, no solo lo que
    // viaja en el token).
    // Bloque 1 (confirmado): Usuario no tiene zonaId/unidadId propios en el
    // modelo actual -- no se inventa esa relacion. unidadesResponsable expone
    // la relacion INVERSA que si existe (Unidad.responsable_id -> Usuario).
    @Operation(
            summary = "Usuario autenticado actual",
            description = "Cualquier rol. Devuelve los datos del usuario dueño del JWT enviado, resueltos desde "
                    + "la base de datos (no solo lo que viaja en el token). `unidadesResponsable` lista las "
                    + "unidades (camiones) de las que este usuario es responsable -- Usuario no tiene un campo "
                    + "`zonaId` propio: la zona vive en `Proveedor.zonaActual` y en `Unidad.zona`, nunca en Usuario."
    )
    @ApiResponse(responseCode = "200", description = "Usuario actual",
            content = @Content(schema = @Schema(implementation = UsuarioMeResponse.class)))
    @GetMapping("/me")
    public ResponseEntity<UsuarioMeResponse> me() {
        return ResponseEntity.ok(authService.me());
    }

    // Auditoria de bajo riesgo (Fases 1/3): self-service, distinto del reset
    // por ADMIN (PUT /api/admin/usuarios/{id}) -- exige la password actual.
    @Operation(
            summary = "Cambiar mi contraseña",
            description = "Cualquier rol. El propio usuario autenticado cambia su contraseña, confirmando la actual."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Contraseña actualizada"),
            @ApiResponse(responseCode = "400", description = "La contraseña actual no es correcta, o la nueva no cumple el mínimo de 8 caracteres")
    })
    @PatchMapping("/password")
    public ResponseEntity<Void> cambiarPassword(@RequestBody @Valid CambiarPasswordRequest dto) {
        authService.cambiarPassword(dto);
        return ResponseEntity.noContent().build();
    }
}
