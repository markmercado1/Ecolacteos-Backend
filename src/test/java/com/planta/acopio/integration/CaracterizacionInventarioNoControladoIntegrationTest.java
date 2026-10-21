package com.planta.acopio.integration;

import com.planta.acopio.dto.AjusteInventarioRequest;
import com.planta.acopio.dto.CrearVentaCarritoRequest;
import com.planta.acopio.dto.DetalleSalidaRequest;
import com.planta.acopio.dto.DetalleVentaRequest;
import com.planta.acopio.dto.FinalizarLoteRequest;
import com.planta.acopio.dto.InsumoRequest;
import com.planta.acopio.dto.InsumoResponse;
import com.planta.acopio.dto.MovimientoKardexResponse;
import com.planta.acopio.dto.PlanificarLoteRequest;
import com.planta.acopio.dto.PrecioQuesoRequest;
import com.planta.acopio.dto.RecepcionPlantaRequest;
import com.planta.acopio.dto.RecetaInsumoRequest;
import com.planta.acopio.dto.RecetaRequest;
import com.planta.acopio.dto.RecetaResponse;
import com.planta.acopio.dto.VentaRequest;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.AjusteInventarioService;
import com.planta.acopio.service.ConciliacionService;
import com.planta.acopio.service.InsumoService;
import com.planta.acopio.service.KardexService;
import com.planta.acopio.service.LoteProduccionService;
import com.planta.acopio.service.PrecioQuesoService;
import com.planta.acopio.service.RecetaService;
import com.planta.acopio.service.VentaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CARACTERIZACION (Fase 1, control de vencimiento): un producto SIN control de vencimiento debe comportarse
 * exactamente igual antes y despues de la Fase 1. Este escenario (recepcion -> receta -> iniciar -> finalizar ->
 * venta movil -> venta carrito -> dos ajustes) y la definicion de la tabla movimiento_inventario se fijaron
 * ejecutandolos sobre el codigo ANTERIOR a la Fase 1; los valores esperados de abajo son esa referencia.
 * Si esta prueba falla, algo cambio el libro, el kardex o los saldos de un producto no controlado.
 */
@Testcontainers
@SpringBootTest
class CaracterizacionInventarioNoControladoIntegrationTest {

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

    // Valores de referencia obtenidos con el codigo previo a la Fase 1 (ver comentario de clase).
    private static final String ESPERADO_ESCENARIO = 
            "libro=ENTRADA_PRODUCCION:9,SALIDA_VENTA:-2,SALIDA_VENTA:-1,AJUSTE:-1,AJUSTE:3 stock=8 kardex[0+12-4=8] ENTRADA_PRODUCCION|e=9|s=null|saldo=9|LOTE|Lote de producción|null;SALIDA_VENTA|e=null|s=2|saldo=7|VENTA|Venta · PUBLICO|null;SALIDA_VENTA|e=null|s=1|saldo=6|VENTA|Venta · PUBLICO|null;AJUSTE|e=null|s=1|saldo=5|AJUSTE|Conteo físico: 6 → 5|Rotura;AJUSTE|e=3|s=null|saldo=8|AJUSTE|Conteo físico: 5 → 8|Hallazgo";
    private static final String ESPERADO_HUELLA_TABLA = "063a1c91c30c5e137e976d7e923b9a8b";

    @Autowired private ConciliacionService conciliacionService;
    @Autowired private RecetaService recetaService;
    @Autowired private LoteProduccionService loteProduccionService;
    @Autowired private VentaService ventaService;
    @Autowired private PrecioQuesoService precioQuesoService;
    @Autowired private AjusteInventarioService ajusteInventarioService;
    @Autowired private InsumoService insumoService;
    @Autowired private KardexService kardexService;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private JdbcTemplate jdbc;

    private Usuario usuario;
    private Unidad unidad;
    private Presentacion pieza;
    private Producto andino;
    private InsumoResponse cuajo;

