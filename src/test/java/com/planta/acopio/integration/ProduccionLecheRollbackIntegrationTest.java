package com.planta.acopio.integration;

import com.planta.acopio.dto.CrearLoteRequest;
import com.planta.acopio.dto.DetalleSalidaRequest;
import com.planta.acopio.dto.RecepcionPlantaRequest;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.LoteProduccion;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.LoteProduccionRecepcionConsumoRepository;
import com.planta.acopio.repository.LoteProduccionRepository;
import com.planta.acopio.repository.MovimientoInventarioLecheRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.ConciliacionService;
import com.planta.acopio.service.InventarioLecheService;
import com.planta.acopio.service.InventarioService;
import com.planta.acopio.service.LoteProduccionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Verifica la atomicidad real exigida explícitamente por el usuario (punto 12 del refactor): "la
 * transacción de consumo de leche + creación de producción + entrada de producto terminado sea atómica".
 * `InventarioService` (el ÚLTIMO paso de `LoteProduccionService.crear()`) se reemplaza por un mock que
 * falla a propósito -- así se fuerza una excepción DESPUÉS de que ya se guardaron, en la misma transacción
 * real de Postgres, el lote, la(s) fila(s) de consumo FIFO y el movimiento de salida de leche. Si el
 * rollback de Spring/Postgres funciona de verdad, ninguna de esas tres escrituras debe sobrevivir.
 *
 * Contexto de Spring separado a propósito (el @MockBean reemplaza el InventarioService real para TODA la
 * clase) -- por eso vive en su propio archivo con su propio contenedor, en vez de mezclarse con
 * ProduccionLecheFlujoIntegrationTest (que sí necesita el InventarioService real para que sus casos de
 * éxito funcionen).
 */
@Testcontainers
@SpringBootTest
class ProduccionLecheRollbackIntegrationTest {

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
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private LoteProduccionRepository loteProduccionRepository;
    @Autowired private LoteProduccionRecepcionConsumoRepository consumoRepository;
    @Autowired private MovimientoInventarioLecheRepository movimientoLecheRepository;

    // Reemplaza el bean real: se configura para fallar SOLO en el test que lo necesita: en el otro test de
    // esta clase (control, sin fallo forzado) se deja con su comportamiento por defecto de Mockito (no-op),
    // que para un metodo void no rompe nada.
    @MockBean private InventarioService inventarioService;

    private Usuario responsable;
    private Unidad unidad;
    private Producto producto;
    private Presentacion presentacion;

    @BeforeEach
    void seedComun() {
        Rol rol = rolRepository.save(rolCon("PROD_RB_IT_" + (System.nanoTime() % 1_000_000_000L)));
        responsable = usuarioRepository.save(usuarioCon(rol));
        unidad = unidadRepository.save(unidadCon(responsable));
        producto = productoRepository.save(productoCon());
        presentacion = presentacionRepository.save(presentacionCon(producto));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(responsable.getEmail(), null));

        conciliacionService.registrarYConciliar(new RecepcionPlantaRequest(
                LocalDate.now(), null, unidad.getId(), BigDecimal.valueOf(300), BigDecimal.valueOf(300), List.of()));
    }

    @Test
    void unFalloEnElUltimoPasoRevierteLoteConsumoYMovimientoDeLecheEnPostgresReal() {
        doThrow(new RuntimeException("fallo forzado de prueba en el ultimo paso"))
                .when(inventarioService).registrarEntradaProduccion(any());

        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), producto.getId(),
                BigDecimal.valueOf(150), List.of(new DetalleSalidaRequest(presentacion.getId(), 20)));

        assertThatThrownBy(() -> loteProduccionService.crear(dto)).isInstanceOf(RuntimeException.class);

        // Las tres escrituras que ya habian ocurrido DENTRO de la misma transaccion deben haber
        // desaparecido -- ninguna quedo a medias.
        assertThat(loteProduccionRepository.findByUuidCliente(dto.uuidCliente())).isEmpty();
        List<LoteProduccion> lotesDeEsteTipo = loteProduccionRepository.findAllByOrderByFechaDesc().stream()
                .filter(l -> l.getProducto().getId().equals(producto.getId())).toList();
        assertThat(lotesDeEsteTipo).isEmpty();

        // El saldo de leche vuelve a ser el de la recepcion original (300), no 150 -- la salida de leche
        // tambien se revirtio junto con el lote.
        assertThat(inventarioLecheService.obtenerSaldoDisponibleLitros()).isEqualByComparingTo(BigDecimal.valueOf(300));
        assertThat(movimientoLecheRepository.count()).isEqualTo(1); // solo la entrada de la recepcion, ninguna salida
    }

    private Rol rolCon(String nombre) {
        Rol r = new Rol();
        r.setNombre(nombre);
        return r;
    }

    private Usuario usuarioCon(Rol rol) {
        Usuario u = new Usuario();
        u.setNombre("Produccion Rollback Test");
        u.setEmail("produccion-rollback-" + System.nanoTime() + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        return u;
    }

    private Unidad unidadCon(Usuario responsable) {
        Unidad u = new Unidad();
        u.setPlaca("RBIT-" + (System.nanoTime() % 1_000_000_000L));
        u.setResponsable(responsable);
        return u;
    }

    private Producto productoCon() {
        Familia familia = new Familia();
        familia.setNombre("Queso Rollback Test " + System.nanoTime());
        Producto t = new Producto();
        t.setNombre("Queso Rollback Test " + System.nanoTime());
        t.setFamilia(familiaRepository.save(familia));
        t.setRendimientoEsperadoPct(BigDecimal.valueOf(12));
        return t;
    }

    private Presentacion presentacionCon(Producto producto) {
        Presentacion p = new Presentacion();
        p.setNombre("Unidad");
        p.setProducto(producto);
        p.setUnidadConteo("unidad");
        return p;
    }
}
