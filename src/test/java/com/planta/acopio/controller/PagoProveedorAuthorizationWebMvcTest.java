package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.dto.PagoProveedorResponse;
import com.planta.acopio.service.PagoProveedorService;
import com.planta.acopio.service.SaldoPagoService;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Pagos efectivos: ADMIN y RECEPCION registran, aplican y consultan; solo ADMIN anula; los demas roles no ven nada. Los permisos se evaluan
// de verdad (SecurityConfig real); solo los services estan mockeados.
@WebMvcTest(PagoProveedorController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
class PagoProveedorAuthorizationWebMvcTest {

    @Autowired private MockMvc mvc;
    @MockBean private PagoProveedorService service;
    @MockBean private SaldoPagoService saldoService;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    private final UUID id = UUID.randomUUID();
    private final UUID proveedorId = UUID.randomUUID();

    private String registro() {
        return "{\"uuidCliente\":\"" + UUID.randomUUID() + "\",\"proveedorId\":\"" + proveedorId + "\",\"modalidad\":\"EFECTIVO\",\"monto\":100.00}";
    }

    private PagoProveedorResponse respuesta() {
        return new PagoProveedorResponse(id, "u", proveedorId, "Proveedor", LocalDateTime.of(2026, 10, 21, 10, 0), "EFECTIVO", new BigDecimal("100.00"),
                null, null, null, null, null, "Administrador", "REGISTRADO", null, null, null, BigDecimal.ZERO, new BigDecimal("100.00"), List.of());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void adminRegistraYRecibe201() throws Exception {
        when(service.registrar(any())).thenReturn(new PagoProveedorService.Resultado(respuesta(), true));

        mvc.perform(post("/api/pagos-proveedor").contentType(MediaType.APPLICATION_JSON).content(registro()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sinAplicar").value(100.00));
    }

    @Test
    @WithMockUser(roles = {"RECEPCION"})
    void recepcionRegistraYUnReenvioRecibe200() throws Exception {
        when(service.registrar(any())).thenReturn(new PagoProveedorService.Resultado(respuesta(), false));

        mvc.perform(post("/api/pagos-proveedor").contentType(MediaType.APPLICATION_JSON).content(registro())).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"RECEPCION"})
    void recepcionConsultaYAplicaPeroNoAnula() throws Exception {
        when(service.obtener(id)).thenReturn(respuesta());
        when(service.aplicar(any(), any())).thenReturn(respuesta());

        mvc.perform(get("/api/pagos-proveedor/" + id)).andExpect(status().isOk());
        mvc.perform(get("/api/pagos-proveedor/proveedor/" + proveedorId + "/saldo")).andExpect(status().isOk());
        mvc.perform(get("/api/pagos-proveedor/saldos").param("pagoIds", UUID.randomUUID().toString())).andExpect(status().isOk());
        mvc.perform(post("/api/pagos-proveedor/" + id + "/aplicaciones").contentType(MediaType.APPLICATION_JSON)
                .content("{\"aplicaciones\":[{\"pagoId\":\"" + UUID.randomUUID() + "\",\"monto\":50.00}]}")).andExpect(status().isOk());
        mvc.perform(post("/api/pagos-proveedor/" + id + "/anular").contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Error\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void soloAdminAnulaYElMotivoEsObligatorio() throws Exception {
        when(service.anular(any(), any())).thenReturn(respuesta());

        mvc.perform(post("/api/pagos-proveedor/" + id + "/anular").contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Monto mal digitado\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/pagos-proveedor/" + id + "/anular").contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = {"CALIDAD", "VENTAS", "PRODUCCION", "ACOPIADOR"})
    void losDemasRolesNoVenNiHacenNada() throws Exception {
        mvc.perform(post("/api/pagos-proveedor").contentType(MediaType.APPLICATION_JSON).content(registro())).andExpect(status().isForbidden());
        mvc.perform(get("/api/pagos-proveedor")).andExpect(status().isForbidden());
        mvc.perform(get("/api/pagos-proveedor/" + id)).andExpect(status().isForbidden());
        mvc.perform(get("/api/pagos-proveedor/saldos").param("pagoIds", id.toString())).andExpect(status().isForbidden());
        mvc.perform(get("/api/pagos-proveedor/proveedor/" + proveedorId + "/saldo")).andExpect(status().isForbidden());
        mvc.perform(post("/api/pagos-proveedor/" + id + "/aplicaciones").contentType(MediaType.APPLICATION_JSON)
                .content("{\"aplicaciones\":[{\"pagoId\":\"" + id + "\",\"monto\":1.00}]}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/pagos-proveedor/" + id + "/anular").contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"x\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service, saldoService);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void losDatosInvalidosSeRechazanAntesDelService() throws Exception {
        // monto cero, modalidad desconocida y sin proveedor
        mvc.perform(post("/api/pagos-proveedor").contentType(MediaType.APPLICATION_JSON)
                .content("{\"uuidCliente\":\"" + UUID.randomUUID() + "\",\"proveedorId\":\"" + proveedorId + "\",\"modalidad\":\"EFECTIVO\",\"monto\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/pagos-proveedor").contentType(MediaType.APPLICATION_JSON)
                .content("{\"uuidCliente\":\"" + UUID.randomUUID() + "\",\"proveedorId\":\"" + proveedorId + "\",\"modalidad\":\"CHEQUE\",\"monto\":10}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/pagos-proveedor").contentType(MediaType.APPLICATION_JSON)
                .content("{\"uuidCliente\":\"" + UUID.randomUUID() + "\",\"modalidad\":\"EFECTIVO\",\"monto\":10}"))
                .andExpect(status().isBadRequest());
        // aplicar sin aplicaciones
        mvc.perform(post("/api/pagos-proveedor/" + id + "/aplicaciones").contentType(MediaType.APPLICATION_JSON).content("{\"aplicaciones\":[]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void sinAutenticarNoSeAccede() throws Exception {
        mvc.perform(get("/api/pagos-proveedor")).andExpect(status().is4xxClientError());
        verifyNoInteractions(service);
    }
}
