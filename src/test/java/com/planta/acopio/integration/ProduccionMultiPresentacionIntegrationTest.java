package com.planta.acopio.integration;

import com.planta.acopio.dto.CrearLoteRequest;
import com.planta.acopio.dto.DetalleSalidaRequest;
import com.planta.acopio.dto.PrecioQuesoRequest;
import com.planta.acopio.dto.RecepcionPlantaRequest;
import com.planta.acopio.dto.VentaRequest;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.RangoRendimiento;
import com.planta.acopio.entity.UnidadContenido;
import com.planta.acopio.entity.UnidadRendimiento;
import com.planta.acopio.service.InventarioService;
import org.springframework.data.domain.PageRequest;
import jakarta.persistence.EntityManager;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.RecursoInactivoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.MovimientoInventarioRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.ConciliacionService;
import com.planta.acopio.service.InventarioLecheService;
import com.planta.acopio.service.LoteProduccionService;
import com.planta.acopio.service.PrecioQuesoService;
import com.planta.acopio.service.VentaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verificación end-to-end contra Postgres real (Testcontainers) de la Fase 3 (Producción con varias
 * presentaciones de salida e Inventario por presentación), pedida explícitamente por el usuario: un lote
 * con varias salidas consume leche una sola vez y deja stock independiente por presentación; una venta de
 * una presentación nunca afecta el stock de otra; producir no requiere ningún precio configurado.
 *
 * @Transactional a nivel de clase (mismo patrón que ProduccionLecheFlujoIntegrationTest): cada @Test corre
 * en su propia transacción con rollback automático.
 */
