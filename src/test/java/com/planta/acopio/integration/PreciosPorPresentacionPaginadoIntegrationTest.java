package com.planta.acopio.integration;

import com.planta.acopio.dto.FilaPrecioPresentacionResponse;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.PrecioQueso;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.TipoClienteVenta;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.PrecioQuesoRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.service.PrecioQuesoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verificación real (PostgreSQL 15 vía Testcontainers, no mocks) de la ampliación de /precios pedida por el
 * usuario: listado paginado por presentación, organizado por familia, con búsqueda y filtro aplicados en el
 * servidor antes de paginar. Cubre exactamente lo que un test con Mockito no puede: que la query nativa con
 * columnas UUID (PresentacionRepository.buscarFilasPrecios) mapea correctamente contra Postgres real, que el
 * LEFT JOIN produce la fila informativa para un producto sin presentaciones, y que el orden/paginación real
 * coincide con lo esperado.
 */
@Testcontainers
@SpringBootTest
@Transactional
class PreciosPorPresentacionPaginadoIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = PostgresDePrueba.contenedor()
            .withDatabaseName("acopio_lechero_test")
            .withUsername("acopio")
            .withPassword("acopio");

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private PrecioQuesoService precioQuesoService;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private PrecioQuesoRepository precioQuesoRepository;

    private Familia queso;
    private Familia yogurt;
    private Producto quesoFresco;      // 2 presentaciones: una con precio vigente, otra sin precio
    private Producto quesoAndino;      // 0 presentaciones activas -> fila informativa
    private Producto yogurtFresa;      // 1 presentacion: precio programado a futuro
    private Presentacion botella1L;
    private Presentacion bolsa500g;
    private Presentacion frascoYogurt;

    @BeforeEach
    void setUp() {
        String sufijo = "-" + System.nanoTime();
        queso = familiaRepository.save(familiaCon("Queso" + sufijo));
        yogurt = familiaRepository.save(familiaCon("Yogurt" + sufijo));

        quesoFresco = productoRepository.save(productoCon("Queso Fresco" + sufijo, queso));
        quesoAndino = productoRepository.save(productoCon("Queso Andino" + sufijo, queso));
        yogurtFresa = productoRepository.save(productoCon("Yogurt Fresa" + sufijo, yogurt));

        botella1L = presentacionRepository.save(presentacionCon("Botella 1 L" + sufijo, quesoFresco));
        bolsa500g = presentacionRepository.save(presentacionCon("Bolsa 500 g" + sufijo, quesoFresco));
        frascoYogurt = presentacionRepository.save(presentacionCon("Frasco 1 L" + sufijo, yogurtFresa));
        // quesoAndino se queda sin ninguna presentacion a proposito.

        precioQuesoRepository.save(precioCon(botella1L, TipoClienteVenta.PUBLICO, "12.00", LocalDate.now().minusDays(10)));
        precioQuesoRepository.save(precioCon(frascoYogurt, TipoClienteVenta.MAYORISTA, "5.50", LocalDate.now().plusDays(30)));
        // bolsa500g se queda sin ningun precio configurado a proposito.
    }

    @Test
    void devuelveUnaFilaPorPresentacionActivaYUnaFilaInformativaParaElProductoSinPresentaciones() {
        PaginaResponse<FilaPrecioPresentacionResponse> pagina =
                precioQuesoService.listarPaginado(queso.getId(), null, PageRequest.of(0, 50));

        assertThat(pagina.totalElementos()).isEqualTo(3); // botella1L + bolsa500g + fila informativa de quesoAndino
        List<FilaPrecioPresentacionResponse> filas = pagina.contenido();
        assertThat(filas).extracting(FilaPrecioPresentacionResponse::presentacionId)
                .containsExactlyInAnyOrder(botella1L.getId(), bolsa500g.getId(), null);

        FilaPrecioPresentacionResponse filaAndino = filas.stream()
                .filter(f -> f.presentacionId() == null).findFirst().orElseThrow();
        assertThat(filaAndino.productoId()).isEqualTo(quesoAndino.getId());
        assertThat(filaAndino.mayorista().estado()).isEqualTo("SIN_PRECIO");
        assertThat(filaAndino.proveedor().estado()).isEqualTo("SIN_PRECIO");
        assertThat(filaAndino.publico().estado()).isEqualTo("SIN_PRECIO");
    }

    @Test
    void resuelveVigenteProgramadoYSinPrecioCorrectamentePorPresentacionYModalidad() {
        PaginaResponse<FilaPrecioPresentacionResponse> pagina =
                precioQuesoService.listarPaginado(null, null, PageRequest.of(0, 50));

        FilaPrecioPresentacionResponse filaBotella = filaDe(pagina, botella1L.getId());
        assertThat(filaBotella.publico().estado()).isEqualTo("VIGENTE");
        assertThat(filaBotella.publico().precio()).isEqualByComparingTo("12.00");
        assertThat(filaBotella.mayorista().estado()).isEqualTo("SIN_PRECIO");

        FilaPrecioPresentacionResponse filaBolsa = filaDe(pagina, bolsa500g.getId());
        assertThat(filaBolsa.publico().estado()).isEqualTo("SIN_PRECIO");
        assertThat(filaBolsa.mayorista().estado()).isEqualTo("SIN_PRECIO");

        FilaPrecioPresentacionResponse filaFrasco = filaDe(pagina, frascoYogurt.getId());
        assertThat(filaFrasco.mayorista().estado()).isEqualTo("PROGRAMADO");
        assertThat(filaFrasco.mayorista().precio()).isEqualByComparingTo("5.50");
        assertThat(filaFrasco.mayorista().vigenteDesde()).isEqualTo(LocalDate.now().plusDays(30));
    }

    @Test
    void filtraPorFamiliaAntesDePaginarSinAfectarOtrasFamilias() {
        PaginaResponse<FilaPrecioPresentacionResponse> soloQueso =
                precioQuesoService.listarPaginado(queso.getId(), null, PageRequest.of(0, 50));
        assertThat(soloQueso.contenido()).allMatch(f -> f.familiaId().equals(queso.getId()));
        assertThat(soloQueso.totalElementos()).isEqualTo(3);

        PaginaResponse<FilaPrecioPresentacionResponse> soloYogurt =
                precioQuesoService.listarPaginado(yogurt.getId(), null, PageRequest.of(0, 50));
        assertThat(soloYogurt.contenido()).hasSize(1);
        assertThat(soloYogurt.contenido().get(0).presentacionId()).isEqualTo(frascoYogurt.getId());
    }

    @Test
    void combinaFamiliaYBusquedaConAndNoConOr() {
        // "Fresco" coincide con un producto de queso; filtrado ademas a familia Yogurt, no debe devolver nada.
        PaginaResponse<FilaPrecioPresentacionResponse> combinadoSinCoincidir =
                precioQuesoService.listarPaginado(yogurt.getId(), "Fresco", PageRequest.of(0, 50));
        assertThat(combinadoSinCoincidir.totalElementos()).isEqualTo(0);

        // La misma busqueda, pero filtrando a la familia correcta (Queso), si debe encontrar el producto.
        PaginaResponse<FilaPrecioPresentacionResponse> combinadoCoincidiendo =
                precioQuesoService.listarPaginado(queso.getId(), "Fresco", PageRequest.of(0, 50));
        assertThat(combinadoCoincidiendo.totalElementos()).isEqualTo(2); // las 2 presentaciones de Queso Fresco
        assertThat(combinadoCoincidiendo.contenido()).allMatch(f -> f.productoId().equals(quesoFresco.getId()));
    }

    @Test
    void buscaPorNombreDeProductoOPresentacionSinImportarLaFamilia() {
        PaginaResponse<FilaPrecioPresentacionResponse> porProducto =
                precioQuesoService.listarPaginado(null, "Andino", PageRequest.of(0, 50));
        assertThat(porProducto.contenido()).hasSize(1);
        assertThat(porProducto.contenido().get(0).productoId()).isEqualTo(quesoAndino.getId());

        PaginaResponse<FilaPrecioPresentacionResponse> porPresentacion =
                precioQuesoService.listarPaginado(null, "Botella 1", PageRequest.of(0, 50));
        assertThat(porPresentacion.contenido()).hasSize(1);
        assertThat(porPresentacion.contenido().get(0).presentacionId()).isEqualTo(botella1L.getId());
    }

    @Test
    void paginaRealmenteEnBloquesSinRepetirNiOmitirFilasEntrePaginas() {
        PaginaResponse<FilaPrecioPresentacionResponse> pagina0 =
                precioQuesoService.listarPaginado(queso.getId(), null, PageRequest.of(0, 1));
        PaginaResponse<FilaPrecioPresentacionResponse> pagina1 =
                precioQuesoService.listarPaginado(queso.getId(), null, PageRequest.of(1, 1));
        PaginaResponse<FilaPrecioPresentacionResponse> pagina2 =
                precioQuesoService.listarPaginado(queso.getId(), null, PageRequest.of(2, 1));

        assertThat(pagina0.contenido()).hasSize(1);
        assertThat(pagina1.contenido()).hasSize(1);
        assertThat(pagina2.contenido()).hasSize(1);
        assertThat(pagina0.totalElementos()).isEqualTo(3);
        assertThat(pagina0.totalPaginas()).isEqualTo(3);

        List<Object> idsVistos = List.of(
                claveDe(pagina0.contenido().get(0)), claveDe(pagina1.contenido().get(0)), claveDe(pagina2.contenido().get(0)));
        assertThat(idsVistos).doesNotHaveDuplicates();
    }

    @Test
    void unaPresentacionInactivaNoAparecePeroElProductoSigueMostrandoLaActiva() {
        Presentacion inactiva = presentacionRepository.save(presentacionCon("Descontinuada", quesoFresco));
        inactiva.setActivo(false);
        presentacionRepository.save(inactiva);

        PaginaResponse<FilaPrecioPresentacionResponse> pagina =
                precioQuesoService.listarPaginado(queso.getId(), null, PageRequest.of(0, 50));

        assertThat(pagina.contenido()).extracting(FilaPrecioPresentacionResponse::presentacionId)
                .doesNotContain(inactiva.getId());
        // quesoFresco sigue representado por sus 2 presentaciones activas, no se agrega una fila
        // informativa para el (no esta "sin presentaciones").
        assertThat(pagina.totalElementos()).isEqualTo(3);
    }

    private FilaPrecioPresentacionResponse filaDe(PaginaResponse<FilaPrecioPresentacionResponse> pagina, java.util.UUID presentacionId) {
        return pagina.contenido().stream()
                .filter(f -> presentacionId.equals(f.presentacionId()))
                .findFirst().orElseThrow();
    }

    private Object claveDe(FilaPrecioPresentacionResponse fila) {
        return fila.presentacionId() != null ? fila.presentacionId() : fila.productoId();
    }

    private Familia familiaCon(String nombre) {
        Familia f = new Familia();
        f.setNombre(nombre);
        return f;
    }

    private Producto productoCon(String nombre, Familia familia) {
        Producto p = new Producto();
        p.setNombre(nombre);
        p.setFamilia(familia);
        return p;
    }

    private Presentacion presentacionCon(String nombre, Producto producto) {
        Presentacion p = new Presentacion();
        p.setNombre(nombre);
        p.setProducto(producto);
        p.setUnidadConteo("unidad");
        return p;
    }

    private PrecioQueso precioCon(Presentacion presentacion, TipoClienteVenta modalidad, String precio, LocalDate vigenteDesde) {
        PrecioQueso p = new PrecioQueso();
        p.setPresentacion(presentacion);
        p.setModalidad(modalidad);
        p.setPrecio(new BigDecimal(precio));
        p.setVigenteDesde(vigenteDesde);
        return p;
    }
}