    @BeforeEach
    void seed() {
        String s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("CARAC_IT_" + s);
        rol = rolRepository.save(rol);
        Usuario u = new Usuario();
        u.setNombre("Carac Test " + s);
        u.setEmail("carac-" + s + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        usuario = usuarioRepository.save(u);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(usuario.getEmail(), null));
        Unidad un = new Unidad();
        un.setPlaca("CAR-" + s);
        un.setResponsable(usuario);
        unidad = unidadRepository.save(un);
        Familia f = new Familia();
        f.setNombre("Queso Carac " + s);
        Producto p = new Producto();
        p.setNombre("Queso Carac " + s);
        p.setFamilia(familiaRepository.save(f));
        andino = productoRepository.save(p);
        Presentacion pr = new Presentacion();
        pr.setNombre("Pieza");
        pr.setProducto(andino);
        pr.setUnidadConteo("pieza");
        pieza = presentacionRepository.save(pr);
        cuajo = insumoService.crear(new InsumoRequest("Cuajo C " + s, "g", new BigDecimal("15")));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private String ejecutarEscenario() {
        conciliacionService.registrarYConciliar(new RecepcionPlantaRequest(LocalDate.now(), null, unidad.getId(),
                new BigDecimal("1500"), new BigDecimal("1500"), List.of()));
        RecetaResponse receta = recetaService.crear(new RecetaRequest("Estándar", andino.getId(), pieza.getId(),
                new BigDecimal("100"), BigDecimal.ONE, List.of(new RecetaInsumoRequest(cuajo.id(), BigDecimal.ONE))));
        var plan = loteProduccionService.planificar(new PlanificarLoteRequest(UUID.randomUUID().toString(),
                LocalDate.now(), receta.id(), new BigDecimal("1000")));
        loteProduccionService.iniciar(plan.id());
        loteProduccionService.finalizar(plan.id(),
                new FinalizarLoteRequest(List.of(new DetalleSalidaRequest(pieza.getId(), 9)), null));
        precioQuesoService.crear(new PrecioQuesoRequest(pieza.getId(), "PUBLICO", new BigDecimal("25"), LocalDate.now().minusDays(1)));
        ventaService.registrarOIgnorarSiDuplicado(new VentaRequest(UUID.randomUUID().toString(), LocalDate.now(),
                "PUBLICO", pieza.getId(), 2, new BigDecimal("25")));
        ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(UUID.randomUUID().toString(),
                LocalDate.now(), "PUBLICO", List.of(new DetalleVentaRequest(pieza.getId(), 1))));
        ajusteInventarioService.crear(new AjusteInventarioRequest(pieza.getId(), 5, "Rotura"));
        ajusteInventarioService.crear(new AjusteInventarioRequest(pieza.getId(), 8, "Hallazgo"));

        // Resumen determinista (sin ids ni fechas): libro ordenado + kardex de la presentacion.
        String libro = jdbc.query("SELECT tipo_movimiento, cantidad FROM movimiento_inventario WHERE presentacion_id = ? "
                        + "ORDER BY creado_en, id", (rs, i) -> rs.getString(1) + ":" + rs.getInt(2), pieza.getId())
                .stream().collect(Collectors.joining(","));
        Integer stock = jdbc.queryForObject("SELECT COALESCE(SUM(cantidad),0) FROM movimiento_inventario WHERE presentacion_id = ?",
                Integer.class, pieza.getId());
        var kardex = kardexService.presentacion(pieza.getId(), null, null, PageRequest.of(0, 200));
        String filas = kardex.movimientos().contenido().stream()
                .map(m -> m.tipoMovimiento() + "|e=" + num(m.entrada()) + "|s=" + num(m.salida()) + "|saldo=" + num(m.saldo())
                        + "|" + m.documentoTipo() + "|" + m.referencia() + "|" + m.motivo())
                .collect(Collectors.joining(";"));
        return "libro=" + libro + " stock=" + stock + " kardex[" + num(kardex.saldoInicial()) + "+" + num(kardex.totalEntradas())
                + "-" + num(kardex.totalSalidas()) + "=" + num(kardex.saldoFinal()) + "] " + filas;
    }

    private static String num(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }

    /** Huella de la DEFINICION de movimiento_inventario: columnas, indices y restricciones. */
    private String huellaDeLaTabla() {
        String columnas = jdbc.queryForList("SELECT column_name || ':' || data_type || ':' || is_nullable || ':' || COALESCE(character_maximum_length::text,'') "
                        + "FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'movimiento_inventario' ORDER BY column_name",
                String.class).stream().collect(Collectors.joining("|"));
        String indices = jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'movimiento_inventario' ORDER BY indexname",
                String.class).stream().collect(Collectors.joining("|"));
        String restricciones = jdbc.queryForList("SELECT conname || ':' || pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conrelid = 'public.movimiento_inventario'::regclass ORDER BY conname",
                String.class).stream().collect(Collectors.joining("|"));
        return jdbc.queryForObject("SELECT md5(?)", String.class, columnas + "##" + indices + "##" + restricciones);
    }

    @Test
    void unProductoSinControlSeComportaIgualQueAntesDeLaFase1() {
        String escenario = ejecutarEscenario();
        String huella = huellaDeLaTabla();
        System.out.println("CARAC_ESCENARIO=" + escenario);
        System.out.println("CARAC_HUELLA=" + huella);
        assertThat(escenario).isEqualTo(ESPERADO_ESCENARIO);
        assertThat(huella).isEqualTo(ESPERADO_HUELLA_TABLA);
    }
}
