package com.planta.acopio.service;

import com.planta.acopio.entity.RefreshToken;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// Bloque "Token offline" (confirmado): refresh token opaco, rotativo,
// guardado solo como hash SHA-256.
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock private RefreshTokenRepository repository;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(repository, 30);
    }

    @Test
    void generarGuardaSoloElHashNuncaElValorEnClaro() {
        Usuario usuario = new Usuario();
        usuario.setId(UUID.randomUUID());

        String tokenPlano = service.generar(usuario);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        RefreshToken guardado = captor.getValue();

        assertThat(tokenPlano).isNotBlank();
        assertThat(guardado.getTokenHash()).isNotEqualTo(tokenPlano); // nunca el valor en claro
        assertThat(guardado.getTokenHash()).hasSize(64); // hex de SHA-256
        assertThat(guardado.getUsuario()).isEqualTo(usuario);
        assertThat(guardado.getRevocado()).isFalse();
        assertThat(guardado.getExpiraEn()).isAfter(LocalDateTime.now().plusDays(29));
    }

    @Test
    void validarYRotarAceptaUnTokenValidoYLoMarcaRevocado() {
        Usuario usuario = new Usuario();
        usuario.setId(UUID.randomUUID());
        String tokenPlano = service.generar(usuario);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        RefreshToken guardado = captor.getValue();
        when(repository.findByTokenHash(guardado.getTokenHash())).thenReturn(Optional.of(guardado));

        Usuario resultado = service.validarYRotar(tokenPlano);

        assertThat(resultado).isEqualTo(usuario);
        assertThat(guardado.getRevocado()).isTrue(); // rotacion: queda invalidado tras usarse
    }

    @Test
    void validarYRotarRechazaUnTokenYaRevocado() {
        RefreshToken existente = new RefreshToken();
        existente.setRevocado(true);
        existente.setExpiraEn(LocalDateTime.now().plusDays(10));
        when(repository.findByTokenHash(any())).thenReturn(Optional.of(existente));

        assertThatThrownBy(() -> service.validarYRotar("cualquier-token"))
                .isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void validarYRotarRechazaUnTokenVencido() {
        RefreshToken existente = new RefreshToken();
        existente.setRevocado(false);
        existente.setExpiraEn(LocalDateTime.now().minusDays(1)); // ya vencio
        when(repository.findByTokenHash(any())).thenReturn(Optional.of(existente));

        assertThatThrownBy(() -> service.validarYRotar("cualquier-token"))
                .isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void validarYRotarRechazaUnTokenInexistente() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validarYRotar("token-nunca-emitido"))
                .isInstanceOf(ValidacionNegocioException.class);
    }
}
