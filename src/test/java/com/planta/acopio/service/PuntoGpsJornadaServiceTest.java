package com.planta.acopio.service;

import com.planta.acopio.dto.PuntoGpsJornadaDTO;
import com.planta.acopio.entity.Jornada;
import com.planta.acopio.entity.PuntoGpsJornada;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.JornadaRepository;
import com.planta.acopio.repository.PuntoGpsJornadaRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Fase 3 -- Recorrido GPS (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend). Mismo criterio de
// prioridad que JornadaServiceTest: idempotencia primero, despues aislamiento por usuario (ESPEC §19,
// "no confiar unicamente en el usuarioId enviado por el movil") y jornada inexistente.
@ExtendWith(MockitoExtension.class)
class PuntoGpsJornadaServiceTest {

    @Mock private PuntoGpsJornadaRepository puntoGpsJornadaRepository;
    @Mock private JornadaRepository jornadaRepository;
    @Mock private UsuarioRepository usuarioRepository;

    private PuntoGpsJornadaService service;

    private final UUID jornadaId = UUID.randomUUID();
    private final UUID acopiadorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PuntoGpsJornadaService(puntoGpsJornadaRepository, jornadaRepository, usuarioRepository);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("acopiador@planta.com", null));
        Usuario acopiador = new Usuario();
        acopiador.setId(acopiadorId);
        lenient().when(usuarioRepository.findByEmail("acopiador@planta.com")).thenReturn(Optional.of(acopiador));
    }

    private Jornada jornadaDe(UUID acopiadorIdDeLaJornada) {
        Jornada j = new Jornada();
        j.setId(jornadaId);
        Usuario acopiador = new Usuario();
        acopiador.setId(acopiadorIdDeLaJornada);
        j.setAcopiador(acopiador);
        return j;
    }

    private PuntoGpsJornadaDTO dtoValido(String uuidCliente) {
        return new PuntoGpsJornadaDTO(uuidCliente, jornadaId, BigDecimal.valueOf(-6.7), BigDecimal.valueOf(-78.5),
                BigDecimal.valueOf(12.5), LocalDateTime.now());
    }

    @Test
    void siUuidClienteYaExisteDevuelveElExistenteSinCrearDuplicado() {
        String uuidCliente = UUID.randomUUID().toString();
        PuntoGpsJornada existente = new PuntoGpsJornada();
        existente.setId(UUID.randomUUID());
        existente.setUuidCliente(uuidCliente);
        when(puntoGpsJornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.of(existente));

        var resultado = service.registrarOIgnorarSiDuplicado(dtoValido(uuidCliente));

        assertThat(resultado.getUuidCliente()).isEqualTo(uuidCliente);
        verify(puntoGpsJornadaRepository, never()).save(any());
        // Reintento: no vuelve a resolver jornada ni usuario -- devuelve exactamente lo que ya existia.
        verifyNoInteractions(jornadaRepository);
    }

    @Test
    void reenviarElMismoPuntoNoLoDuplicaAunqueCambienLasCoordenadas() {
        String uuidCliente = UUID.randomUUID().toString();
        PuntoGpsJornada existente = new PuntoGpsJornada();
        existente.setId(UUID.randomUUID());
        existente.setUuidCliente(uuidCliente);
        existente.setLat(BigDecimal.valueOf(-6.7));
        when(puntoGpsJornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.of(existente));

        var otraVersion = new PuntoGpsJornadaDTO(uuidCliente, jornadaId, BigDecimal.valueOf(-6.9),
                BigDecimal.valueOf(-78.9), null, LocalDateTime.now());
        var resultado = service.registrarOIgnorarSiDuplicado(otraVersion);

        assertThat(resultado.getLat()).isEqualByComparingTo(BigDecimal.valueOf(-6.7)); // sin tocar
        verify(puntoGpsJornadaRepository, never()).save(any());
    }

    @Test
    void rechazaJornadaInexistente() {
        String uuidCliente = UUID.randomUUID().toString();
        when(puntoGpsJornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(jornadaRepository.findById(jornadaId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registrarOIgnorarSiDuplicado(dtoValido(uuidCliente)))
                .isInstanceOf(RecursoNoEncontradoException.class);
        verify(puntoGpsJornadaRepository, never()).save(any());
    }

    // Aislamiento por usuario (ESPEC §19): un punto no puede vincularse a la jornada de otro acopiador,
    // aunque el uuidCliente/jornadaId enviados sean sintacticamente validos.
    @Test
    void rechazaJornadaDeOtroAcopiador() {
        String uuidCliente = UUID.randomUUID().toString();
        when(puntoGpsJornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(jornadaRepository.findById(jornadaId)).thenReturn(Optional.of(jornadaDe(UUID.randomUUID())));

        assertThatThrownBy(() -> service.registrarOIgnorarSiDuplicado(dtoValido(uuidCliente)))
                .isInstanceOf(ValidacionNegocioException.class);
        verify(puntoGpsJornadaRepository, never()).save(any());
    }

    @Test
    void guardaUnPuntoValidoDeLaPropiaJornadaConPrecisionOpcional() {
        String uuidCliente = UUID.randomUUID().toString();
        when(puntoGpsJornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(jornadaRepository.findById(jornadaId)).thenReturn(Optional.of(jornadaDe(acopiadorId)));
        when(puntoGpsJornadaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var sinPrecision = new PuntoGpsJornadaDTO(uuidCliente, jornadaId, BigDecimal.valueOf(-6.77),
                BigDecimal.valueOf(-78.51), null, LocalDateTime.now());
        var resultado = service.registrarOIgnorarSiDuplicado(sinPrecision);

        assertThat(resultado.getUuidCliente()).isEqualTo(uuidCliente);
        assertThat(resultado.getJornada().getId()).isEqualTo(jornadaId);
        assertThat(resultado.getPrecisionM()).isNull();
    }
}
