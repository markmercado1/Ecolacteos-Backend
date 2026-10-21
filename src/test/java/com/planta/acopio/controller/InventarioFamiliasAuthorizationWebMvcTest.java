package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.dto.FamiliaResponse;
import com.planta.acopio.service.AjusteInventarioService;
import com.planta.acopio.service.FamiliaService;
import com.planta.acopio.service.InventarioLecheService;
import com.planta.acopio.service.InventarioService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InventarioController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
class InventarioFamiliasAuthorizationWebMvcTest {
    @Autowired private MockMvc mvc;
    @MockBean private InventarioService inventarioService;
    @MockBean private InventarioLecheService inventarioLecheService;
    @MockBean private FamiliaService familiaService;
    @MockBean private AjusteInventarioService ajusteInventarioService;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    @Test
    @WithMockUser(roles = "PRODUCCION")
    void produccionLeeFamiliasSinPermisoAdministrativo() throws Exception {
        when(familiaService.listarParaInventario()).thenReturn(List.of(new FamiliaResponse(UUID.randomUUID(), "Queso", false)));
        mvc.perform(get("/api/inventario/familias")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nombre").value("Queso"))
                .andExpect(jsonPath("$[0].activo").value(false));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    void ventasLeeFamilias() throws Exception {
        mvc.perform(get("/api/inventario/familias")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminLeeFamilias() throws Exception {
        mvc.perform(get("/api/inventario/familias")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ACOPIADOR")
    void otrosRolesNoAcceden() throws Exception {
        mvc.perform(get("/api/inventario/familias")).andExpect(status().isForbidden());
    }

    @Test
    void exigeAutenticacion() throws Exception {
        mvc.perform(get("/api/inventario/familias")).andExpect(status().isUnauthorized());
    }

    // Fase 2: existencias (fisico / vendible / por lote) de una presentacion, solo lectura.
    @Test
    @WithMockUser(roles = {"PRODUCCION", "VENTAS", "ADMIN"})
    void produccionVentasYAdminLeenLasExistenciasDeUnaPresentacion() throws Exception {
        UUID presentacionId = UUID.randomUUID();
        when(inventarioService.obtenerExistencias(presentacionId)).thenReturn(new com.planta.acopio.dto.ExistenciasPresentacionResponse(
                presentacionId, true, 100, 70, 10, 20, java.time.LocalDate.of(2026, 10, 11), List.of()));

        mvc.perform(get("/api/inventario/presentaciones/" + presentacionId + "/existencias")).andExpect(status().isOk())
                .andExpect(jsonPath("$.stockFisico").value(100)).andExpect(jsonPath("$.stockVendible").value(70))
                .andExpect(jsonPath("$.stockVencido").value(10)).andExpect(jsonPath("$.stockSinTrazabilidad").value(20));
    }

    @Test
    @WithMockUser(roles = {"ACOPIADOR", "CALIDAD"})
    void otrosRolesNoLeenLasExistencias() throws Exception {
        mvc.perform(get("/api/inventario/presentaciones/" + UUID.randomUUID() + "/existencias")).andExpect(status().isForbidden());
    }
}
