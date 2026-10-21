package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.exception.GlobalExceptionHandler;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.service.FotoCatalogoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Las fotos del catalogo son PUBLICAS (sin token); las de acopio (/api/archivos) siguen protegidas. */
@WebMvcTest(CatalogoFotosController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, GlobalExceptionHandler.class})
class CatalogoFotosWebMvcTest {

    @Autowired private MockMvc mvc;
    @MockBean private FotoCatalogoService fotoCatalogoService;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    @TempDir Path tmp;

    @Test
    void cualquierVisitanteVeLaFotoSinTokenConCacheLargo() throws Exception {
        Path jpg = tmp.resolve("abc.jpg");
        Files.write(jpg, new byte[]{1, 2, 3});
        when(fotoCatalogoService.resolver("abc.jpg")).thenReturn(jpg);

        mvc.perform(get("/api/catalogo/fotos/abc.jpg"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("immutable")))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void unaFotoInexistenteDevuelve404() throws Exception {
        when(fotoCatalogoService.resolver("no-existe.jpg")).thenThrow(new RecursoNoEncontradoException("Foto no encontrada"));

        mvc.perform(get("/api/catalogo/fotos/no-existe.jpg")).andExpect(status().isNotFound());
    }

    @Test
    void laRutaPublicaSoloPermiteLecturaSinToken() throws Exception {
        // Escribir en esa ruta sin token sigue siendo 401: permitAll aplica unicamente a GET.
        mvc.perform(put("/api/catalogo/fotos/abc.jpg")).andExpect(status().isUnauthorized());
    }
}
