package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.service.InventarioInsumoService;
import com.planta.acopio.service.KardexService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// V19, permisos confirmados: PRODUCCION registra ingresos y consulta los tres kardex; ADMIN ademas ajusta por
// conteo; VENTAS solo consulta el kardex de producto terminado. No existe endpoint para borrar movimientos.
@WebMvcTest({KardexController.class, InventarioInsumoController.class})
@Import({SecurityConfig.class, JwtAuthFilter.class})
class KardexAuthorizationWebMvcTest {
    @Autowired private MockMvc mvc;
    @MockBean private KardexService kardexService;
    @MockBean private InventarioInsumoService inventarioInsumoService;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    private final UUID id = UUID.randomUUID();
    private static final String INGRESO = "{\"cantidad\":10,\"referencia\":\"Guía 1\"}";
    private static final String AJUSTE = "{\"cantidadFisica\":4,\"motivo\":\"Conteo mensual\"}";

    @Test
    @WithMockUser(roles = "PRODUCCION")
    void produccionConsultaLosTresKardexYRegistraIngresosPeroNoAjusta() throws Exception {
        mvc.perform(get("/api/inventario/kardex/leche")).andExpect(status().isOk());
        mvc.perform(get("/api/inventario/kardex/insumos/" + id)).andExpect(status().isOk());
        mvc.perform(get("/api/inventario/kardex/presentaciones/" + id)).andExpect(status().isOk());
        mvc.perform(get("/api/inventario/insumos")).andExpect(status().isOk());
        mvc.perform(post("/api/inventario/insumos/" + id + "/ingresos").contentType(MediaType.APPLICATION_JSON).content(INGRESO))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/inventario/insumos/" + id + "/ajustes").contentType(MediaType.APPLICATION_JSON).content(AJUSTE))
                .andExpect(status().isForbidden());
        verify(inventarioInsumoService, never()).ajustar(any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminConsultaRegistraIngresosYAjusta() throws Exception {
        mvc.perform(get("/api/inventario/kardex/leche")).andExpect(status().isOk());
        mvc.perform(post("/api/inventario/insumos/" + id + "/ingresos").contentType(MediaType.APPLICATION_JSON).content(INGRESO))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/inventario/insumos/" + id + "/ajustes").contentType(MediaType.APPLICATION_JSON).content(AJUSTE))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    void ventasSoloConsultaElKardexDeProductoTerminado() throws Exception {
        mvc.perform(get("/api/inventario/kardex/presentaciones/" + id)).andExpect(status().isOk());
        mvc.perform(get("/api/inventario/kardex/leche")).andExpect(status().isForbidden());
        mvc.perform(get("/api/inventario/kardex/insumos/" + id)).andExpect(status().isForbidden());
        mvc.perform(get("/api/inventario/insumos")).andExpect(status().isForbidden());
        mvc.perform(post("/api/inventario/insumos/" + id + "/ingresos").contentType(MediaType.APPLICATION_JSON).content(INGRESO))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ACOPIADOR"})
    void otrosRolesNoAcceden() throws Exception {
        mvc.perform(get("/api/inventario/kardex/presentaciones/" + id)).andExpect(status().isForbidden());
        mvc.perform(get("/api/inventario/kardex/leche")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void validaElCuerpoDeIngresosYAjustes() throws Exception {
        mvc.perform(post("/api/inventario/insumos/" + id + "/ingresos").contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/inventario/insumos/" + id + "/ajustes").contentType(MediaType.APPLICATION_JSON).content("{\"cantidadFisica\":3,\"motivo\":\" \"}"))
                .andExpect(status().isBadRequest());
        verify(inventarioInsumoService, never()).registrarIngreso(any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void noExisteBorradoDeMovimientos() throws Exception {
        mvc.perform(delete("/api/inventario/insumos/" + id + "/ingresos")).andExpect(status().is4xxClientError());
        mvc.perform(delete("/api/inventario/kardex/insumos/" + id)).andExpect(status().is4xxClientError());
    }

    @Test
    void exigeAutenticacion() throws Exception {
        mvc.perform(get("/api/inventario/kardex/leche")).andExpect(status().isUnauthorized());
    }
}
