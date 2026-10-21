package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.dto.CatalogoPublicoResponse;
import com.planta.acopio.exception.GlobalExceptionHandler;
import com.planta.acopio.service.CatalogoPublicoService;
import com.planta.acopio.service.DisponibilidadPublica;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CatalogoPublicoController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, GlobalExceptionHandler.class})
class CatalogoPublicoWebMvcTest {

    @Autowired private MockMvc mvc;
    @MockBean private CatalogoPublicoService service;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    private CatalogoPublicoResponse catalogo() {
        var pres = new CatalogoPublicoResponse.Presentacion(UUID.randomUUID(), "Botella 1 L", BigDecimal.ONE, "L", "botella",
                "/api/catalogo/fotos/x.jpg", new BigDecimal("12.00"), false, DisponibilidadPublica.POCAS_UNIDADES, 3);
        return new CatalogoPublicoResponse(List.of(new CatalogoPublicoResponse.Producto(UUID.randomUUID(), "Yogurt", "Lácteos", null, List.of(pres))));
    }

    @Test
    void esPublicoSinTokenYDevuelveElCatalogoConSuDisponibilidad() throws Exception {
        when(service.obtener()).thenReturn(catalogo());

        mvc.perform(get("/api/catalogo/productos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productos[0].nombre").value("Yogurt"))
                .andExpect(jsonPath("$.productos[0].presentaciones[0].precio").value(12.0))
                .andExpect(jsonPath("$.productos[0].presentaciones[0].disponibilidad").value("POCAS_UNIDADES"))
                .andExpect(jsonPath("$.productos[0].presentaciones[0].unidadesDisponibles").value(3))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=10")));
    }

    @Test
    void laRespuestaNoExponeStockLotesFechasNiCostos() throws Exception {
        when(service.obtener()).thenReturn(catalogo());

        String json = mvc.perform(get("/api/catalogo/productos")).andReturn().getResponse().getContentAsString().toLowerCase();

        for (String prohibido : List.of("stock", "lote", "vencimiento", "saldo", "costo", "trazabilidad", "existencia")) {
            org.assertj.core.api.Assertions.assertThat(json).as("campo interno: " + prohibido).doesNotContain(prohibido);
        }
    }

    @Test
    void soloLaLecturaEsPublicaCualquierOtroMetodoOSubrutaExigeToken() throws Exception {
        mvc.perform(post("/api/catalogo/productos")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/catalogo/productos")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/catalogo/otra-cosa")).andExpect(status().isUnauthorized());
    }
}
