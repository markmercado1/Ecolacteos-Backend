package com.planta.acopio.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.dto.CorreccionRegistroRequest;
import com.planta.acopio.dto.RegistroAcopioDTO;
import com.planta.acopio.dto.RegistroAcopioResponse;
import com.planta.acopio.dto.RegistroAcopioResumenResponse;
import com.planta.acopio.service.CorreccionRegistroService;
import com.planta.acopio.service.RegistroAcopioService;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 04 (docs/PROMPTS_FINALIZAR_MOVIL.md), verificación de seguimiento: la primera versión de esta
 * prueba (RegistroAcopioAuthorizationIntegrationTest, con Testcontainers) no pudo ejecutarse en este
 * entorno porque WSL2/virtualización no está disponible (confirmado con `wsl --status`). Esta clase
 * cubre la misma matriz de autorización SIN Docker/Testcontainers, pero conservando real la seguridad:
 *
 * - `@WebMvcTest(RegistroAcopioController.class)` levanta el controller real dentro de un
 *   DispatcherServlet real -- no es una llamada directa a Java que se salte Spring.
 * - `@Import(SecurityConfig.class)` trae el `SecurityFilterChain` y el `@EnableMethodSecurity` REALES de
 *   la aplicación (no una config de seguridad de prueba simplificada) -- `@PreAuthorize` se evalúa tal
 *   cual está escrito en el controller.
 * - `@Import(JwtAuthFilter.class)` trae el filtro JWT real: como ninguna petición de esta clase manda un
 *   header `Authorization`, su primera rama (`if (header == null ...) { filterChain.doFilter(...); return; }`)
 *   se ejecuta de verdad y deja pasar la petición sin alterar el `SecurityContext` -- así que
 *   `@WithMockUser` (que puebla el `SecurityContext` antes del filtro) sigue siendo la identidad real
 *   evaluada por `@PreAuthorize`, sin mockear el propio filtro (un filtro mockeado con @MockBean no
 *   llamaría a `chain.doFilter(...)`, cortando la cadena entera -- por eso se importa la clase real).
 * - Lo único mockeado es lo que aisla de infraestructura externa: `JwtService`/`UsuarioDetailsService`
 *   (dependencias del filtro real, nunca invocadas porque no hay header) y
 *   `RegistroAcopioService`/`CorreccionRegistroService` (para no requerir una base de datos real). Ningún
 *   mock reemplaza la seguridad misma.
 */
@WebMvcTest(RegistroAcopioController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
class RegistroAcopioAuthorizationWebMvcTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private RegistroAcopioService registroAcopioService;
    @MockBean private CorreccionRegistroService correccionRegistroService;

    // Dependencias del JwtAuthFilter real -- nunca se invocan en estas pruebas porque ninguna peticion
    // manda un header Authorization (el filtro corta antes de tocarlas, ver el comentario de la clase).
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    private final UUID registroId = UUID.randomUUID();
    private final UUID proveedorId = UUID.randomUUID();

    // 1a: PRODUCCION puede listar/buscar registros de acopio (antes de esta fase, 403).
    @Test
    @WithMockUser(roles = "PRODUCCION")
    void produccionPuedeBuscarRegistrosDeAcopio() throws Exception {
        when(registroAcopioService.buscar(isNull(), isNull(), isNull())).thenReturn(List.of(registroDeEjemplo()));

        mockMvc.perform(get("/api/registros-acopio"))
                .andExpect(status().isOk());
    }

    // 1b: PRODUCCION puede ver el historial de un proveedor puntual.
    @Test
    @WithMockUser(roles = "PRODUCCION")
    void produccionPuedeVerElHistorialDeUnProveedor() throws Exception {
        when(registroAcopioService.historialPorProveedor(proveedorId))
                .thenReturn(List.of(new RegistroAcopioResumenResponse(registroId, LocalDateTime.now(), BigDecimal.TEN, false, true)));

        mockMvc.perform(get("/api/registros-acopio/proveedor/" + proveedorId))
                .andExpect(status().isOk());
    }

    // 1c: PRODUCCION puede ver el detalle completo de un registro puntual.
    @Test
    @WithMockUser(roles = "PRODUCCION")
    void produccionPuedeVerElDetalleDeUnRegistro() throws Exception {
        when(registroAcopioService.obtener(registroId)).thenReturn(registroDeEjemplo());

        mockMvc.perform(get("/api/registros-acopio/" + registroId))
                .andExpect(status().isOk());
    }

    // 2a: PRODUCCION NO puede crear registros de acopio (rol de escritura sigue siendo solo ACOPIADOR/ADMIN).
    @Test
    @WithMockUser(roles = "PRODUCCION")
    void produccionNoPuedeCrearRegistrosDeAcopio() throws Exception {
        mockMvc.perform(post("/api/registros-acopio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dtoValido())))
                .andExpect(status().isForbidden());

        // El 403 ocurre antes de invocar el metodo del controller/service -- nunca se intento crear nada.
        verifyNoInteractions(registroAcopioService);
    }

    // 2b: PRODUCCION NO puede anexar correcciones (sigue siendo solo CALIDAD/ADMIN).
    @Test
    @WithMockUser(roles = "PRODUCCION")
    void produccionNoPuedeAnexarCorrecciones() throws Exception {
        var correccion = new CorreccionRegistroRequest(BigDecimal.valueOf(45.5), "Ajuste de prueba");

        mockMvc.perform(post("/api/registros-acopio/" + registroId + "/correcciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correccion)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(correccionRegistroService);
    }

    // 3: un rol sin relacion con este recurso (VENTAS) sigue en 403 en las tres lecturas, igual que antes
    // de agregar PRODUCCION -- el cambio de la Fase 04 no abrio la puerta a nadie mas.
    @Test
    @WithMockUser(roles = "VENTAS")
    void ventasSigueRecibiendo403EnLasTresLecturas() throws Exception {
        mockMvc.perform(get("/api/registros-acopio")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/registros-acopio/proveedor/" + proveedorId)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/registros-acopio/" + registroId)).andExpect(status().isForbidden());

        verifyNoInteractions(registroAcopioService);
    }

    // Control negativo: sin @WithMockUser (sin autenticacion alguna) el filtro real deja pasar la
    // peticion sin poblar el SecurityContext, y el entry point real responde 401 -- confirma que estas
    // pruebas realmente dependen de la autenticacion/autorizacion real, no de un atajo del test.
    @Test
    void sinAutenticacionDevuelve401NoForbidden() throws Exception {
        mockMvc.perform(get("/api/registros-acopio")).andExpect(status().isUnauthorized());
        verifyNoInteractions(registroAcopioService);
    }

    private RegistroAcopioResponse registroDeEjemplo() {
        return new RegistroAcopioResponse(
                registroId, "uuid-cliente-test", proveedorId, "Proveedor Test", UUID.randomUUID(),
                LocalDateTime.now(), BigDecimal.TEN, null, null, null, false, LocalDateTime.now(), null, true, null);
    }

    private RegistroAcopioDTO dtoValido() {
        return new RegistroAcopioDTO(
                "uuid-cliente-" + System.nanoTime(), proveedorId, UUID.randomUUID(),
                LocalDateTime.now(), BigDecimal.valueOf(45.5), null, null, null, false, null, null);
    }
}
