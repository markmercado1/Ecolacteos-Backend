package com.planta.acopio.service;

import com.planta.acopio.entity.Usuario;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Bloque "Token offline" (confirmado): desactivar un usuario debe revocar
// sus refresh tokens vigentes -- no debe poder recuperar sesion offline.
@ExtendWith(MockitoExtension.class)
class UsuarioServiceTest {

    @Mock private UsuarioRepository usuarioRepository;
    @Mock private RolRepository rolRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RefreshTokenService refreshTokenService;

    private UsuarioService service;

    @BeforeEach
    void setUp() {
        service = new UsuarioService(usuarioRepository, rolRepository, passwordEncoder, refreshTokenService);
    }

    @Test
    void desactivarRevocaTodosLosRefreshTokensDelUsuario() {
        UUID usuarioId = UUID.randomUUID();
        Usuario usuario = new Usuario();
        usuario.setId(usuarioId);
        usuario.setActivo(true);
        when(usuarioRepository.findById(usuarioId)).thenReturn(Optional.of(usuario));

        service.desactivar(usuarioId);

        assertThat(usuario.getActivo()).isFalse();
        verify(refreshTokenService).revocarTodosDeUsuario(usuarioId);
    }
}
