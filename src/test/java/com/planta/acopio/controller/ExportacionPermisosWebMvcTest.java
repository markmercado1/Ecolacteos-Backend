package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.excel.ExportacionExcelService;
import com.planta.acopio.excel.LimitesExcel;
import com.planta.acopio.service.AnalisisCalidadService;
import com.planta.acopio.service.AnomaliaService;
import com.planta.acopio.service.ConciliacionService;
import com.planta.acopio.service.ConfiguracionSistemaService;
import com.planta.acopio.service.FamiliaService;
import com.planta.acopio.service.InformeProduccionDiarioService;
import com.planta.acopio.service.InsumoService;
import com.planta.acopio.service.InventarioInsumoService;
import com.planta.acopio.service.InventarioService;
import com.planta.acopio.service.JornadaService;
import com.planta.acopio.service.LoteProduccionService;
import com.planta.acopio.service.MotivoObservacionService;
import com.planta.acopio.service.PagoService;
import com.planta.acopio.service.PrecioLitroService;
import com.planta.acopio.service.PrecioQuesoService;
import com.planta.acopio.service.ProductoService;
import com.planta.acopio.service.ProveedorService;
import com.planta.acopio.service.RecetaService;
import com.planta.acopio.service.RegistroAcopioService;
import com.planta.acopio.service.ReservaService;
import com.planta.acopio.service.UnidadService;
import com.planta.acopio.service.UsuarioService;
import com.planta.acopio.service.VentaService;
import com.planta.acopio.service.ZonaService;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Matriz de permisos de TODAS las exportaciones: cada ruta responde 200 a los roles de su listado y 403 a los demas, y un
// rol rechazado no llega a consultar ni un solo dato. Sin sesion: 401.
@WebMvcTest({ExportacionController.class, ExportacionAdminController.class})
@Import({SecurityConfig.class, JwtAuthFilter.class, ExportacionExcelService.class, LimitesExcel.class})
class ExportacionPermisosWebMvcTest {

    private static final List<String> ROLES = List.of("ADMIN", "ACOPIADOR", "CALIDAD", "RECEPCION", "PRODUCCION", "VENTAS");
    private static final String ZONA = UUID.randomUUID().toString();

    @Autowired private MockMvc mvc;
    @MockBean private ProveedorService proveedorService;
    @MockBean private RegistroAcopioService registroAcopioService;
    @MockBean private PagoService pagoService;
    @MockBean private VentaService ventaService;
    @MockBean private ReservaService reservaService;
    @MockBean private ConciliacionService conciliacionService;
    @MockBean private UnidadService unidadService;
    @MockBean private LoteProduccionService loteProduccionService;
    @MockBean private InformeProduccionDiarioService informeService;
    @MockBean private InventarioService inventarioService;
    @MockBean private InventarioInsumoService inventarioInsumoService;
    @MockBean private AnalisisCalidadService analisisCalidadService;
    @MockBean private AnomaliaService anomaliaService;
    @MockBean private JornadaService jornadaService;
    @MockBean private UsuarioService usuarioService;
    @MockBean private PrecioLitroService precioLitroService;
    @MockBean private PrecioQuesoService precioQuesoService;
    @MockBean private ZonaService zonaService;
    @MockBean private MotivoObservacionService motivoService;
    @MockBean private FamiliaService familiaService;
    @MockBean private ProductoService productoService;
    @MockBean private InsumoService insumoService;
    @MockBean private RecetaService recetaService;
    @MockBean private ConfiguracionSistemaService configuracionService;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    /** ruta, roles permitidos (separados por coma). */
    private record Ruta(String url, Set<String> permitidos) {
        @Override public String toString() { return url; }
    }

    static Stream<Ruta> rutas() {
        return Stream.of(
                new Ruta("/api/exportaciones/proveedores", Set.of("ADMIN")),
                new Ruta("/api/exportaciones/acopios", Set.of("ACOPIADOR", "ADMIN", "CALIDAD", "PRODUCCION")),
                new Ruta("/api/exportaciones/pagos", Set.of("ADMIN", "RECEPCION")),
                new Ruta("/api/exportaciones/ventas", Set.of("VENTAS", "ADMIN")),
                new Ruta("/api/exportaciones/reservas", Set.of("VENTAS", "ADMIN")),
                new Ruta("/api/exportaciones/recepciones", Set.of("RECEPCION", "ADMIN")),
                new Ruta("/api/exportaciones/lotes", Set.of("PRODUCCION", "ADMIN")),
                new Ruta("/api/exportaciones/informes-produccion", Set.of("PRODUCCION", "ADMIN")),
                new Ruta("/api/exportaciones/stock", Set.of("PRODUCCION", "VENTAS", "ADMIN")),
                new Ruta("/api/exportaciones/inventario-insumos", Set.of("PRODUCCION", "ADMIN")),
                new Ruta("/api/exportaciones/calidad-analizados", Set.of("CALIDAD", "ADMIN")),
                new Ruta("/api/exportaciones/alertas-anomalia?zonaId=" + ZONA, Set.of("CALIDAD", "ADMIN")),
                new Ruta("/api/exportaciones/jornadas", Set.of("RECEPCION", "ADMIN")),
                new Ruta("/api/exportaciones/usuarios", Set.of("ADMIN")),
                new Ruta("/api/exportaciones/precios-litro", Set.of("ADMIN")),
                new Ruta("/api/exportaciones/precios-presentacion", Set.of("ADMIN")),
                new Ruta("/api/exportaciones/zonas", Set.of("ADMIN")),
                new Ruta("/api/exportaciones/motivos-observacion", Set.of("ADMIN")),
                new Ruta("/api/exportaciones/familias", Set.of("ADMIN")),
                new Ruta("/api/exportaciones/productos", Set.of("ADMIN")),
                new Ruta("/api/exportaciones/insumos", Set.of("ADMIN")),
                new Ruta("/api/exportaciones/recetas", Set.of("PRODUCCION", "ADMIN")),
                new Ruta("/api/exportaciones/configuracion", Set.of("ADMIN")),
                new Ruta("/api/exportaciones/unidades", Set.of("ADMIN"))
        );
    }

