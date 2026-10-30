package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.dto.ReservaPublicaResponse;
import com.planta.acopio.dto.ReservaResponse;
import com.planta.acopio.dto.VentaResumenResponse;
import com.planta.acopio.entity.EstadoReserva;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.GlobalExceptionHandler;
import com.planta.acopio.service.ReglaVencimiento;
import com.planta.acopio.service.ReservaService;
import com.planta.acopio.service.BuscadorPresentacionesVentaService;
import com.planta.acopio.service.ComprobanteVentaPdfService;
import com.planta.acopio.service.VentaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Fase 4: permisos y validacion de entrada de las reservas. Publico: solo crear y consultar la PROPIA. VENTAS y ADMIN
// gestionan; cualquier otro rol o un visitante sin token no.
@WebMvcTest({ReservaPublicaController.class, ReservaController.class})
@Import({SecurityConfig.class, JwtAuthFilter.class, GlobalExceptionHandler.class})
class ReservaWebMvcTest {

    @Autowired private MockMvc mvc;
    @MockBean private ReservaService service;
    @MockBean private VentaService ventaService;
    @MockBean private BuscadorPresentacionesVentaService buscadorPresentacionesVentaService;
    @MockBean private ComprobanteVentaPdfService comprobanteVentaPdfService;
    @MockBean private ReglaVencimiento regla;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    private final UUID id = UUID.randomUUID();
    private final UUID presentacionId = UUID.randomUUID();

