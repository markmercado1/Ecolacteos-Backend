package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.service.ConciliacionService;
import com.planta.acopio.service.LoteProduccionService;
import com.planta.acopio.service.PrecioQuesoService;
import com.planta.acopio.service.BuscadorPresentacionesVentaService;
import com.planta.acopio.service.ComprobanteVentaPdfService;
import com.planta.acopio.service.VentaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Parametro opcional `busqueda` de los listados paginados: el controlador lo pasa tal cual al service (que lo pliega sin
// acentos ni mayusculas) y, si no llega, pasa null (el listado se comporta como siempre: compatible con la app movil).
@WebMvcTest(controllers = {
        VentaController.class, LoteProduccionController.class, RecepcionPlantaController.class
})
@Import({SecurityConfig.class, JwtAuthFilter.class})
class BusquedaParametroWebMvcTest {

    @Autowired private MockMvc mvc;
    @MockBean private VentaService ventaService;
    @MockBean private BuscadorPresentacionesVentaService buscadorPresentacionesVentaService;
    @MockBean private ComprobanteVentaPdfService comprobanteVentaPdfService;
    @MockBean private PrecioQuesoService precioQuesoService;
    @MockBean private LoteProduccionService loteProduccionService;
    @MockBean private ConciliacionService conciliacionService;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    @Test
    @WithMockUser(roles = "VENTAS")
    void ventasPasaLaBusquedaYLaOmiteSiNoLlega() throws Exception {
        when(ventaService.buscar(any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/ventas").param("busqueda", "Fresón").param("tipoCliente", "PUBLICO")).andExpect(status().isOk());
        verify(ventaService).buscar(isNull(), isNull(), eq("PUBLICO"), eq("Fresón"), any());

        mvc.perform(get("/api/ventas")).andExpect(status().isOk());
        verify(ventaService).buscar(isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    void lotesPasaLaBusquedaYLaOmiteSiNoLlega() throws Exception {
        when(loteProduccionService.buscar(any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/lotes-produccion").param("busqueda", "queso")).andExpect(status().isOk());
        verify(loteProduccionService).buscar(isNull(), isNull(), isNull(), eq("queso"), any());

        mvc.perform(get("/api/lotes-produccion")).andExpect(status().isOk());
        verify(loteProduccionService).buscar(isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    @WithMockUser(roles = "RECEPCION")
    void recepcionesPasaLaBusquedaYLaOmiteSiNoLlega() throws Exception {
        when(conciliacionService.listar(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/recepcion-planta").param("busqueda", "ABC-123")).andExpect(status().isOk());
        verify(conciliacionService).listar(isNull(), isNull(), eq("ABC-123"), any());

        mvc.perform(get("/api/recepcion-planta")).andExpect(status().isOk());
        verify(conciliacionService).listar(isNull(), isNull(), isNull(), any());
    }

}
