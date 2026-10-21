package com.planta.acopio.controller;

import com.planta.acopio.config.JwtAuthFilter;
import com.planta.acopio.config.JwtService;
import com.planta.acopio.config.SecurityConfig;
import com.planta.acopio.config.UsuarioDetailsService;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.exception.GlobalExceptionHandler;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.service.ConfiguracionSistemaService;
import com.planta.acopio.service.FamiliaService;
import com.planta.acopio.service.FotoCatalogoService;
import com.planta.acopio.service.InsumoService;
import com.planta.acopio.service.MotivoObservacionService;
import com.planta.acopio.service.PrecioLitroService;
import com.planta.acopio.service.PrecioQuesoService;
import com.planta.acopio.service.PresentacionService;
import com.planta.acopio.service.ProductoService;
import com.planta.acopio.service.RolService;
import com.planta.acopio.service.UnidadService;
import com.planta.acopio.service.ZonaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de /api/admin/productos y sus presentaciones: autorizacion por rol, validacion de entrada y
 * traduccion de errores. Usa el controller, la validacion, los servicios y el GlobalExceptionHandler reales;
 * solo los repositorios estan simulados (no requiere BD ni Docker).
 */
@WebMvcTest(AdminCatalogosController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, GlobalExceptionHandler.class, ProductoService.class, PresentacionService.class})
class AdminProductosWebMvcTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProductoRepository productoRepository;
    @MockBean private FamiliaRepository familiaRepository;
    @MockBean private PresentacionRepository presentacionRepository;
    @MockBean private FotoCatalogoService fotoCatalogoService;
    @MockBean private ZonaService zonaService;
    @MockBean private UnidadService unidadService;
    @MockBean private MotivoObservacionService motivoObservacionService;
    @MockBean private FamiliaService familiaService;
    @MockBean private ConfiguracionSistemaService configuracionSistemaService;
    @MockBean private PrecioLitroService precioLitroService;
    @MockBean private PrecioQuesoService precioQuesoService;
    @MockBean private RolService rolService;
    @MockBean private InsumoService insumoService;
    @MockBean private JwtService jwtService;
    @MockBean private UsuarioDetailsService usuarioDetailsService;

    private final UUID familiaId = UUID.randomUUID();
    private final UUID productoId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Familia familia = new Familia();
        familia.setId(familiaId);
        familia.setNombre("Queso");
        when(familiaRepository.findById(familiaId)).thenReturn(Optional.of(familia));
        when(productoRepository.save(any())).thenAnswer(inv -> {
            Producto p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        Producto existente = new Producto();
        existente.setId(productoId);
        existente.setNombre("Existente");
        existente.setFamilia(familia);
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(existente));
        when(presentacionRepository.save(any())).thenAnswer(inv -> {
            Presentacion p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
    }

    private ResultActions postProducto(String json) throws Exception {
        return mvc.perform(post("/api/admin/productos").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions postPresentacion(String json) throws Exception {
        return mvc.perform(post("/api/admin/productos/" + productoId + "/presentaciones")
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String producto(String extra) {
        return "{\"nombre\":\"Yogurt\",\"familiaId\":\"" + familiaId + "\"" + extra + "}";
    }

    // --- autorizacion ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCreaUnProducto() throws Exception {
        postProducto(producto("")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.nombre").value("Yogurt"))
                .andExpect(jsonPath("$.familiaNombre").value("Queso"))
                .andExpect(jsonPath("$.presentaciones").isEmpty());
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    void ventasNoPuedeCrearProductos() throws Exception {
        postProducto(producto("")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    void produccionNoPuedeCrearPresentaciones() throws Exception {
        postPresentacion("{\"nombre\":\"A\",\"unidadConteo\":\"b\"}").andExpect(status().isForbidden());
    }

    @Test
    void sinTokenSeRechazaConUnauthorized() throws Exception {
        postProducto(producto("")).andExpect(status().isUnauthorized());
    }

    // --- validacion de producto: todo responde 400, nunca 500 ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void rechazaNombreEnBlancoYFamiliaNula() throws Exception {
        postProducto("{\"nombre\":\"  \",\"familiaId\":\"" + familiaId + "\"}").andExpect(status().isBadRequest());
        postProducto("{\"nombre\":\"A\"}").andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rechazaValoresFueraDeLosLimitesDeLaBd() throws Exception {
        postProducto("{\"nombre\":\"" + "a".repeat(61) + "\",\"familiaId\":\"" + familiaId + "\"}")
                .andExpect(status().isBadRequest());
        postProducto(producto(",\"rendimientoEsperadoPct\":100000")).andExpect(status().isBadRequest());
        postProducto(producto(",\"rendimientoEsperadoPct\":-5")).andExpect(status().isBadRequest());
        postProducto(producto(",\"rendimientoEsperadoPct\":12.345")).andExpect(status().isBadRequest());
        postProducto(producto(",\"rendimientoEsperadoPct\":999.99")).andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rechazaUnCicloCapitalQueNoEstaEnElEnum() throws Exception {
        postProducto(producto(",\"cicloCapital\":\"FOO\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("RAPIDO o MADURACION")));
        postProducto(producto(",\"cicloCapital\":\"MADURACION\"")).andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rechazaCuerposIlegiblesConBadRequest() throws Exception {
        postProducto("{nombre:").andExpect(status().isBadRequest());
        postProducto("{\"nombre\":\"A\",\"familiaId\":\"no-es-uuid\"}").andExpect(status().isBadRequest());
        postProducto(producto(",\"rangoRendimiento\":{\"minimo\":1,\"maximo\":10,\"unidad\":\"FOO\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rechazaUnRangoInvertidoYAceptaUnoValido() throws Exception {
        postProducto(producto(",\"rangoRendimiento\":{\"minimo\":20,\"maximo\":10,\"unidad\":\"KG\"}"))
                .andExpect(status().isBadRequest());
        postProducto(producto(",\"rangoRendimiento\":{\"minimo\":1,\"maximo\":10,\"unidad\":\"KG\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.rangoRendimiento.unidad").value("KG"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void familiaInexistenteDevuelve404() throws Exception {
        postProducto("{\"nombre\":\"A\",\"familiaId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isNotFound());
    }

    // --- duplicados: 409 ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void nombreDuplicadoDevuelve409ConUnMensajeUtil() throws Exception {
        when(productoRepository.existsByNombreIgnoreCase("Yogurt")).thenReturn(true);

        postProducto(producto("")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("Yogurt")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void editarConElNombreDeOtroProductoDevuelve409() throws Exception {
        when(productoRepository.existsByNombreIgnoreCaseAndIdNot("Yogurt", productoId)).thenReturn(true);

        mvc.perform(put("/api/admin/productos/" + productoId).contentType(MediaType.APPLICATION_JSON).content(producto("")))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unaCarreraConLaUnicidadDeLaBdTambienDevuelve409() throws Exception {
        doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
                .when(productoRepository).save(any());

        postProducto(producto("")).andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void editarUnProductoInexistenteDevuelve404() throws Exception {
        mvc.perform(put("/api/admin/productos/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(producto(""))).andExpect(status().isNotFound());
    }

    // --- presentaciones ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void presentacionValidaYPiezaSinContenido() throws Exception {
        postPresentacion("{\"nombre\":\"Botella\",\"contenidoCantidad\":1,\"contenidoUnidad\":\"L\",\"unidadConteo\":\"botella\"}")
                .andExpect(status().isCreated());
        postPresentacion("{\"nombre\":\"Pieza\",\"unidadConteo\":\"pieza\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.contenidoCantidad").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void contenidoYUnidadDebenVenirJuntos() throws Exception {
        postPresentacion("{\"nombre\":\"B\",\"contenidoCantidad\":5,\"unidadConteo\":\"botella\"}")
                .andExpect(status().isBadRequest());
        postPresentacion("{\"nombre\":\"B\",\"contenidoUnidad\":\"L\",\"unidadConteo\":\"botella\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rechazaUnidadInvalidaCantidadFueraDeRangoYCamposLargos() throws Exception {
        postPresentacion("{\"nombre\":\"B\",\"contenidoCantidad\":1,\"contenidoUnidad\":\"XX\",\"unidadConteo\":\"b\"}")
                .andExpect(status().isBadRequest());
        postPresentacion("{\"nombre\":\"B\",\"contenidoCantidad\":0,\"contenidoUnidad\":\"L\",\"unidadConteo\":\"b\"}")
                .andExpect(status().isBadRequest());
        postPresentacion("{\"nombre\":\"B\",\"contenidoCantidad\":99999999,\"contenidoUnidad\":\"L\",\"unidadConteo\":\"b\"}")
                .andExpect(status().isBadRequest());
        postPresentacion("{\"nombre\":\"" + "a".repeat(61) + "\",\"unidadConteo\":\"b\"}").andExpect(status().isBadRequest());
        postPresentacion("{\"nombre\":\"B\",\"unidadConteo\":\"" + "a".repeat(31) + "\"}").andExpect(status().isBadRequest());
        postPresentacion("{\"nombre\":\"B\"}").andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void presentacionDuplicadaDevuelve409() throws Exception {
        when(presentacionRepository.existsByProductoIdAndNombre(productoId, "Botella")).thenReturn(true);

        postPresentacion("{\"nombre\":\" Botella \",\"unidadConteo\":\"b\"}").andExpect(status().isConflict());
    }

    // --- fotos ---

    private org.springframework.mock.web.MockMultipartFile foto() {
        return new org.springframework.mock.web.MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    private ResultActions putFotoProducto(org.springframework.mock.web.MockMultipartFile f) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(org.springframework.http.HttpMethod.PUT, "/api/admin/productos/" + productoId + "/foto").file(f));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminSubeLaFotoDeUnProductoYRecibeSuUrlPublica() throws Exception {
        when(fotoCatalogoService.guardar(any())).thenReturn("abc.jpg");

        putFotoProducto(foto()).andExpect(status().isOk())
                .andExpect(jsonPath("$.fotoUrl").value("/api/catalogo/fotos/abc.jpg"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unaFotoInvalidaDevuelve400() throws Exception {
        when(fotoCatalogoService.guardar(any()))
                .thenThrow(new com.planta.acopio.exception.ValidacionNegocioException("Formato no permitido"));

        putFotoProducto(foto()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("Formato no permitido"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void sinLaParteFileDevuelve400YNoMultipartDevuelve415() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(org.springframework.http.HttpMethod.PUT, "/api/admin/productos/" + productoId + "/foto"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/productos/" + productoId + "/foto").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    void soloAdminPuedeSubirFotos() throws Exception {
        putFotoProducto(foto()).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/admin/productos/" + productoId + "/foto")).andExpect(status().isForbidden());
    }

    @Test
    void subirFotoSinTokenDevuelve401() throws Exception {
        putFotoProducto(foto()).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminQuitaLaFotoDeUnProducto() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/admin/productos/" + productoId + "/foto"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fotoUrl").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void fotoDePresentacionDeOtroProductoDevuelve404() throws Exception {
        UUID presentacionId = UUID.randomUUID();
        Producto otro = new Producto();
        otro.setId(UUID.randomUUID());
        Presentacion presentacion = new Presentacion();
        presentacion.setId(presentacionId);
        presentacion.setProducto(otro);
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacion));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart(org.springframework.http.HttpMethod.PUT,
                                "/api/admin/productos/" + productoId + "/presentaciones/" + presentacionId + "/foto")
                        .file(foto()))
                .andExpect(status().isNotFound());
    }
}