    @BeforeEach
    void respuestasVacias() {
        // Las listas devuelven vacio por defecto (Mockito); las paginas hay que darlas.
        when(proveedorService.buscar(any(), any(), any(), any())).thenAnswer(i -> new PageImpl<>(List.of(), i.getArgument(3), 0));
        when(registroAcopioService.buscarPaginado(any(), any(), any(), any(), any())).thenAnswer(i -> new PageImpl<>(List.of(), i.getArgument(4), 0));
        when(pagoService.listar(any(), any())).thenAnswer(i -> new PageImpl<>(List.of(), i.getArgument(1), 0));
        when(ventaService.buscar(any(), any(), any(), any())).thenAnswer(i -> new PageImpl<>(List.of(), i.getArgument(3), 0));
        when(conciliacionService.listar(any(), any(), any(), any())).thenAnswer(i -> new PageImpl<>(List.of(), i.getArgument(3), 0));
        when(loteProduccionService.buscar(any(), any(), any(), any(), any())).thenAnswer(i -> new PageImpl<>(List.of(), i.getArgument(4), 0));
        when(informeService.buscar(any(), any(), any(), any())).thenAnswer(i -> new PageImpl<>(List.of(), i.getArgument(3), 0));
        when(usuarioService.buscar(any(), any(), any(), any())).thenAnswer(i -> new PageImpl<>(List.of(), i.getArgument(3), 0));
        when(precioLitroService.listar(any())).thenAnswer(i -> new PageImpl<>(List.of(), i.getArgument(0), 0));
        when(reservaService.listar(any(), any(), any(), any(), any(), any())).thenReturn(new PaginaResponse<>(List.of(), 0, 20, 0, 0));
        when(inventarioService.listarStockPaginado(any(), any(), any())).thenReturn(new PaginaResponse<>(List.of(), 0, 20, 0, 0));
        when(precioQuesoService.listarPaginado(any(), any(), any())).thenReturn(new PaginaResponse<>(List.of(), 0, 20, 0, 0));
    }

    private Object[] todosLosServicios() {
        return new Object[]{proveedorService, registroAcopioService, pagoService, ventaService, reservaService, conciliacionService,
                unidadService, loteProduccionService, informeService, inventarioService, inventarioInsumoService, analisisCalidadService,
                anomaliaService, jornadaService, usuarioService, precioLitroService, precioQuesoService, zonaService, motivoService,
                familiaService, productoService, insumoService, recetaService, configuracionService};
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rutas")
    void cadaRutaSoloLaVenSusRolesYUnRolRechazadoNoConsultaNada(Ruta ruta) throws Exception {
        for (String rol : ROLES) {
            Mockito.clearInvocations(todosLosServicios());
            var respuesta = mvc.perform(get(ruta.url()).with(user("prueba").roles(rol)));
            if (ruta.permitidos().contains(rol)) {
                respuesta.andExpect(status().isOk());
            } else {
                respuesta.andExpect(status().isForbidden());
                for (Object servicio : todosLosServicios()) {
                    assertThat(mockingDetails(servicio).getInvocations())
                            .as("el rol %s no debe consultar %s en %s", rol, servicio.getClass().getSimpleName(), ruta.url())
                            .isEmpty();
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rutas")
    void sinSesionNoSeEntregaNada(Ruta ruta) throws Exception {
        mvc.perform(get(ruta.url())).andExpect(status().isUnauthorized());
        for (Object servicio : todosLosServicios()) {
            assertThat(mockingDetails(servicio).getInvocations()).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rutas")
    void unaTablaVaciaDaUnXlsxValidoSoloConElEncabezado(Ruta ruta) throws Exception {
        MvcResult resultado = mvc.perform(get(ruta.url()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();

        assertThat(resultado.getResponse().getContentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(resultado.getResponse().getContentAsByteArray()))) {
            Sheet hoja = libro.getSheetAt(0);
            assertThat(hoja.getLastRowNum()).isZero();
            assertThat(hoja.getRow(0).getLastCellNum()).isGreaterThanOrEqualTo((short) 1);
        }
    }

    @Test
    void alertasSinZonaSePideElegirUna() throws Exception {
        mvc.perform(get("/api/exportaciones/alertas-anomalia").with(user("c").roles("CALIDAD")))
                .andExpect(status().isBadRequest());
    }
}
