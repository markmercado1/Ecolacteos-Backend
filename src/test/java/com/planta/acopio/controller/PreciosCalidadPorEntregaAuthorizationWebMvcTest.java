package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.dto.AjusteCalidadEntregaResponse;
import com.planta.acopio.service.AjusteCalidadEntregaService;
import com.planta.acopio.service.AnalisisCalidadService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Cierre de la Fase C: CALIDAD consulta los precios de calidad de UNA entrega desde su zona (analisis), sin acceso a los pagos. Los permisos se
// evaluan de verdad (SecurityConfig real); solo los services estan mockeados.
@WebMvcTest(AnalisisCalidadController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
class PreciosCalidadPorEntregaAuthorizationWebMvcTest {

    @Autowired private MockMvc mvc;
    @MockBean private AnalisisCalidadService analisisCalidadService;
    @MockBean private AjusteCalidadEntregaService ajusteCalidadEntregaService;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    private final UUID registroId = UUID.randomUUID();

    private String url() {
        return "/api/analisis-calidad/registro/" + registroId + "/precios-calidad";
    }

    @Test
    @WithMockUser(roles = {"CALIDAD"})
    void calidadConsultaLosPreciosDeCalidadDeUnaEntrega() throws Exception {
        when(ajusteCalidadEntregaService.listarPorRegistro(registroId)).thenReturn(List.of(new AjusteCalidadEntregaResponse(
                UUID.randomUUID(), UUID.randomUUID(), registroId, UUID.randomUUID(), "F-001", "OBSERVADO", new BigDecimal("100.00"),
                new BigDecimal("1.000"), new BigDecimal("1.000"), new BigDecimal("0.900"), new BigDecimal("-10.00"), "Agua", "Administrador",
                LocalDateTime.of(2026, 10, 21, 10, 0))));

        mvc.perform(get(url()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].motivo").value("Agua"))
                .andExpect(jsonPath("$[0].precioLitroNuevo").value(0.9));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void adminTambienLoConsulta() throws Exception {
        when(ajusteCalidadEntregaService.listarPorRegistro(registroId)).thenReturn(List.of());

        mvc.perform(get(url())).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @WithMockUser(roles = {"RECEPCION", "VENTAS", "PRODUCCION", "ACOPIADOR"})
    void losDemasRolesNoLoConsultan() throws Exception {
        mvc.perform(get(url())).andExpect(status().isForbidden());

        verifyNoInteractions(ajusteCalidadEntregaService);
    }

    @Test
    void sinAutenticarNoSeConsulta() throws Exception {
        mvc.perform(get(url())).andExpect(status().is4xxClientError());

        verifyNoInteractions(ajusteCalidadEntregaService);
    }
}
