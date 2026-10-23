package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.dto.AjusteCalidadEntregaResponse;
import com.planta.acopio.service.AjusteCalidadEntregaService;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Fase C: precio de calidad por entrega. Solo ADMIN lo aplica; ADMIN, RECEPCION y CALIDAD consultan el historial. Los permisos se evaluan
// de verdad (SecurityConfig real); solo los services estan mockeados.
@WebMvcTest(PagoController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
class AjusteCalidadEntregaAuthorizationWebMvcTest {

    @Autowired private MockMvc mvc;
    @MockBean private PagoService pagoService;
    @MockBean private AjustePagoService ajustePagoService;
    @MockBean private AjustePrecioPagoService ajustePrecioPagoService;
    @MockBean private BoletaPagoService boletaPagoService;
    @MockBean private LineaPagoService lineaPagoService;
    @MockBean private AjusteCalidadEntregaService ajusteCalidadEntregaService;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    private final UUID pagoId = UUID.randomUUID();
    private final UUID registroId = UUID.randomUUID();
    private final UUID analisisId = UUID.randomUUID();

    private String url() {
        return "/api/pagos/" + pagoId + "/lineas/" + registroId + "/ajuste-calidad";
    }

    private String cuerpo() {
        return "{\"precioLitroNuevo\":0.90,\"analisisCalidadId\":\"" + analisisId + "\",\"motivo\":\"Agua en la leche\"}";
    }

    private AjusteCalidadEntregaResponse respuesta() {
        return new AjusteCalidadEntregaResponse(UUID.randomUUID(), pagoId, registroId, analisisId, "F-001", "OBSERVADO",
                new BigDecimal("100.00"), new BigDecimal("1.000"), new BigDecimal("1.000"), new BigDecimal("0.900"),
                new BigDecimal("-10.00"), "Agua en la leche", "Administrador", LocalDateTime.of(2026, 10, 21, 10, 0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminAplicaElPrecioDeCalidadYRecibe201ConElEfectoEnElTotal() throws Exception {
        when(ajusteCalidadEntregaService.crear(eq(pagoId), eq(registroId), any())).thenReturn(respuesta());

        mvc.perform(post(url()).contentType(MediaType.APPLICATION_JSON).content(cuerpo()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.efectoEnElTotal").value(-10.00))
                .andExpect(jsonPath("$.analisisFolio").value("F-001"));
    }

    @Test
    @WithMockUser(roles = {"RECEPCION", "VENTAS", "CALIDAD", "PRODUCCION", "ACOPIADOR"})
    void ningunOtroRolPuedeAplicarUnPrecioDeCalidad() throws Exception {
        mvc.perform(post(url()).contentType(MediaType.APPLICATION_JSON).content(cuerpo())).andExpect(status().isForbidden());

        verifyNoInteractions(ajusteCalidadEntregaService);
    }

    @Test
    void sinAutenticarNoSePuedeAplicar() throws Exception {
        mvc.perform(post(url()).contentType(MediaType.APPLICATION_JSON).content(cuerpo())).andExpect(status().is4xxClientError());

        verifyNoInteractions(ajusteCalidadEntregaService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rechazaPeticionesInvalidasConBadRequestSinLlegarAlService() throws Exception {
        for (String c : List.of(
                "{\"precioLitroNuevo\":0.90,\"analisisCalidadId\":\"" + analisisId + "\",\"motivo\":\"  \"}",   // motivo vacio
                "{\"precioLitroNuevo\":0.90,\"analisisCalidadId\":\"" + analisisId + "\"}",                      // sin motivo
                "{\"precioLitroNuevo\":0.90,\"motivo\":\"Motivo\"}",                                             // sin analisis (es obligatorio)
                "{\"analisisCalidadId\":\"" + analisisId + "\",\"motivo\":\"Motivo\"}",                          // sin precio
                "{\"precioLitroNuevo\":0,\"analisisCalidadId\":\"" + analisisId + "\",\"motivo\":\"Motivo\"}",   // cero
                "{\"precioLitroNuevo\":-1,\"analisisCalidadId\":\"" + analisisId + "\",\"motivo\":\"Motivo\"}",  // negativo
                "{\"precioLitroNuevo\":0.9001,\"analisisCalidadId\":\"" + analisisId + "\",\"motivo\":\"Motivo\"}", // mas de 3 decimales
                "{\"precioLitroNuevo\":0.90,\"analisisCalidadId\":\"" + analisisId + "\",\"motivo\":\"" + "x".repeat(501) + "\"}")) { // motivo largo
            mvc.perform(post(url()).contentType(MediaType.APPLICATION_JSON).content(c)).andExpect(status().isBadRequest());
        }

        verifyNoInteractions(ajusteCalidadEntregaService);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void adminConsultaElHistorial() throws Exception {
        when(ajusteCalidadEntregaService.listarPorPago(pagoId)).thenReturn(List.of(respuesta()));

        mvc.perform(get("/api/pagos/" + pagoId + "/ajustes-calidad"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].motivo").value("Agua en la leche"));
    }

    @Test
    @WithMockUser(roles = "RECEPCION")
    void recepcionConsultaElHistorial() throws Exception {
        when(ajusteCalidadEntregaService.listarPorPago(pagoId)).thenReturn(List.of(respuesta()));

        mvc.perform(get("/api/pagos/" + pagoId + "/ajustes-calidad")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CALIDAD")
    void calidadConsultaElHistorialPeroNoVeLosPagos() throws Exception {
        when(ajusteCalidadEntregaService.listarPorPago(pagoId)).thenReturn(List.of(respuesta()));

        mvc.perform(get("/api/pagos/" + pagoId + "/ajustes-calidad")).andExpect(status().isOk());
        mvc.perform(get("/api/pagos/" + pagoId)).andExpect(status().isForbidden());          // sigue sin leer pagos
        mvc.perform(get("/api/pagos/" + pagoId + "/lineas")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"VENTAS", "PRODUCCION", "ACOPIADOR"})
    void otrosRolesNoConsultanElHistorial() throws Exception {
        mvc.perform(get("/api/pagos/" + pagoId + "/ajustes-calidad")).andExpect(status().isForbidden());

        verifyNoInteractions(ajusteCalidadEntregaService);
    }
}
