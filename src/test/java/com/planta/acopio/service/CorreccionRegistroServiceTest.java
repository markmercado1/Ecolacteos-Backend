package com.planta.acopio.service;

import com.planta.acopio.dto.CorreccionRegistroRequest;
import com.planta.acopio.dto.CorreccionRegistroResponse;
import com.planta.acopio.entity.CorreccionRegistro;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.repository.CorreccionRegistroRepository;
import com.planta.acopio.repository.RegistroAcopioRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// RNF-03/05: anexar una correccion nunca bloquea, ni siquiera sobre un
// registro ya liquidado (Fase 3, decision de negocio 1, opcion C confirmada)
// -- eso es responsabilidad de AjustePagoService, invocado como efecto
// colateral sin condicionar el resultado de este metodo.
@ExtendWith(MockitoExtension.class)
class CorreccionRegistroServiceTest {

    @Mock private CorreccionRegistroRepository repository;
    @Mock private RegistroAcopioRepository registroAcopioRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private AjustePagoService ajustePagoService;

    private CorreccionRegistroService service;
    private final UUID registroId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CorreccionRegistroService(repository, registroAcopioRepository, usuarioRepository, ajustePagoService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("calidad@planta.com", null));
    }

    @Test
    void anexarSiempreInvocaAjustePagoServiceSinBloquearLaCorreccion() {
        RegistroAcopio registro = new RegistroAcopio();
        registro.setId(registroId);
        registro.setLitros(BigDecimal.valueOf(40));
        when(registroAcopioRepository.findById(registroId)).thenReturn(Optional.of(registro));

        Usuario usuario = new Usuario();
        usuario.setId(UUID.randomUUID());
        usuario.setNombre("Responsable Calidad");
        when(usuarioRepository.findByEmail("calidad@planta.com")).thenReturn(Optional.of(usuario));

        when(repository.save(any(CorreccionRegistro.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = new CorreccionRegistroRequest(BigDecimal.valueOf(35), "Balde mal medido");
        CorreccionRegistroResponse respuesta = service.anexar(registroId, dto);

        // La correccion se crea igual, sin importar si el registro estaba
        // liquidado o no -- Opcion C nunca bloquea (a diferencia de la Opcion
        // B descartada).
        assertThat(respuesta.litrosAnterior()).isEqualByComparingTo(BigDecimal.valueOf(40));
        assertThat(respuesta.litrosCorregido()).isEqualByComparingTo(BigDecimal.valueOf(35));
        verify(ajustePagoService).generarSiCorrespondeAjuste(eq(registro), any(CorreccionRegistro.class));
    }
}
