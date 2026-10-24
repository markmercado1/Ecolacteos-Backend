package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.dto.HistorialEntregaResponse;
import com.planta.acopio.dto.HistorialResumenResponse;
import com.planta.acopio.service.HistorialProveedorService;
import com.planta.acopio.service.HistorialProveedorService.Secciones;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Fase D: ADMIN, RECEPCION y CALIDAD consultan el historial consolidado, pero cada uno recibe SOLO sus secciones (calidad: ADMIN y CALIDAD; pagos:
// ADMIN y RECEPCION; zonas: ADMIN). Los permisos se evaluan de verdad (SecurityConfig real); el service esta mockeado y se comprueba que el
// controlador le pide exactamente las secciones del rol.
@WebMvcTest(HistorialProveedorController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
class HistorialProveedorAuthorizationWebMvcTest {

    @Autowired private MockMvc mvc;
    @MockBean private HistorialProveedorService service;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    private final UUID proveedorId = UUID.randomUUID();

    private Secciones seccionesPedidas(String url) throws Exception {
        when(service.resumen(eq(proveedorId), any())).thenReturn(new HistorialResumenResponse(proveedorId, "P", null, List.of("ENTREGAS"), null, null, null, null));
        when(service.entregas(eq(proveedorId), any(Pageable.class), any())).thenReturn(new PageImpl<HistorialEntregaResponse>(List.of()));
        mvc.perform(get(url)).andExpect(status().isOk());
        ArgumentCaptor<Secciones> captura = ArgumentCaptor.forClass(Secciones.class);
        if (url.endsWith("/resumen")) {
            verify(service).resumen(eq(proveedorId), captura.capture());
        } else {
            verify(service).entregas(eq(proveedorId), any(Pageable.class), captura.capture());
        }
        return captura.getValue();
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void adminVeTodasLasSecciones() throws Exception {
        for (String url : List.of("/api/proveedores/" + proveedorId + "/historial/resumen")) {
            assertThat(seccionesPedidas(url)).isEqualTo(new Secciones(true, true, true));
        }
    }

    @Test
    @WithMockUser(roles = {"RECEPCION"})
    void recepcionVeEntregasYPagosPeroNoCalidadNiZonas() throws Exception {
        Secciones s = seccionesPedidas("/api/proveedores/" + proveedorId + "/historial/resumen");
        assertThat(s).isEqualTo(new Secciones(false, true, false));
        assertThat(s.nombres()).containsExactly("ENTREGAS", "PAGOS");
    }

    @Test
    @WithMockUser(roles = {"CALIDAD"})
    void calidadVeEntregasYCalidadPeroNoPagosNiZonas() throws Exception {
        Secciones s = seccionesPedidas("/api/proveedores/" + proveedorId + "/historial/entregas");
        assertThat(s).isEqualTo(new Secciones(true, false, false));
        assertThat(s.nombres()).containsExactly("ENTREGAS", "CALIDAD");
    }

    @Test
    @WithMockUser(roles = {"VENTAS", "PRODUCCION", "ACOPIADOR"})
    void losDemasRolesNoLoConsultan() throws Exception {
        mvc.perform(get("/api/proveedores/" + proveedorId + "/historial/resumen")).andExpect(status().isForbidden());
        mvc.perform(get("/api/proveedores/" + proveedorId + "/historial/entregas")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void sinAutenticarNoSeConsulta() throws Exception {
        mvc.perform(get("/api/proveedores/" + proveedorId + "/historial/resumen")).andExpect(status().is4xxClientError());
        verifyNoInteractions(service);
    }
}