@Testcontainers
@SpringBootTest
@Transactional
class ProduccionMultiPresentacionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("acopio_lechero_test")
            .withUsername("acopio")
            .withPassword("acopio");

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private ConciliacionService conciliacionService;
    @Autowired private LoteProduccionService loteProduccionService;
    @Autowired private InventarioLecheService inventarioLecheService;
    @Autowired private PrecioQuesoService precioQuesoService;
    @Autowired private VentaService ventaService;
    @Autowired private MovimientoInventarioRepository movimientoInventarioRepository;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private InventarioService inventarioService;
    @Autowired private EntityManager entityManager;

    @Test
    void rangoExcedidoSinMotivoNoConsumeLecheNiGeneraStock() {
        yogurt.setRangoRendimiento(new RangoRendimiento(BigDecimal.TEN, new BigDecimal("20"), UnidadRendimiento.L));
        botella1L.setContenidoUnidad(UnidadContenido.L);
        entityManager.flush();
        var detalles = List.of(new DetalleSalidaRequest(botella1L.getId(), 100));
        var sinMotivo = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), yogurt.getId(),
                new BigDecimal("100"), detalles);
        assertThatThrownBy(() -> loteProduccionService.crear(sinMotivo))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("indica un motivo");
        assertThat(inventarioLecheService.obtenerSaldoDisponibleLitros()).isEqualByComparingTo("300");
        assertThat(movimientoInventarioRepository.sumarCantidadPorPresentacion(botella1L.getId())).isZero();
    }

    @Test
    void motivoYRevisionSePersistenYElReintentoNoDuplicaElConsumo() {
        yogurt.setRangoRendimiento(new RangoRendimiento(BigDecimal.TEN, new BigDecimal("20"), UnidadRendimiento.L));
        botella1L.setContenidoUnidad(UnidadContenido.L);
        var solicitud = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), yogurt.getId(),
                new BigDecimal("100"), List.of(new DetalleSalidaRequest(botella1L.getId(), 100)), "Proceso excepcional revisado");
        var lote = loteProduccionService.crear(solicitud);
        entityManager.flush();
        entityManager.clear();
        var recuperado = loteProduccionService.crear(solicitud);
        assertThat(recuperado.id()).isEqualTo(lote.id());
        assertThat(recuperado.motivoRendimiento()).isEqualTo("Proceso excepcional revisado");
        assertThat(recuperado.revisionRendimiento()).contains("100 L por 100 L", "10–20 L");
        assertThat(inventarioLecheService.obtenerSaldoDisponibleLitros()).isEqualByComparingTo("200");
    }

    @Test
    void familiaFiltraAntesDePaginarConTotalesCorrectos() {
        var familiaId = yogurt.getFamilia().getId();
        var primera = inventarioService.listarStockPaginado(familiaId, null, PageRequest.of(0, 1));
        var segunda = inventarioService.listarStockPaginado(familiaId, null, PageRequest.of(1, 1));
        assertThat(primera.totalElementos()).isEqualTo(2);
        assertThat(primera.totalPaginas()).isEqualTo(2);
        assertThat(primera.contenido()).singleElement().satisfies(fila -> assertThat(fila.familiaId()).isEqualTo(familiaId));
        assertThat(segunda.contenido().get(0).presentacionId()).isNotEqualTo(primera.contenido().get(0).presentacionId());
        assertThat(inventarioService.listarStockPaginado(UUID.randomUUID(), null, PageRequest.of(0, 10)).totalElementos()).isZero();
    }

    private Usuario responsable;
    private Unidad unidad;
    private Producto yogurt;
    private Presentacion botella1L;
    private Presentacion botella2L;

    @BeforeEach
    void seedComun() {
        Rol rol = rolRepository.save(rolCon("PROD_MULTI_IT_" + (System.nanoTime() % 1_000_000_000L)));
        responsable = usuarioRepository.save(usuarioCon(rol));
        unidad = unidadRepository.save(unidadCon(responsable));
        Familia familia = familiaRepository.save(familiaCon());
        yogurt = productoRepository.save(productoCon(familia));
        botella1L = presentacionRepository.save(presentacionCon("Botella 1 L", yogurt));
        botella2L = presentacionRepository.save(presentacionCon("Botella 2 L", yogurt));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(responsable.getEmail(), null));

        conciliacionService.registrarYConciliar(new RecepcionPlantaRequest(
                LocalDate.now(), null, unidad.getId(), BigDecimal.valueOf(300), BigDecimal.valueOf(300), List.of()));
    }

    @Test
    void unLoteConVariasSalidasConsumeLaLecheUnaSolaVezYDejaStockIndependientePorPresentacion() {
        var lote = loteProduccionService.crear(new CrearLoteRequest(
                UUID.randomUUID().toString(), LocalDate.now(), yogurt.getId(), BigDecimal.valueOf(100),
                List.of(new DetalleSalidaRequest(botella1L.getId(), 40), new DetalleSalidaRequest(botella2L.getId(), 20))));

        // Leche: un solo consumo de 100 L (no 200), saldo real verificado contra Postgres.
        assertThat(inventarioLecheService.obtenerSaldoDisponibleLitros()).isEqualByComparingTo(BigDecimal.valueOf(200));

        assertThat(lote.detalles()).hasSize(2);

        // Stock independiente: 40 en Botella 1 L, 20 en Botella 2 L -- nunca 60 mezclado en ninguna de las dos.
        assertThat(movimientoInventarioRepository.sumarCantidadPorPresentacion(botella1L.getId())).isEqualTo(40);
        assertThat(movimientoInventarioRepository.sumarCantidadPorPresentacion(botella2L.getId())).isEqualTo(20);
    }

    @Test
    void producirNoRequiereNingunPrecioConfigurado() {
        // Ninguna llamada a PrecioQuesoService en todo este test -- si LoteProduccionService dependiera de
        // un precio existente, esta produccion fallaria.
        var lote = loteProduccionService.crear(new CrearLoteRequest(
                UUID.randomUUID().toString(), LocalDate.now(), yogurt.getId(), BigDecimal.valueOf(50),
                List.of(new DetalleSalidaRequest(botella1L.getId(), 25))));

        assertThat(lote.detalles()).hasSize(1);
        assertThat(movimientoInventarioRepository.sumarCantidadPorPresentacion(botella1L.getId())).isEqualTo(25);
    }

    @Test
    void unaVentaDeUnaPresentacionDescuentaSoloEsaPresentacionSinAfectarLaOtra() {
        loteProduccionService.crear(new CrearLoteRequest(
                UUID.randomUUID().toString(), LocalDate.now(), yogurt.getId(), BigDecimal.valueOf(100),
                List.of(new DetalleSalidaRequest(botella1L.getId(), 40), new DetalleSalidaRequest(botella2L.getId(), 20))));

        precioQuesoService.crear(new PrecioQuesoRequest(botella1L.getId(), "PUBLICO", BigDecimal.valueOf(8.50), LocalDate.now().minusDays(1)));

        var venta = ventaService.registrarOIgnorarSiDuplicado(new VentaRequest(
                UUID.randomUUID().toString(), LocalDate.now(), "PUBLICO", botella1L.getId(), 15, BigDecimal.valueOf(999) // precio del cliente, ignorado
        ));

        assertThat(venta.precioUnitario()).isEqualByComparingTo("8.50"); // resuelto por el servidor, no el enviado
        assertThat(venta.presentacionId()).isEqualTo(botella1L.getId());

        // Botella 1 L: 40 producidas - 15 vendidas = 25. Botella 2 L: sigue en 20, sin tocar.
        assertThat(movimientoInventarioRepository.sumarCantidadPorPresentacion(botella1L.getId())).isEqualTo(25);
        assertThat(movimientoInventarioRepository.sumarCantidadPorPresentacion(botella2L.getId())).isEqualTo(20);
    }

    @Test
    void rechazaLaVentaDeUnaPresentacionSinStockAunqueLaOtraPresentacionDelMismoProductoSiTenga() {
        loteProduccionService.crear(new CrearLoteRequest(
                UUID.randomUUID().toString(), LocalDate.now(), yogurt.getId(), BigDecimal.valueOf(100),
                List.of(new DetalleSalidaRequest(botella1L.getId(), 40), new DetalleSalidaRequest(botella2L.getId(), 20))));
        precioQuesoService.crear(new PrecioQuesoRequest(botella2L.getId(), "PUBLICO", BigDecimal.valueOf(12.00), LocalDate.now().minusDays(1)));

        // Botella 2 L solo tiene 20 unidades; pedir 25 debe rechazarse aunque Botella 1 L tenga 40 disponibles.
        var dto = new VentaRequest(UUID.randomUUID().toString(), LocalDate.now(), "PUBLICO", botella2L.getId(), 25, BigDecimal.valueOf(12.00));

        assertThatThrownBy(() -> ventaService.registrarOIgnorarSiDuplicado(dto))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("Stock insuficiente");

        assertThat(movimientoInventarioRepository.sumarCantidadPorPresentacion(botella1L.getId())).isEqualTo(40); // sin tocar
        assertThat(movimientoInventarioRepository.sumarCantidadPorPresentacion(botella2L.getId())).isEqualTo(20); // sin tocar
    }

    @Test
    void rechazaUnLoteConUnaPresentacionDesactivadaSinEscribirNadaDeEseLote() {
        Presentacion inactiva = presentacionRepository.save(presentacionCon("Descontinuada", yogurt));
        inactiva.setActivo(false);
        presentacionRepository.save(inactiva);

        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), yogurt.getId(), BigDecimal.valueOf(50),
                List.of(new DetalleSalidaRequest(inactiva.getId(), 10)));

        assertThatThrownBy(() -> loteProduccionService.crear(dto)).isInstanceOf(RecursoInactivoException.class);

        // Leche no se toco: el saldo sigue en 300 (nada del plan de consumo se confirmo).
        assertThat(inventarioLecheService.obtenerSaldoDisponibleLitros()).isEqualByComparingTo(BigDecimal.valueOf(300));
        assertThat(movimientoInventarioRepository.sumarCantidadPorPresentacion(inactiva.getId())).isEqualTo(0);
    }

    private Rol rolCon(String nombre) {
        Rol r = new Rol();
        r.setNombre(nombre);
        return r;
    }

    private Usuario usuarioCon(Rol rol) {
        Usuario u = new Usuario();
        u.setNombre("Produccion Multi Presentacion Test");
        u.setEmail("produccion-multi-" + System.nanoTime() + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        return u;
    }

    private Unidad unidadCon(Usuario responsable) {
        Unidad u = new Unidad();
        u.setPlaca("MULT-" + (System.nanoTime() % 1_000_000_000L));
        u.setResponsable(responsable);
        return u;
    }

    private Familia familiaCon() {
        Familia f = new Familia();
        f.setNombre("Yogures Multi Test " + System.nanoTime());
        return f;
    }

    private Producto productoCon(Familia familia) {
        Producto p = new Producto();
        p.setNombre("Yogurt de fresa Multi Test " + System.nanoTime());
        p.setFamilia(familia);
        // Sin rendimientoEsperadoPct: familia sin regla de conversion litros->unidades (no es queso).
        return p;
    }

    private Presentacion presentacionCon(String nombre, Producto producto) {
        Presentacion p = new Presentacion();
        p.setNombre(nombre);
        p.setProducto(producto);
        p.setContenidoCantidad(BigDecimal.ONE);
        p.setUnidadConteo("botella");
        return p;
    }
}
