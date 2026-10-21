package com.planta.acopio.service;

import com.planta.acopio.config.JwtService;
import com.planta.acopio.dto.LoginRequest;
import com.planta.acopio.dto.RefreshTokenRequest;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.DemasiadosIntentosException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Bloque 1 (confirmado): GET /api/auth/me debe exponer lo que el movil
// necesita sin hardcodear zonaId/unidadId, sin inventar una relacion
// Usuario->Zona/Unidad que no existe en el modelo.
// Bloque 10 (confirmado): bloqueo por intentos fallidos seguidos de login.
// Bloque "Token offline" (confirmado): login() emite refresh token;
// refrescarConRefreshToken() es el mecanismo real para access token vencido.
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private AuthenticationManager authenticationManager;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private JwtService jwtService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private UnidadRepository unidadRepository;
    @Mock private LoginAttemptService loginAttemptService;
    @Mock private RefreshTokenService refreshTokenService;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(authenticationManager, usuarioRepository, jwtService, passwordEncoder,
                unidadRepository, loginAttemptService, refreshTokenService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("usuario@planta.com", null));
    }

    @Test
    void meIncluyeLasUnidadesDeLasQueElUsuarioEsResponsable() {
        Usuario usuario = usuarioCon("Juan Acopiador", "ACOPIADOR");
        when(usuarioRepository.findByEmail("usuario@planta.com")).thenReturn(Optional.of(usuario));

        UUID unidadId = UUID.randomUUID();
        Unidad unidad = new Unidad();
        unidad.setId(unidadId);
        when(unidadRepository.findByResponsableId(usuario.getId())).thenReturn(List.of(unidad));

        var respuesta = service.me();

        assertThat(respuesta.id()).isEqualTo(usuario.getId());
        assertThat(respuesta.nombre()).isEqualTo("Juan Acopiador");
        assertThat(respuesta.email()).isEqualTo("usuario@planta.com");
        assertThat(respuesta.rolNombre()).isEqualTo("ACOPIADOR");
        assertThat(respuesta.activo()).isTrue();
        assertThat(respuesta.unidadesResponsable()).containsExactly(unidadId);
    }

    // La mayoria de roles (CALIDAD, VENTAS, etc.) no son responsables de
    // ninguna unidad -- debe venir vacia, nunca null ni un error.
    @Test
    void meDevuelveListaVaciaDeUnidadesSiNoEsResponsableDeNinguna() {
        Usuario usuario = usuarioCon("Maria Calidad", "CALIDAD");
        when(usuarioRepository.findByEmail("usuario@planta.com")).thenReturn(Optional.of(usuario));
        when(unidadRepository.findByResponsableId(usuario.getId())).thenReturn(List.of());

        var respuesta = service.me();

        assertThat(respuesta.unidadesResponsable()).isEmpty();
    }

    @Test
    void loginRechazaConDemasiadosIntentosSiElEmailEstaBloqueado() {
        var dto = new LoginRequest("acopiador@planta.com", "cualquiera");
        when(loginAttemptService.estaBloqueado("acopiador@planta.com")).thenReturn(true);
        when(loginAttemptService.minutosRestantesDeBloqueo("acopiador@planta.com")).thenReturn(7L);

        assertThatThrownBy(() -> service.login(dto))
                .isInstanceOf(DemasiadosIntentosException.class)
                .hasMessageContaining("7");

        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void loginRegistraElFalloCuandoLasCredencialesSonInvalidas() {
        var dto = new LoginRequest("acopiador@planta.com", "incorrecta");
        when(loginAttemptService.estaBloqueado("acopiador@planta.com")).thenReturn(false);
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("mal"));

        assertThatThrownBy(() -> service.login(dto)).isInstanceOf(BadCredentialsException.class);

        verify(loginAttemptService).registrarFallo("acopiador@planta.com");
        verify(loginAttemptService, never()).registrarExito(any());
    }

    @Test
    void loginExitosoReseteaElContadorDeIntentosYEmiteRefreshToken() {
        var dto = new LoginRequest("acopiador@planta.com", "correcta");
        when(loginAttemptService.estaBloqueado("acopiador@planta.com")).thenReturn(false);

        Usuario usuario = usuarioCon("Juan Acopiador", "ACOPIADOR");
        usuario.setEmail("acopiador@planta.com");
        when(usuarioRepository.findByEmail("acopiador@planta.com")).thenReturn(Optional.of(usuario));
        when(jwtService.generarToken(any(), any(), any())).thenReturn("token-simulado");
        when(jwtService.getExpirationMinutes()).thenReturn(480L);
        when(refreshTokenService.generar(usuario)).thenReturn("refresh-token-simulado");
        when(refreshTokenService.getDiasValidez()).thenReturn(30L);

        var respuesta = service.login(dto);

        verify(loginAttemptService).registrarExito("acopiador@planta.com");
        verify(loginAttemptService, never()).registrarFallo(any());
        assertThat(respuesta.refreshToken()).isEqualTo("refresh-token-simulado");
        assertThat(respuesta.refreshExpiraEnSegundos()).isEqualTo(30L * 86400);
    }

    // Bloque "Token offline" (confirmado): el /refresh viejo (access token
    // todavia vigente) NO debe emitir un refresh token nuevo -- evita filas
    // de refresh_token en cada llamada liviana de un endpoint que ya
    // funcionaba antes de esta decision.
    @Test
    void refrescarViejoNoEmiteNiTocaRefreshToken() {
        Usuario usuario = usuarioCon("Juan Acopiador", "ACOPIADOR");
        when(usuarioRepository.findByEmail("usuario@planta.com")).thenReturn(Optional.of(usuario));
        when(jwtService.generarToken(any(), any(), any())).thenReturn("token-simulado");
        when(jwtService.getExpirationMinutes()).thenReturn(480L);

        var respuesta = service.refrescar();

        assertThat(respuesta.refreshToken()).isNull();
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void refrescarConRefreshTokenValidoRotaYDevuelveSesionNueva() {
        Usuario usuario = usuarioCon("Juan Acopiador", "ACOPIADOR");
        when(refreshTokenService.validarYRotar("refresh-valido")).thenReturn(usuario);
        when(jwtService.generarToken(any(), any(), any())).thenReturn("token-nuevo");
        when(jwtService.getExpirationMinutes()).thenReturn(480L);
        when(refreshTokenService.generar(usuario)).thenReturn("refresh-nuevo");
        when(refreshTokenService.getDiasValidez()).thenReturn(30L);

        var respuesta = service.refrescarConRefreshToken(new RefreshTokenRequest("refresh-valido"));

        assertThat(respuesta.token()).isEqualTo("token-nuevo");
        assertThat(respuesta.refreshToken()).isEqualTo("refresh-nuevo");
    }

    @Test
    void refrescarConRefreshTokenRechazaUsuarioInactivo() {
        Usuario usuario = usuarioCon("Juan Acopiador", "ACOPIADOR");
        usuario.setActivo(false);
        when(refreshTokenService.validarYRotar("refresh-de-inactivo")).thenReturn(usuario);

        assertThatThrownBy(() -> service.refrescarConRefreshToken(new RefreshTokenRequest("refresh-de-inactivo")))
                .isInstanceOf(ValidacionNegocioException.class);
    }

    // Bloque "Token offline" (confirmado): si la contraseña cambia, las
    // sesiones offline viejas (refresh tokens) no deben seguir sirviendo.
    @Test
    void cambiarPasswordRevocaTodosLosRefreshTokensDelUsuario() {
        Usuario usuario = usuarioCon("Juan Acopiador", "ACOPIADOR");
        usuario.setPasswordHash("hash-actual");
        when(usuarioRepository.findByEmail("usuario@planta.com")).thenReturn(Optional.of(usuario));
        when(passwordEncoder.matches("actual", "hash-actual")).thenReturn(true);
        when(passwordEncoder.encode("nueva12345")).thenReturn("hash-nuevo");

        service.cambiarPassword(new com.planta.acopio.dto.CambiarPasswordRequest("actual", "nueva12345"));

        verify(refreshTokenService).revocarTodosDeUsuario(usuario.getId());
    }

    private Usuario usuarioCon(String nombre, String rolNombre) {
        Usuario usuario = new Usuario();
        usuario.setId(UUID.randomUUID());
        usuario.setNombre(nombre);
        usuario.setEmail("usuario@planta.com");
        usuario.setActivo(true);
        Rol rol = new Rol();
        rol.setNombre(rolNombre);
        usuario.setRol(rol);
        return usuario;
    }
}
