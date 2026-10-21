package com.planta.acopio.service;

import com.planta.acopio.config.JwtService;
import com.planta.acopio.dto.CambiarPasswordRequest;
import com.planta.acopio.dto.LoginRequest;
import com.planta.acopio.dto.LoginResponse;
import com.planta.acopio.dto.RefreshTokenRequest;
import com.planta.acopio.dto.UsuarioMeResponse;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.DemasiadosIntentosException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UsuarioRepository usuarioRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final UnidadRepository unidadRepository;
    private final LoginAttemptService loginAttemptService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(AuthenticationManager authenticationManager, UsuarioRepository usuarioRepository,
                        JwtService jwtService, PasswordEncoder passwordEncoder, UnidadRepository unidadRepository,
                        LoginAttemptService loginAttemptService, RefreshTokenService refreshTokenService) {
        this.authenticationManager = authenticationManager;
        this.usuarioRepository = usuarioRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.unidadRepository = unidadRepository;
        this.loginAttemptService = loginAttemptService;
        this.refreshTokenService = refreshTokenService;
    }

    // Bloque 10 (confirmado): bloqueo por email tras varios intentos fallidos
    // seguidos (LoginAttemptService, en memoria). Un login exitoso resetea el
    // contador de ese email.
    public LoginResponse login(LoginRequest request) {
        if (loginAttemptService.estaBloqueado(request.email())) {
            throw new DemasiadosIntentosException(
                    "Demasiados intentos fallidos. Intente nuevamente en "
                            + loginAttemptService.minutosRestantesDeBloqueo(request.email()) + " minuto(s).");
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        } catch (Exception ex) {
            loginAttemptService.registrarFallo(request.email());
            throw new BadCredentialsException("Credenciales invalidas");
        }

        loginAttemptService.registrarExito(request.email());

        Usuario usuario = usuarioRepository.findByEmail(request.email())
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado"));

        // Bloque "Token offline" (confirmado): cada login emite tambien un
        // refresh token de larga duracion, para que la app pueda recuperar
        // sesion tras varios dias offline sin volver a pedir la contraseña.
        String token = jwtService.generarToken(usuario.getEmail(), usuario.getRol().getNombre(), usuario.getId().toString());
        String refreshToken = refreshTokenService.generar(usuario);
        return new LoginResponse(token, usuario.getRol().getNombre(), usuario.getNombre(),
                jwtService.getExpirationMinutes() * 60, refreshToken, refreshTokenService.getDiasValidez() * 86400);
    }

    // Segunda auditoria (flujos end-to-end §11/§14-F): CORE_DESIGN.md §2.9
    // pide JWT "de expiracion corta... con refresh" para tolerar mala señal en
    // campo. Requiere un JWT TODAVIA valido en el header Authorization --
    // SecurityConfig exige autenticacion para /refresh, y JwtAuthFilter/
    // UsuarioDetailsService ya validaron que el usuario sigue activo antes de
    // que este metodo se ejecute. Un token YA EXPIRADO no se puede refrescar
    // por esta via -- para ESE caso (el problema real de "offline varios
    // dias") esta refrescarConRefreshToken() mas abajo.
    // No cambiado de arquitectura (confirmado): este metodo sigue exactamente
    // igual que antes -- no emite ni rota refresh token (refreshToken=null en
    // la respuesta), evita generar filas de refresh_token en cada llamada
    // liviana de este endpoint que ya funcionaba.
    public LoginResponse refrescar() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado"));

        String token = jwtService.generarToken(usuario.getEmail(), usuario.getRol().getNombre(), usuario.getId().toString());
        return new LoginResponse(token, usuario.getRol().getNombre(), usuario.getNombre(),
                jwtService.getExpirationMinutes() * 60, null, 0);
    }

    // Bloque "Token offline" (confirmado): el mecanismo real para el caso que
    // pediste -- el access token YA ESTA VENCIDO (por eso no se puede usar el
    // /refresh de arriba), pero el refresh token (mas largo) todavia es
    // valido. No requiere Authorization header -- SecurityConfig lo deja en
    // permitAll, igual que /login. Rotacion: el refresh token usado queda
    // invalidado, se emite un access token nuevo Y un refresh token nuevo.
    @Transactional
    public LoginResponse refrescarConRefreshToken(RefreshTokenRequest request) {
        Usuario usuario = refreshTokenService.validarYRotar(request.refreshToken());
        if (!Boolean.TRUE.equals(usuario.getActivo())) {
            throw new ValidacionNegocioException("Usuario inactivo");
        }

        String token = jwtService.generarToken(usuario.getEmail(), usuario.getRol().getNombre(), usuario.getId().toString());
        String nuevoRefreshToken = refreshTokenService.generar(usuario);
        return new LoginResponse(token, usuario.getRol().getNombre(), usuario.getNombre(),
                jwtService.getExpirationMinutes() * 60, nuevoRefreshToken, refreshTokenService.getDiasValidez() * 86400);
    }

    // Auditoria de bajo riesgo (Fases 1/4): el JWT ya lleva rol/usuarioId, pero
    // no nombre/activo actualizados -- este endpoint resuelve el usuario
    // autenticado contra BD, sin duplicar mas datos en el token.
    // Bloque 1 (confirmado): Usuario no tiene zonaId/unidadId propios -- no se
    // inventa esa relacion. unidadesResponsable expone la relacion INVERSA que
    // si existe (Unidad.responsable_id), para que el movil no tenga que
    // hardcodear unidadId si el usuario ya es responsable de una unidad conocida.
    public UsuarioMeResponse me() {
        Usuario usuario = usuarioActual();
        List<UUID> unidadesResponsable = unidadRepository.findByResponsableId(usuario.getId()).stream()
                .map(Unidad::getId).toList();
        return new UsuarioMeResponse(usuario.getId(), usuario.getNombre(), usuario.getEmail(),
                usuario.getRol().getNombre(), usuario.getActivo(), unidadesResponsable);
    }

    // Auditoria de bajo riesgo (Fases 1/3): self-service, distinto del reset
    // por ADMIN (UsuarioService.actualizar) -- exige la password actual.
    @Transactional
    public void cambiarPassword(CambiarPasswordRequest dto) {
        Usuario usuario = usuarioActual();
        if (!passwordEncoder.matches(dto.passwordActual(), usuario.getPasswordHash())) {
            throw new ValidacionNegocioException("La contraseña actual no es correcta");
        }
        usuario.setPasswordHash(passwordEncoder.encode(dto.passwordNueva()));
        // Bloque "Token offline" (confirmado): si la contraseña cambio (ej.
        // porque la anterior estaba comprometida), los refresh tokens viejos
        // no deben seguir sirviendo para recuperar sesion.
        refreshTokenService.revocarTodosDeUsuario(usuario.getId());
    }

    private Usuario usuarioActual() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado"));
    }
}
