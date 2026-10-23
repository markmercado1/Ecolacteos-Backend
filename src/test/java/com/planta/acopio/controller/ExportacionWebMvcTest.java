package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.dto.DetalleVentaResponse;
import com.planta.acopio.dto.PagoResponse;
import com.planta.acopio.dto.ProveedorAdminResponse;
import com.planta.acopio.dto.RegistroAcopioResponse;
import com.planta.acopio.dto.VentaResumenResponse;
import com.planta.acopio.excel.ExportacionExcelService;
import com.planta.acopio.excel.LimitesExcel;
import com.planta.acopio.service.PagoService;
import com.planta.acopio.service.ProveedorService;
import com.planta.acopio.service.RegistroAcopioService;
import com.planta.acopio.service.VentaService;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Exportacion a Excel: cada endpoint tiene los mismos roles que el listado de su pantalla, recorre TODAS las paginas
// de la misma consulta y entrega un .xlsx. Un rol no autorizado no llega ni a consultar los datos.
@WebMvcTest(ExportacionController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, ExportacionExcelService.class, LimitesExcel.class})
class ExportacionWebMvcTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired private MockMvc mvc;
    @MockBean private ProveedorService proveedorService;
    @MockBean private RegistroAcopioService registroAcopioService;
    @MockBean private PagoService pagoService;
    @MockBean private VentaService ventaService;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    private ProveedorAdminResponse proveedor(int i) {
        return new ProveedorAdminResponse(UUID.randomUUID(), "Proveedor " + i, String.format("%08d", i), "+51987654321",
                UUID.randomUUID(), "Zona Norte", "qr-" + i, true);
    }

    @BeforeEach
    void catalogos() {
        // 1100 proveedores en total, de 500 en 500: tres paginas.
        List<ProveedorAdminResponse> todos = new ArrayList<>();
        for (int i = 0; i < 1100; i++) todos.add(proveedor(i));
        when(proveedorService.buscar(any(), any(), any(), any(Pageable.class))).thenAnswer(inv -> {
            Pageable p = inv.getArgument(3);
            int desde = (int) p.getOffset();
            int hasta = Math.min(desde + p.getPageSize(), todos.size());
            return new PageImpl<>(todos.subList(desde, hasta), p, todos.size());
        });
    }

    private Sheet hojaDe(MvcResult resultado) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(resultado.getResponse().getContentAsByteArray())).getSheetAt(0);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminExportaTodosLosProveedoresConLosFiltrosYLaBusquedaActuales() throws Exception {
        UUID zona = UUID.randomUUID();

        MvcResult resultado = mvc.perform(get("/api/exportaciones/proveedores")
                        .param("zonaId", zona.toString()).param("activo", "true").param("busqueda", "huaman"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", XLSX))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("attachment"), org.hamcrest.Matchers.containsString("proveedores-"),
                        org.hamcrest.Matchers.containsString(".xlsx"))))
                .andReturn();

        Sheet hoja = hojaDe(resultado);
        assertThat(hoja.getLastRowNum()).isEqualTo(1100); // todas las filas, no 20
        assertThat(hoja.getRow(1).getCell(1).getStringCellValue()).isEqualTo("00000000"); // ceros iniciales del DNI
        ArgumentCaptor<Pageable> paginas = ArgumentCaptor.forClass(Pageable.class);
        verify(proveedorService, org.mockito.Mockito.times(3)).buscar(eq(zona), eq(true), eq("huaman"), paginas.capture());
        assertThat(paginas.getAllValues()).extracting(Pageable::getPageNumber).containsExactly(0, 1, 2);
    }

    @Test
    @WithMockUser(roles = {"RECEPCION", "VENTAS", "CALIDAD", "PRODUCCION", "ACOPIADOR"})
    void proveedoresConDniYTelefonoSoloSeExportanAAdmin() throws Exception {
        mvc.perform(get("/api/exportaciones/proveedores")).andExpect(status().isForbidden());
        verifyNoInteractions(proveedorService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void pagosYVentasTienenSusPropiosRoles() throws Exception {
        // ADMIN ve ambos; (los pagos no son de VENTAS ni las ventas de RECEPCION: ver las pruebas siguientes)
        when(pagoService.listar(any(), any())).thenAnswer(inv -> new PageImpl<PagoResponse>(List.of(), inv.getArgument(1), 0));
        when(ventaService.buscar(any(), any(), any(), any())).thenAnswer(inv -> new PageImpl<VentaResumenResponse>(List.of(), inv.getArgument(3), 0));
        mvc.perform(get("/api/exportaciones/pagos")).andExpect(status().isOk());
        mvc.perform(get("/api/exportaciones/ventas")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "RECEPCION")
    void recepcionExportaPagosPeroNoVentasNiProveedores() throws Exception {
        when(pagoService.listar(any(), any())).thenAnswer(inv -> new PageImpl<PagoResponse>(List.of(), inv.getArgument(1), 0));
        mvc.perform(get("/api/exportaciones/pagos")).andExpect(status().isOk());
        mvc.perform(get("/api/exportaciones/ventas")).andExpect(status().isForbidden());
        mvc.perform(get("/api/exportaciones/proveedores")).andExpect(status().isForbidden());
        mvc.perform(get("/api/exportaciones/acopios")).andExpect(status().isForbidden());
        verify(ventaService, never()).buscar(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    void ventasExportaVentasPeroNoPagosNiAcopios() throws Exception {
        when(ventaService.buscar(any(), any(), any(), any())).thenAnswer(inv -> new PageImpl<VentaResumenResponse>(List.of(), inv.getArgument(3), 0));
        mvc.perform(get("/api/exportaciones/ventas")).andExpect(status().isOk());
        mvc.perform(get("/api/exportaciones/pagos")).andExpect(status().isForbidden());
        mvc.perform(get("/api/exportaciones/acopios")).andExpect(status().isForbidden());
        verifyNoInteractions(pagoService, registroAcopioService);
    }

    @Test
    @WithMockUser(roles = "CALIDAD")
    void calidadExportaAcopiosPeroNoPagos() throws Exception {
        when(registroAcopioService.buscarPaginado(any(), any(), any(), any(), any())).thenAnswer(inv ->
                new PageImpl<RegistroAcopioResponse>(List.of(), inv.getArgument(4), 0));
        mvc.perform(get("/api/exportaciones/acopios").param("fecha", "2026-10-05").param("sinAnalisis", "true"))
                .andExpect(status().isOk());
        verify(registroAcopioService).buscarPaginado(eq(LocalDate.of(2026, 10, 5)), isNull(), eq(true), isNull(), any());
        mvc.perform(get("/api/exportaciones/pagos")).andExpect(status().isForbidden());
    }

    @Test
    void sinTokenNoSeExportaNada() throws Exception {
        mvc.perform(get("/api/exportaciones/proveedores")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/exportaciones/pagos")).andExpect(status().isUnauthorized());
        verifyNoInteractions(proveedorService, pagoService, ventaService, registroAcopioService);
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    void ventasSeExportaUnaFilaPorLineaYElTotalSoloEnLaPrimera() throws Exception {
        VentaResumenResponse venta = new VentaResumenResponse(UUID.randomUUID(), LocalDate.of(2026, 10, 5), "MAYORISTA", List.of(
                new DetalleVentaResponse(UUID.randomUUID(), "1 kg", "Queso fresco", 3, new BigDecimal("20.00"), new BigDecimal("60.00")),
                new DetalleVentaResponse(UUID.randomUUID(), "500 g", "Queso andino", 2, new BigDecimal("12.50"), new BigDecimal("25.00"))),
                new BigDecimal("85.00"));
        when(ventaService.buscar(any(), any(), any(), any())).thenAnswer(inv ->
                new PageImpl<>(List.of(venta), inv.getArgument(3), 1));

        MvcResult resultado = mvc.perform(get("/api/exportaciones/ventas").param("tipoCliente", "MAYORISTA"))
                .andExpect(status().isOk()).andReturn();

        Sheet hoja = hojaDe(resultado);
        assertThat(hoja.getLastRowNum()).isEqualTo(2);
        assertThat(hoja.getRow(1).getCell(7).getNumericCellValue()).isEqualTo(85.0);
        assertThat(hoja.getRow(2).getCell(7).getCellType().name()).isEqualTo("BLANK");
        assertThat(hoja.getRow(2).getCell(3).getStringCellValue()).isEqualTo("500 g");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unaExportacionDemasiadoGrandeSeRechazaConUnMensajeClaro() throws Exception {
        when(pagoService.listar(any(), any())).thenAnswer(inv -> new PageImpl<PagoResponse>(List.of(), inv.getArgument(1), 60_000));

        mvc.perform(get("/api/exportaciones/pagos"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("60000")));
    }
}