    private ReservaPublicaResponse publica() {
        return new ReservaPublicaResponse("ABCD234567", EstadoReserva.PENDIENTE, LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 14),
                LocalDateTime.of(2026, 10, 12, 10, 0), false, new BigDecimal("30.00"),
                List.of(new ReservaPublicaResponse.Linea(presentacionId, "Yogurt", "Botella 1 L", "botella", 2, new BigDecimal("15.00"), new BigDecimal("30.00"))));
    }

    private ReservaResponse detalle() {
        return new ReservaResponse(id, "ABCD234567", EstadoReserva.CONFIRMADA, "Ana", "+51987654321", null, null, LocalDate.of(2026, 10, 13),
                LocalDate.of(2026, 10, 14), LocalDateTime.of(2026, 10, 11, 9, 0), LocalDateTime.of(2026, 10, 12, 9, 0), new BigDecimal("30.00"),
                false, false, new ReservaResponse.Aceptacion(null, null, null, null, null), false, null, List.of(), List.of());
    }

    private String crearJson(String nombre, String telefono, String lineas) {
        return "{\"uuidCliente\":\"" + UUID.randomUUID() + "\",\"nombreCliente\":" + (nombre == null ? "null" : "\"" + nombre + "\"")
                + ",\"telefono\":" + (telefono == null ? "null" : "\"" + telefono + "\"") + ",\"fechaRetiro\":\"2026-10-13\",\"lineas\":" + lineas + "}";
    }

    private String lineaOk() { return "[{\"presentacionId\":\"" + presentacionId + "\",\"cantidad\":2}]"; }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String cuerpo) {
        return b.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    // ------------------------------------------------------------------ publico

    @Test
    void crearEsPublicoSinToken() throws Exception {
        when(service.crear(any())).thenReturn(publica());

        mvc.perform(json(post("/api/catalogo/reservas"), crearJson("Ana", "987 654 321", lineaOk())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.codigo").value("ABCD234567"))
                .andExpect(jsonPath("$.estado").value("PENDIENTE")).andExpect(jsonPath("$.total").value(30.0));
    }

    @Test
    void consultarEsPublicoSinToken() throws Exception {
        when(service.consultar(any())).thenReturn(publica());

        mvc.perform(json(post("/api/catalogo/reservas/consulta"), "{\"codigo\":\"ABCD234567\",\"telefono\":\"987654321\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.codigo").value("ABCD234567"));
    }

    @Test
    void laRespuestaPublicaNoExponeDatosDelClienteNiNotasInternas() throws Exception {
        when(service.crear(any())).thenReturn(publica());

        String cuerpo = mvc.perform(json(post("/api/catalogo/reservas"), crearJson("Ana", "987654321", lineaOk())))
                .andReturn().getResponse().getContentAsString().toLowerCase();

        for (String prohibido : List.of("telefono", "nombrecliente", "correo", "motivo", "usuario", "ventaid", "aceptad")) {
            org.assertj.core.api.Assertions.assertThat(cuerpo).as(prohibido).doesNotContain(prohibido);
        }
    }

    @Test
    void laEntradaPublicaSeValidaYLosErroresDeNegocioSalenConSuCodigo() throws Exception {
        for (String cuerpo : List.of(
                crearJson(null, "987654321", lineaOk()),
                crearJson("   ", "987654321", lineaOk()),
                crearJson("Ana", null, lineaOk()),
                crearJson("Ana", "987654321", "[]"),
                crearJson("Ana", "987654321", "null"),
                crearJson("Ana", "987654321", "[{\"presentacionId\":\"" + presentacionId + "\",\"cantidad\":0}]"),
                crearJson("Ana", "987654321", "[{\"presentacionId\":\"" + presentacionId + "\",\"cantidad\":-2}]"),
                crearJson("Ana", "987654321", "[{\"presentacionId\":null,\"cantidad\":1}]"),
                crearJson("N".repeat(81), "987654321", lineaOk()),
                "{\"nombreCliente\":\"Ana\",\"telefono\":\"987654321\",\"lineas\":" + lineaOk() + "}",
                "{no es json")) {
            mvc.perform(json(post("/api/catalogo/reservas"), cuerpo)).andExpect(status().isBadRequest());
        }
        String once = "[" + String.join(",", java.util.Collections.nCopies(11, "{\"presentacionId\":\"" + UUID.randomUUID() + "\",\"cantidad\":1}")) + "]";
        mvc.perform(json(post("/api/catalogo/reservas"), crearJson("Ana", "987654321", once))).andExpect(status().isBadRequest());

        when(service.crear(any())).thenThrow(new ConflictoNegocioException("Este teléfono ya tiene 2 reservas pendientes."));
        mvc.perform(json(post("/api/catalogo/reservas"), crearJson("Ana", "987654321", lineaOk()))).andExpect(status().isConflict());
    }

    @Test
    void lasReglasDeReservaSonPublicasYSoloLectura() throws Exception {
        when(service.reglasPublicas()).thenReturn(new com.planta.acopio.dto.ReglasReservaPublicaResponse(
                List.of(java.time.LocalDate.of(2026, 10, 13)), 20, 24));
        mvc.perform(get("/api/catalogo/reservas/reglas")).andExpect(status().isOk())
                .andExpect(jsonPath("$.fechasDisponibles[0]").value("2026-10-13"))
                .andExpect(jsonPath("$.maxUnidades").value(20))
                .andExpect(jsonPath("$.horasPendiente").value(24));
        mvc.perform(put("/api/catalogo/reservas/reglas")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/catalogo/reservas/reglas")).andExpect(status().isUnauthorized());
    }

    @Test
    void elVisitanteNoPuedeCambiarEstadosNiListarNiVerDetalle() throws Exception {
        mvc.perform(put("/api/reservas/" + id + "/confirmar")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reservas")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reservas/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/reservas/" + id + "/retirar"), "{\"uuidCliente\":\"x\"}")).andExpect(status().isUnauthorized());
        // Nada mas bajo /api/catalogo/reservas es publico.
        mvc.perform(put("/api/catalogo/reservas/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/catalogo/reservas")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/catalogo/reservas/otra")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ personal

    @Test
    @WithMockUser(roles = {"VENTAS", "ADMIN"})
    void ventasYAdminGestionanReservas() throws Exception {
        when(service.listar(any(), any(), any(), any(), any(), any())).thenReturn(new PaginaResponse<>(List.of(), 0, 20, 0, 0));
        when(service.obtener(id)).thenReturn(detalle());
        when(service.confirmar(id)).thenReturn(detalle());
        when(service.rechazar(eq(id), any())).thenReturn(detalle());
        when(service.cancelar(eq(id), any())).thenReturn(detalle());
        when(service.recotizar(id)).thenReturn(detalle());
        when(service.registrarAceptacionDePrecio(eq(id), any())).thenReturn(detalle());

        mvc.perform(get("/api/reservas?estado=PENDIENTE&fechaRetiro=2026-10-13&telefono=987654321&codigo=abcd234567")).andExpect(status().isOk());
        verify(service).listar(eq("PENDIENTE"), eq(java.time.LocalDate.of(2026, 10, 13)), eq("987654321"), eq("abcd234567"), org.mockito.ArgumentMatchers.isNull(), any());
        mvc.perform(get("/api/reservas?busqueda=José Muñoz")).andExpect(status().isOk());
        verify(service).listar(isNull(), isNull(), isNull(), isNull(), eq("José Muñoz"), any());
        mvc.perform(get("/api/reservas/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.codigo").value("ABCD234567"));
        mvc.perform(put("/api/reservas/" + id + "/confirmar")).andExpect(status().isOk());
        mvc.perform(put("/api/reservas/" + id + "/recotizar")).andExpect(status().isOk());
        mvc.perform(json(put("/api/reservas/" + id + "/rechazar"), "{\"motivo\":\"Sin stock\"}")).andExpect(status().isOk());
        mvc.perform(json(put("/api/reservas/" + id + "/cancelar"), "{\"motivo\":\"El cliente no viene\"}")).andExpect(status().isOk());
        mvc.perform(json(post("/api/reservas/" + id + "/aceptacion-precio"), "{\"medio\":\"WHATSAPP\"}")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"ACOPIADOR", "CALIDAD", "RECEPCION", "PRODUCCION"})
    void otrosRolesNoGestionanReservas() throws Exception {
        mvc.perform(get("/api/reservas")).andExpect(status().isForbidden());
        mvc.perform(get("/api/reservas/" + id)).andExpect(status().isForbidden());
        mvc.perform(put("/api/reservas/" + id + "/confirmar")).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/reservas/" + id + "/rechazar"), "{\"motivo\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/reservas/" + id + "/cancelar"), "{\"motivo\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/reservas/" + id + "/retirar"), "{\"uuidCliente\":\"x\"}")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    void rechazarYCancelarExigenMotivoYLaAceptacionUnMedioValido() throws Exception {
        for (String ruta : List.of("rechazar", "cancelar")) {
            mvc.perform(json(put("/api/reservas/" + id + "/" + ruta), "{}")).andExpect(status().isBadRequest());
            mvc.perform(json(put("/api/reservas/" + id + "/" + ruta), "{\"motivo\":\"   \"}")).andExpect(status().isBadRequest());
            mvc.perform(json(put("/api/reservas/" + id + "/" + ruta), "{\"motivo\":\"" + "m".repeat(301) + "\"}")).andExpect(status().isBadRequest());
        }
        mvc.perform(json(post("/api/reservas/" + id + "/aceptacion-precio"), "{\"medio\":\"PALOMA\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/reservas/" + id + "/aceptacion-precio"), "{}")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    void elRetiroCreaLaVentaPorElFlujoNormalYDevuelve201() throws Exception {
        when(regla.hoy()).thenReturn(LocalDate.of(2026, 10, 13));
        when(ventaService.registrarRetiroDeReserva(eq(id), eq("venta-1"), eq(LocalDate.of(2026, 10, 13))))
                .thenReturn(new VentaResumenResponse(UUID.randomUUID(), LocalDate.of(2026, 10, 13), "PUBLICO", List.of(), new BigDecimal("30.00")));

        mvc.perform(json(post("/api/reservas/" + id + "/retirar"), "{\"uuidCliente\":\"venta-1\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.tipoCliente").value("PUBLICO"));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    void unEstadoInvalidoSeInformaComoConflicto() throws Exception {
        when(service.confirmar(id)).thenThrow(new ConflictoNegocioException("No se puede confirmar una reserva en estado RECHAZADA."));

        mvc.perform(put("/api/reservas/" + id + "/confirmar")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("No se puede confirmar una reserva en estado RECHAZADA."));
    }
}
