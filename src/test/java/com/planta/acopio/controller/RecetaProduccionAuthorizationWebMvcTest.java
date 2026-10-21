package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.service.LoteProduccionService;
import com.planta.acopio.service.RecetaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// V18: permisos con los roles existentes. Recetas: consulta/calculo PRODUCCION o ADMIN; administracion
// solo ADMIN (igual que Productos/Insumos). Lotes: las nuevas transiciones heredan PRODUCCION/ADMIN de
// LoteProduccionController.
@WebMvcTest({RecetaController.class, LoteProduccionController.class})
@Import({SecurityConfig.class, JwtAuthFilter.class})
class RecetaProduccionAuthorizationWebMvcTest {
    @Autowired private MockMvc mvc;
    @MockBean private RecetaService recetaService;
    @MockBean private LoteProduccionService loteProduccionService;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    private static final String RECETA_VALIDA = """
            {"nombre":"Receta estándar","productoId":"%s","presentacionId":"%s","litrosBase":100,
             "rendimientoBase":1,"insumos":[]}""".formatted(UUID.randomUUID(), UUID.randomUUID());

    @Test
    @WithMockUser(roles = "PRODUCCION")
    void produccionConsultaYCalculaRecetas() throws Exception {
        when(recetaService.listar(false)).thenReturn(List.of());
        mvc.perform(get("/api/recetas")).andExpect(status().isOk());
        mvc.perform(get("/api/recetas/" + UUID.randomUUID() + "/calculo").param("litros", "1000"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    void produccionNoAdministraRecetas() throws Exception {
        mvc.perform(post("/api/recetas").contentType(MediaType.APPLICATION_JSON).content(RECETA_VALIDA))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/recetas/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(RECETA_VALIDA))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/recetas/" + UUID.randomUUID())).andExpect(status().isForbidden());
        verify(recetaService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminAdministraRecetas() throws Exception {
        mvc.perform(post("/api/recetas").contentType(MediaType.APPLICATION_JSON).content(RECETA_VALIDA))
                .andExpect(status().isCreated());
        mvc.perform(delete("/api/recetas/" + UUID.randomUUID())).andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rechazaRecetaInvalidaConBadRequest() throws Exception {
        String sinBase = """
                {"nombre":"","productoId":"%s","presentacionId":"%s","litrosBase":0,"rendimientoBase":1,"insumos":[]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
        mvc.perform(post("/api/recetas").contentType(MediaType.APPLICATION_JSON).content(sinBase))
                .andExpect(status().isBadRequest());
        verify(recetaService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = {"VENTAS"})
    void otrosRolesNoAccedenARecetasNiATransiciones() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/api/recetas")).andExpect(status().isForbidden());
        mvc.perform(post("/api/lotes-produccion/" + id + "/iniciar")).andExpect(status().isForbidden());
        mvc.perform(post("/api/lotes-produccion/" + id + "/cancelar")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    void produccionOperaElCicloDeVidaDelLote() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/lotes-produccion/planificar").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"uuidCliente":"u1","fecha":"2026-09-30","recetaId":"%s","litros":1000}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/lotes-produccion/" + id + "/disponibilidad")).andExpect(status().isOk());
        mvc.perform(post("/api/lotes-produccion/" + id + "/iniciar")).andExpect(status().isOk());
        mvc.perform(post("/api/lotes-produccion/" + id + "/finalizar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"detalles\":[]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/lotes-produccion/" + id + "/cancelar")).andExpect(status().isOk());
        verify(loteProduccionService).iniciar(eq(id));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    void estadoInvalidoEnElFiltroEs400YNo500() throws Exception {
        mvc.perform(get("/api/lotes-produccion").param("estado", "INVENTADO")).andExpect(status().isBadRequest());
    }

    @Test
    void exigeAutenticacion() throws Exception {
        mvc.perform(get("/api/recetas")).andExpect(status().isUnauthorized());
    }
}
