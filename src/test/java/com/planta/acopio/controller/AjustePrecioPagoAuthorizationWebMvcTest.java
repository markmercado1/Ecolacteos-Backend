package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.dto.AjustePrecioPagoResponse;
import com.planta.acopio.service.AjustePagoService;
import com.planta.acopio.service.AjustePrecioPagoService;
import com.planta.acopio.service.BoletaPagoService;
import com.planta.acopio.service.LineaPagoService;
import com.planta.acopio.service.PagoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Ajuste de precio por pago (V24): solo ADMIN ajusta; ADMIN y RECEPCION consultan el historial. Los permisos se
// evaluan de verdad (SecurityConfig real); solo los services estan mockeados.
@WebMvcTest(PagoController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
class AjustePrecioPagoAuthorizationWebMvcTest {

    @Autowired private MockMvc mvc;
    @MockBean private PagoService pagoService;
    @MockBean private AjustePagoService ajustePagoService;
    @MockBean private AjustePrecioPagoService ajustePrecioPagoService;
    @MockBean private BoletaPagoService boletaPagoService;
    @MockBean private LineaPagoService lineaPagoService;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    private final UUID pagoId = UUID.randomUUID();
    private static final String AJUSTE = "{\"precioLitroNuevo\":1.20,\"motivo\":\"Reconocimiento por calidad\"}";

    private AjustePrecioPagoResponse respuesta() {
        return new AjustePrecioPagoResponse(UUID.randomUUID(), pagoId, new BigDecimal("1.000"), new BigDecimal("1.200"),
                new BigDecimal("100.00"), new BigDecimal("120.00"), "Reconocimiento por calidad", "Administrador",
                LocalDateTime.of(2026, 10, 21, 10, 0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminAjustaElPrecioYRecibe201ConLosTotales() throws Exception {
        when(ajustePrecioPagoService.crear(eq(pagoId), any())).thenReturn(respuesta());

        mvc.perform(post("/api/pagos/" + pagoId + "/ajuste-precio").contentType(MediaType.APPLICATION_JSON).content(AJUSTE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAnterior").value(100.00))
                .andExpect(jsonPath("$.totalNuevo").value(120.00));
    }

    @Test
    @WithMockUser(roles = {"RECEPCION", "VENTAS", "CALIDAD", "PRODUCCION", "ACOPIADOR"})
    void ningunOtroRolPuedeAjustarElPrecio() throws Exception {
        mvc.perform(post("/api/pagos/" + pagoId + "/ajuste-precio").contentType(MediaType.APPLICATION_JSON).content(AJUSTE))
                .andExpect(status().isForbidden());

        verifyNoInteractions(ajustePrecioPagoService);
    }

    @Test
    void sinAutenticarNoSePuedeAjustar() throws Exception {
        mvc.perform(post("/api/pagos/" + pagoId + "/ajuste-precio").contentType(MediaType.APPLICATION_JSON).content(AJUSTE))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(ajustePrecioPagoService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rechazaPeticionesInvalidasConBadRequestSinLlegarAlService() throws Exception {
        for (String cuerpo : List.of(
                "{\"precioLitroNuevo\":1.20,\"motivo\":\"  \"}",          // motivo vacío
                "{\"precioLitroNuevo\":1.20}",                             // sin motivo
                "{\"motivo\":\"Motivo\"}",                                 // sin precio
                "{\"precioLitroNuevo\":0,\"motivo\":\"Motivo\"}",          // cero
                "{\"precioLitroNuevo\":-1,\"motivo\":\"Motivo\"}",         // negativo
                "{\"precioLitroNuevo\":1.2345,\"motivo\":\"Motivo\"}",     // más de 3 decimales
                "{\"precioLitroNuevo\":1000,\"motivo\":\"Motivo\"}")) {    // no cabe en NUMERIC(6,3)
            mvc.perform(post("/api/pagos/" + pagoId + "/ajuste-precio").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                    .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(ajustePrecioPagoService);
    }

    @Test
    @WithMockUser(roles = "RECEPCION")
    void recepcionConsultaElHistorialDeAjustes() throws Exception {
        when(ajustePrecioPagoService.listarPorPago(pagoId)).thenReturn(List.of(respuesta()));

        mvc.perform(get("/api/pagos/" + pagoId + "/ajustes-precio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].motivo").value("Reconocimiento por calidad"));
    }

    @Test
    @WithMockUser(roles = "RECEPCION")
    void recepcionYAdminDescarganLaBoletaEnPdfEnLinea() throws Exception {
        when(boletaPagoService.generarPdf(pagoId)).thenReturn("%PDF-1.4 prueba".getBytes());

        mvc.perform(get("/api/pagos/" + pagoId + "/boleta"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("inline; filename=\"boleta-pago-")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    @WithMockUser(roles = {"VENTAS", "CALIDAD", "PRODUCCION", "ACOPIADOR"})
    void otrosRolesNoVenLaBoleta() throws Exception {
        mvc.perform(get("/api/pagos/" + pagoId + "/boleta")).andExpect(status().isForbidden());

        verifyNoInteractions(boletaPagoService);
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    void ventasNoConsultaElHistorialDeAjustes() throws Exception {
        mvc.perform(get("/api/pagos/" + pagoId + "/ajustes-precio")).andExpect(status().isForbidden());

        verifyNoInteractions(ajustePrecioPagoService);
    }
}
