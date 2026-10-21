package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.dto.JornadaBandejaResponse;
import com.planta.acopio.dto.JornadaDetalleResponse;
import com.planta.acopio.entity.EstadoJornada;
import com.planta.acopio.service.JornadaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 2 (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend): "Respuesta 403 para un rol no
 * autorizado" es un criterio de prueba explícito del pedido. Mismo patrón que
 * {@code RegistroAcopioAuthorizationWebMvcTest} (sin Docker/Testcontainers, ver esa clase para el detalle
 * de por qué {@code @Import(SecurityConfig.class, JwtAuthFilter.class)} conserva la seguridad real en vez
 * de mockearla).
 */
@WebMvcTest(JornadaController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
class JornadaControllerAuthorizationWebMvcTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private JornadaService jornadaService;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    private final UUID jornadaId = UUID.randomUUID();

    @Test
    @WithMockUser(roles = "RECEPCION")
    void recepcionPuedeListarLaBandeja() throws Exception {
        when(jornadaService.listarPendientesRecepcion()).thenReturn(List.of(bandejaDeEjemplo()));

        mockMvc.perform(get("/api/jornadas")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "RECEPCION")
    void recepcionPuedeVerElDetalle() throws Exception {
        when(jornadaService.obtenerDetalleParaRecepcion(jornadaId)).thenReturn(detalleDeEjemplo());

        mockMvc.perform(get("/api/jornadas/" + jornadaId)).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPuedeListarLaBandeja() throws Exception {
        when(jornadaService.listarPendientesRecepcion()).thenReturn(List.of());

        mockMvc.perform(get("/api/jornadas")).andExpect(status().isOk());
    }

    // Un rol sin relacion con Recepcion (ACOPIADOR, dueño de la jornada) sigue en 403 en ambas lecturas --
    // esta fase no le agrega ningun permiso nuevo sobre este controller.
    @Test
    @WithMockUser(roles = "ACOPIADOR")
    void acopiadorRecibe403EnAmbasLecturas() throws Exception {
        mockMvc.perform(get("/api/jornadas")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/jornadas/" + jornadaId)).andExpect(status().isForbidden());

        verifyNoInteractions(jornadaService);
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    void produccionRecibe403EnAmbasLecturas() throws Exception {
        mockMvc.perform(get("/api/jornadas")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/jornadas/" + jornadaId)).andExpect(status().isForbidden());

        verifyNoInteractions(jornadaService);
    }

    @Test
    void sinAutenticacionDevuelve401NoForbidden() throws Exception {
        mockMvc.perform(get("/api/jornadas")).andExpect(status().isUnauthorized());
        verifyNoInteractions(jornadaService);
    }

    private JornadaBandejaResponse bandejaDeEjemplo() {
        return new JornadaBandejaResponse(jornadaId, "uuid-cliente", EstadoJornada.REGRESANDO_A_PLANTA,
                UUID.randomUUID(), "CARLOS-01", UUID.randomUUID(), "Carlos Acopiador",
                LocalDateTime.now().minusHours(2), LocalDateTime.now(), 2, 2, BigDecimal.valueOf(80));
    }

    private JornadaDetalleResponse detalleDeEjemplo() {
        return new JornadaDetalleResponse(jornadaId, "uuid-cliente", EstadoJornada.REGRESANDO_A_PLANTA,
                UUID.randomUUID(), "CARLOS-01", UUID.randomUUID(), "Carlos Acopiador",
                LocalDateTime.now().minusHours(2), LocalDateTime.now(), BigDecimal.valueOf(80),
                List.of("Aníbal Núñez"), List.of());
    }
}
