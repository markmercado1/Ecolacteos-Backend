package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.dto.ControlVencimientoResponse;
import com.planta.acopio.dto.ProductosConControlResponse;
import com.planta.acopio.dto.RegularizacionResponse;
import com.planta.acopio.dto.VencimientoLoteResponse;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.GlobalExceptionHandler;
import com.planta.acopio.service.ControlVencimientoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ControlVencimientoController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, GlobalExceptionHandler.class})
class ControlVencimientoWebMvcTest {

    @Autowired private MockMvc mvc;
    @MockBean private ControlVencimientoService service;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    private final UUID productoId = UUID.randomUUID();
    private final UUID detalleId = UUID.randomUUID();

    private String url() { return "/api/admin/productos/" + productoId + "/control-vencimiento"; }
    private String urlCorreccion() { return "/api/lotes-produccion/detalles/" + detalleId + "/vencimiento"; }
    private String cuerpo(String fecha, String motivo) {
        return "{\"fechaVencimiento\":\"" + fecha + "\",\"motivo\":" + (motivo == null ? "null" : "\"" + motivo + "\"") + "}";
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminActivaYDesactivaElControl() throws Exception {
        when(service.activar(productoId)).thenReturn(new ControlVencimientoResponse(productoId, true, List.of()));
        when(service.desactivar(productoId)).thenReturn(new ControlVencimientoResponse(productoId, false, List.of()));

        mvc.perform(put(url())).andExpect(status().isOk()).andExpect(jsonPath("$.controlaVencimiento").value(true));
        mvc.perform(delete(url())).andExpect(status().isOk()).andExpect(jsonPath("$.controlaVencimiento").value(false));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void laActivacionDeshabilitadaDevuelve409() throws Exception {
        when(service.activar(productoId)).thenThrow(new ConflictoNegocioException("La activación está deshabilitada"));

        mvc.perform(put(url())).andExpect(status().isConflict()).andExpect(jsonPath("$.mensaje").value("La activación está deshabilitada"));
    }

    @Test
    @WithMockUser(roles = {"PRODUCCION", "VENTAS", "CALIDAD"})
    void soloAdminPuedeActivarODesactivar() throws Exception {
        mvc.perform(put(url())).andExpect(status().isForbidden());
        mvc.perform(delete(url())).andExpect(status().isForbidden());
    }

    @Test
    void sinTokenSeRechazaConUnauthorized() throws Exception {
        mvc.perform(put(url())).andExpect(status().isUnauthorized());
        mvc.perform(patch(urlCorreccion()).contentType(MediaType.APPLICATION_JSON).content(cuerpo("2026-12-01", "x")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    void produccionPuedeCorregirUnaFecha() throws Exception {
        when(service.corregirVencimiento(any(), any())).thenReturn(new VencimientoLoteResponse(detalleId, UUID.randomUUID(),
                UUID.randomUUID(), LocalDate.of(2026, 12, 1), LocalDate.of(2027, 12, 1), null, "x"));

        mvc.perform(patch(urlCorreccion()).contentType(MediaType.APPLICATION_JSON).content(cuerpo("2026-12-01", "x")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fechaVencimiento").value("2026-12-01"))
                .andExpect(jsonPath("$.fechaVencimientoOriginal").value("2027-12-01"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminTambienPuedeCorregirUnaFecha() throws Exception {
        when(service.corregirVencimiento(any(), any())).thenReturn(new VencimientoLoteResponse(detalleId, UUID.randomUUID(),
                UUID.randomUUID(), LocalDate.of(2026, 12, 1), null, null, "x"));

        mvc.perform(patch(urlCorreccion()).contentType(MediaType.APPLICATION_JSON).content(cuerpo("2026-12-01", "x")))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"VENTAS", "CALIDAD", "RECEPCION", "ACOPIADOR"})
    void otrosRolesNoPuedenCorregirFechas() throws Exception {
        mvc.perform(patch(urlCorreccion()).contentType(MediaType.APPLICATION_JSON).content(cuerpo("2026-12-01", "x")))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    void laCorreccionExigeFechaYMotivo() throws Exception {
        mvc.perform(patch(urlCorreccion()).contentType(MediaType.APPLICATION_JSON).content(cuerpo("2026-12-01", null)))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(urlCorreccion()).contentType(MediaType.APPLICATION_JSON).content(cuerpo("2026-12-01", "   ")))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(urlCorreccion()).contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(urlCorreccion()).contentType(MediaType.APPLICATION_JSON).content(cuerpo("no-es-fecha", "x")))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(urlCorreccion()).contentType(MediaType.APPLICATION_JSON).content(cuerpo("2026-12-01", "m".repeat(301))))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ Fase 2

    private final UUID presentacionId = UUID.randomUUID();

    private String urlRegularizacion() { return "/api/admin/presentaciones/" + presentacionId + "/regularizaciones"; }

    private String cuerpoRegularizacion(String cantidad, String fecha, String motivo) {
        return "{\"cantidad\":" + cantidad + ",\"fechaVencimiento\":" + (fecha == null ? "null" : "\"" + fecha + "\"")
                + ",\"motivo\":" + (motivo == null ? "null" : "\"" + motivo + "\"") + "}";
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminRegularizaYObtiene201() throws Exception {
        when(service.regularizar(any(), any())).thenReturn(new RegularizacionResponse(UUID.randomUUID(), presentacionId, 15,
                LocalDate.of(2026, 11, 20), false, "Etiqueta", null, 25));

        mvc.perform(post(urlRegularizacion()).contentType(MediaType.APPLICATION_JSON).content(cuerpoRegularizacion("15", "2026-11-20", "Etiqueta")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.cantidad").value(15))
                .andExpect(jsonPath("$.sinTrazabilidadRestante").value(25));
    }

    @Test
    @WithMockUser(roles = {"PRODUCCION", "VENTAS", "CALIDAD", "RECEPCION", "ACOPIADOR"})
    void soloAdminPuedeRegularizar() throws Exception {
        mvc.perform(post(urlRegularizacion()).contentType(MediaType.APPLICATION_JSON).content(cuerpoRegularizacion("15", "2026-11-20", "x")))
                .andExpect(status().isForbidden());
    }

    @Test
    void regularizarSinTokenEsUnauthorized() throws Exception {
        mvc.perform(post(urlRegularizacion()).contentType(MediaType.APPLICATION_JSON).content(cuerpoRegularizacion("15", "2026-11-20", "x")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void regularizarExigeCantidadPositivaFechaYMotivo() throws Exception {
        for (String cuerpo : List.of(
                cuerpoRegularizacion("0", "2026-11-20", "x"),
                cuerpoRegularizacion("-3", "2026-11-20", "x"),
                cuerpoRegularizacion("null", "2026-11-20", "x"),
                cuerpoRegularizacion("5", null, "x"),
                cuerpoRegularizacion("5", "no-es-fecha", "x"),
                cuerpoRegularizacion("5", "2026-11-20", null),
                cuerpoRegularizacion("5", "2026-11-20", "   "),
                cuerpoRegularizacion("5", "2026-11-20", "m".repeat(301)))) {
            mvc.perform(post(urlRegularizacion()).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @WithMockUser(roles = {"PRODUCCION", "VENTAS", "ADMIN"})
    void produccionVentasYAdminConsultanLosProductosConControl() throws Exception {
        UUID p = UUID.randomUUID();
        when(service.productosConControl()).thenReturn(new ProductosConControlResponse(List.of(p)));

        mvc.perform(get("/api/control-vencimiento/productos")).andExpect(status().isOk())
                .andExpect(jsonPath("$.productoIds[0]").value(p.toString()));
    }

    @Test
    @WithMockUser(roles = {"CALIDAD", "RECEPCION", "ACOPIADOR"})
    void otrosRolesNoConsultanLosProductosConControl() throws Exception {
        mvc.perform(get("/api/control-vencimiento/productos")).andExpect(status().isForbidden());
    }
}
