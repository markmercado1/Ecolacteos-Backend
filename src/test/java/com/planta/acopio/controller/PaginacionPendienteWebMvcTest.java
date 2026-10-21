package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.dto.AcopioPorJornadaResponse;
import com.planta.acopio.dto.PrecioLitroResponse;
import com.planta.acopio.dto.TotalAcopioProveedorResponse;
import com.planta.acopio.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Paginacion pendiente (feat/paginacion-pendiente): contrato HTTP de los listados que pasaron a paginarse,
 * sin Docker. Igual que RegistroAcopioAuthorizationWebMvcTest, levanta los controllers reales con el
 * SecurityConfig y el JwtAuthFilter reales (los @PreAuthorize se evaluan de verdad); solo los services
 * estan mockeados. Cubre: forma PaginaResponse, saneo de page/size (page &lt; 0 -> 0, size acotado a
 * 1..100), filtros y busqueda propagados al service, roles, y que los endpoints que consume la app movil
 * siguen devolviendo un arreglo plano.
 */
@WebMvcTest(controllers = {
        PagoController.class, ComunicadoController.class, AdminCatalogosController.class,
        RegistroAcopioController.class, AdminUsuarioController.class, InventarioController.class,
        ProveedorController.class
})
@Import({SecurityConfig.class, JwtAuthFilter.class})
class PaginacionPendienteWebMvcTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private PagoService pagoService;
    @MockBean private AjustePagoService ajustePagoService;
    @MockBean private ComunicadoService comunicadoService;
    @MockBean private RegistroAcopioService registroAcopioService;
    @MockBean private CorreccionRegistroService correccionRegistroService;
    @MockBean private UsuarioService usuarioService;
    @MockBean private ProveedorService proveedorService;
    @MockBean private InventarioService inventarioService;
    @MockBean private InventarioLecheService inventarioLecheService;
    @MockBean private AjusteInventarioService ajusteInventarioService;
    @MockBean private ZonaService zonaService;
    @MockBean private UnidadService unidadService;
    @MockBean private MotivoObservacionService motivoObservacionService;
    @MockBean private FamiliaService familiaService;
    @MockBean private ProductoService productoService;
    @MockBean private PresentacionService presentacionService;
    @MockBean private ConfiguracionSistemaService configuracionSistemaService;
    @MockBean private PrecioLitroService precioLitroService;
    @MockBean private PrecioQuesoService precioQuesoService;
    @MockBean private RolService rolService;
    @MockBean private InsumoService insumoService;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    private final UUID proveedorId = UUID.randomUUID();

    private Pageable capturarPageable(ArgumentCaptor<Pageable> captor) {
        return captor.getValue();
    }

    // --- Pagos ---

    @Test
    @WithMockUser(roles = "RECEPCION")
    void listarPagosDevuelvePaginaResponseConTotalesDeLaBd() throws Exception {
        when(pagoService.listar(any())).thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(0), 45));

        mockMvc.perform(get("/api/pagos").param("page", "2").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contenido").isArray())
                .andExpect(jsonPath("$.paginaActual").value(2))
                .andExpect(jsonPath("$.tamanoPagina").value(20))
                .andExpect(jsonPath("$.totalElementos").value(45))
                .andExpect(jsonPath("$.totalPaginas").value(3));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listarPagosSaneaPageNegativoYAcotaSizeAlMaximo() throws Exception {
        when(pagoService.listar(any())).thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(0), 0));

        mockMvc.perform(get("/api/pagos").param("page", "-4").param("size", "5000"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(pagoService).listar(captor.capture());
        assertThat(capturarPageable(captor).getPageNumber()).isZero();
        assertThat(capturarPageable(captor).getPageSize()).isEqualTo(Paginacion.TAMANO_MAXIMO);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listarPagosSinParametrosUsaPaginaCeroYTamanoPorDefecto() throws Exception {
        when(pagoService.listar(any())).thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(0), 0));

        mockMvc.perform(get("/api/pagos")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(pagoService).listar(captor.capture());
        assertThat(capturarPageable(captor)).isEqualTo(PageRequest.of(0, Paginacion.TAMANO_POR_DEFECTO));
    }

    @Test
    @WithMockUser(roles = "CALIDAD")
    void listarPagosSigueRestringidoAAdminYRecepcion() throws Exception {
        mockMvc.perform(get("/api/pagos")).andExpect(status().isForbidden());
        verifyNoInteractions(pagoService);
    }

    @Test
    @WithMockUser(roles = "RECEPCION")
    void historialDePagosPorProveedorParaMovilSigueSiendoUnArreglo() throws Exception {
        when(pagoService.historialPorProveedor(proveedorId)).thenReturn(List.of());

        mockMvc.perform(get("/api/pagos/proveedor/{id}", proveedorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void historialDePagosPorProveedorPaginado() throws Exception {
        when(pagoService.historialPorProveedor(eq(proveedorId), any()))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(1), 7));

        mockMvc.perform(get("/api/pagos/proveedor/{id}/paginado", proveedorId).param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paginaActual").value(1))
                .andExpect(jsonPath("$.totalElementos").value(7))
                .andExpect(jsonPath("$.totalPaginas").value(2));
    }

    // --- Comunicados ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void listarComunicadosEsPaginado() throws Exception {
        when(comunicadoService.listar(any())).thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(0), 3));

        mockMvc.perform(get("/api/comunicados").param("size", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(3))
                // size=0 se lleva al minimo (1), nunca a "sin limite".
                .andExpect(jsonPath("$.tamanoPagina").value(1));
    }

    @Test
    @WithMockUser(roles = "ACOPIADOR")
    void listarComunicadosGlobalSigueSiendoSoloAdmin() throws Exception {
        mockMvc.perform(get("/api/comunicados")).andExpect(status().isForbidden());
        verifyNoInteractions(comunicadoService);
    }

    // --- Precio por litro ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void historicoDePreciosPorLitroEsPaginado() throws Exception {
        PrecioLitroResponse precio = new PrecioLitroResponse(UUID.randomUUID(), new BigDecimal("1.50"), LocalDate.of(2026, 9, 1));
        when(precioLitroService.listar(any())).thenAnswer(inv -> new PageImpl<>(List.of(precio), inv.getArgument(0), 21));

        mockMvc.perform(get("/api/admin/precios-litro").param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contenido[0].precio").value(1.50))
                .andExpect(jsonPath("$.totalElementos").value(21))
                .andExpect(jsonPath("$.totalPaginas").value(2));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void precioPorLitroVigenteDevuelveElPrecioO204() throws Exception {
        PrecioLitroResponse precio = new PrecioLitroResponse(UUID.randomUUID(), new BigDecimal("1.80"), LocalDate.of(2026, 9, 1));
        when(precioLitroService.vigente()).thenReturn(Optional.of(precio));
        mockMvc.perform(get("/api/admin/precios-litro/vigente"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precio").value(1.80));

        when(precioLitroService.vigente()).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/admin/precios-litro/vigente")).andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "RECEPCION")
    void precioPorLitroSigueSiendoSoloAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/precios-litro")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/precios-litro/vigente")).andExpect(status().isForbidden());
        verifyNoInteractions(precioLitroService);
    }

    // --- Registros de acopio ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void registrosDeAcopioPaginadosPropaganFiltrosYBusqueda() throws Exception {
        UUID zonaId = UUID.randomUUID();
        when(registroAcopioService.buscarPaginado(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(4), 120));

        mockMvc.perform(get("/api/registros-acopio/paginado")
                        .param("fecha", "2026-10-01").param("zonaId", zonaId.toString())
                        .param("sinAnalisis", "true").param("busqueda", "abc-123")
                        .param("page", "3").param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paginaActual").value(3))
                .andExpect(jsonPath("$.totalElementos").value(120))
                .andExpect(jsonPath("$.totalPaginas").value(5));

        verify(registroAcopioService).buscarPaginado(eq(LocalDate.of(2026, 10, 1)), eq(zonaId), eq(true),
                eq("abc-123"), eq(PageRequest.of(3, 25)));
    }

    @Test
    @WithMockUser(roles = "ACOPIADOR")
    void listadoDeRegistrosParaMovilSigueSiendoUnArreglo() throws Exception {
        when(registroAcopioService.buscar(isNull(), isNull(), isNull())).thenReturn(List.of());
        when(registroAcopioService.historialPorProveedor(proveedorId)).thenReturn(List.of());

        mockMvc.perform(get("/api/registros-acopio")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/registros-acopio/proveedor/{id}", proveedorId))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser(roles = "RECEPCION")
    void registrosDeAcopioPaginadosConservanLosRolesDelListadoOriginal() throws Exception {
        mockMvc.perform(get("/api/registros-acopio/paginado")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/registros-acopio/por-jornada")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/registros-acopio/resumen-por-proveedor")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/registros-acopio/proveedor/{id}/paginado", proveedorId)).andExpect(status().isForbidden());
        verifyNoInteractions(registroAcopioService);
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    void registrosAgrupadosPorJornadaSonPaginados() throws Exception {
        UUID jornadaId = UUID.randomUUID();
        AcopioPorJornadaResponse fila = new AcopioPorJornadaResponse(jornadaId, true, "Ana", "ABC-123",
                LocalDateTime.of(2026, 10, 1, 6, 30), 4, new BigDecimal("120.50"), UUID.randomUUID());
        when(registroAcopioService.agruparPorJornada(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new PageImpl<>(List.of(fila), inv.getArgument(4), 1));

        mockMvc.perform(get("/api/registros-acopio/por-jornada").param("fecha", "2026-10-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contenido[0].clave").value(jornadaId.toString()))
                .andExpect(jsonPath("$.contenido[0].esJornada").value(true))
                .andExpect(jsonPath("$.contenido[0].cantidad").value(4))
                .andExpect(jsonPath("$.contenido[0].totalLitros").value(120.50))
                .andExpect(jsonPath("$.totalElementos").value(1));
    }

    @Test
    @WithMockUser(roles = "CALIDAD")
    void resumenPorProveedorEsGlobalYNoRecibeBusquedaNiPagina() throws Exception {
        when(registroAcopioService.totalesPorProveedor(any(), any(), any())).thenReturn(List.of(
                new TotalAcopioProveedorResponse(proveedorId, "Juan", new BigDecimal("300.00"), 6)));

        mockMvc.perform(get("/api/registros-acopio/resumen-por-proveedor").param("fecha", "2026-10-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].proveedorNombre").value("Juan"))
                .andExpect(jsonPath("$[0].totalLitros").value(300.00));

        verify(registroAcopioService).totalesPorProveedor(LocalDate.of(2026, 10, 1), null, null);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void historialDeEntregasPorProveedorPaginado() throws Exception {
        when(registroAcopioService.historialPorProveedor(eq(proveedorId), any()))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(1), 30));

        mockMvc.perform(get("/api/registros-acopio/proveedor/{id}/paginado", proveedorId).param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(30))
                .andExpect(jsonPath("$.totalPaginas").value(3));
    }

    // --- Usuarios ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void usuariosPaginadosPropaganRolEstadoYBusqueda() throws Exception {
        when(usuarioService.buscar(any(), any(), any(), any()))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(3), 2));

        mockMvc.perform(get("/api/admin/usuarios/paginado")
                        .param("rol", "ACOPIADOR").param("activo", "true").param("busqueda", "ana"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(2));

        verify(usuarioService).buscar("ACOPIADOR", true, "ana", PageRequest.of(0, 20));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listaCompletaDeUsuariosParaSelectoresSigueSiendoUnArreglo() throws Exception {
        when(usuarioService.listar()).thenReturn(List.of());
        mockMvc.perform(get("/api/admin/usuarios")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser(roles = "RECEPCION")
    void usuariosPaginadosSonSoloAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/usuarios/paginado")).andExpect(status().isForbidden());
        verifyNoInteractions(usuarioService);
    }

    // --- Ajustes de inventario ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void historialDeAjustesDeInventarioEsPaginado() throws Exception {
        UUID presentacionId = UUID.randomUUID();
        when(ajusteInventarioService.listarPorPresentacion(eq(presentacionId), any()))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(1), 12));

        mockMvc.perform(get("/api/inventario/ajustes").param("presentacionId", presentacionId.toString())
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paginaActual").value(1))
                .andExpect(jsonPath("$.totalElementos").value(12))
                .andExpect(jsonPath("$.totalPaginas").value(2));
    }

    // --- Proveedores ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void proveedoresPropaganLaBusquedaAlServicio() throws Exception {
        when(proveedorService.buscar(any(), any(), any(), any()))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(3), 0));

        mockMvc.perform(get("/api/proveedores").param("busqueda", "Pérez").param("activo", "true"))
                .andExpect(status().isOk());

        verify(proveedorService).buscar(null, true, "Pérez", PageRequest.of(0, 20));
    }
}
