package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.excel.ArchivoExcel;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.importacion.ImportacionController;
import com.planta.acopio.importacion.ImportacionService;
import com.planta.acopio.importacion.ResultadoImportacionResponse;
import com.planta.acopio.importacion.VistaPreviaImportacionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// La importacion es solo para ADMIN y se valida en el backend: cualquier otro rol (o sin sesion) no llega al servicio.
@WebMvcTest(ImportacionController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
class ImportacionControllerWebMvcTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired private MockMvc mvc;
    @MockBean private ImportacionService service;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    private final UUID id = UUID.randomUUID();
    private final MockMultipartFile archivo = new MockMultipartFile("archivo", "p.xlsx", XLSX, new byte[]{'P', 'K', 3, 4});

    private ArchivoExcel archivoTemporal(String nombre) throws Exception {
        Path ruta = Files.createTempFile("ecolacteos-xlsx-", ".xlsx");
        Files.write(ruta, new byte[]{1, 2, 3});
        return new ArchivoExcel(ruta, nombre, 0);
    }

    private VistaPreviaImportacionResponse vista() {
        return new VistaPreviaImportacionResponse(id, "proveedores", "Proveedores", 1, "p.xlsx", 3, 2, 1, 0, 0,
                LocalDateTime.now().plusMinutes(30), true, List.of(), List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACOPIADOR", "CALIDAD", "RECEPCION", "PRODUCCION", "VENTAS"})
    void ningunRolQueNoSeaAdminImportaNiBajaLaPlantilla(String rol) throws Exception {
        mvc.perform(get("/api/admin/importaciones/proveedores/plantilla").with(user("x").roles(rol))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/admin/importaciones/proveedores/previsualizar").file(archivo).with(user("x").roles(rol))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/importaciones/" + id + "/confirmar").with(user("x").roles(rol))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/importaciones/" + id + "/reporte").with(user("x").roles(rol))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/importaciones/" + id).with(user("x").roles(rol))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void sinSesionTampocoSePuede() throws Exception {
        mvc.perform(get("/api/admin/importaciones/proveedores/plantilla")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/admin/importaciones/proveedores/previsualizar").file(archivo)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/importaciones/" + id + "/confirmar")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void adminDescargaLaPlantillaComoXlsx() throws Exception {
        when(service.plantilla("proveedores")).thenReturn(archivoTemporal("plantilla-proveedores-v1.xlsx"));

        mvc.perform(get("/api/admin/importaciones/proveedores/plantilla").with(user("a").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", XLSX))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("plantilla-proveedores-v1.xlsx")));
    }

    @Test
    void adminPrevisualizaConUnArchivoMultipartYNoSeConfirmaNada() throws Exception {
        when(service.previsualizar(eq("proveedores"), any())).thenReturn(vista());

        mvc.perform(multipart("/api/admin/importaciones/proveedores/previsualizar").file(archivo).with(user("a").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.validas").value(2))
                .andExpect(jsonPath("$.puedeConfirmar").value(true));
        verify(service, never()).confirmar(any());
    }

    @Test
    void unaPeticionSinArchivoSeRechazaConUn400() throws Exception {
        mvc.perform(multipart("/api/admin/importaciones/proveedores/previsualizar").with(user("a").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("archivo")));
    }

    @Test
    void unArchivoInvalidoSeInformaConElMensajeDeNegocio() throws Exception {
        when(service.previsualizar(eq("proveedores"), any())).thenThrow(new ValidacionNegocioException("El archivo no es un .xlsx válido o está dañado."));

        mvc.perform(multipart("/api/admin/importaciones/proveedores/previsualizar").file(archivo).with(user("a").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El archivo no es un .xlsx válido o está dañado."));
    }

    @Test
    void confirmarDevuelveElResultadoYUnConflictoSeInformaConSuMensaje() throws Exception {
        when(service.confirmar(id)).thenReturn(new ResultadoImportacionResponse(id, "proveedores", "Proveedores", 2, 1, 0, 0, false,
                LocalDateTime.now(), List.of(), List.of()));
        mvc.perform(post("/api/admin/importaciones/" + id + "/confirmar").with(user("a").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creadas").value(2))
                .andExpect(jsonPath("$.yaConfirmada").value(false));

        UUID otro = UUID.randomUUID();
        when(service.confirmar(otro)).thenThrow(new ConflictoNegocioException("No se creó ningún registro. La fila 4 no se pudo guardar."));
        mvc.perform(post("/api/admin/importaciones/" + otro + "/confirmar").with(user("a").roles("ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("No se creó ningún registro")));
    }

    @Test
    void elIdentificadorMalFormadoEsUn400YElReporteEsUnXlsx() throws Exception {
        mvc.perform(post("/api/admin/importaciones/no-es-un-uuid/confirmar").with(user("a").roles("ADMIN"))).andExpect(status().isBadRequest());

        when(service.reporte(id)).thenReturn(archivoTemporal("reporte-proveedores-20261022-1100.xlsx"));
        mvc.perform(get("/api/admin/importaciones/" + id + "/reporte").with(user("a").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", XLSX));
    }

    @Test
    void cancelarDevuelveSinContenido() throws Exception {
        mvc.perform(delete("/api/admin/importaciones/" + id).with(user("a").roles("ADMIN"))).andExpect(status().isNoContent());
        verify(service).descartar(id);
    }
}
