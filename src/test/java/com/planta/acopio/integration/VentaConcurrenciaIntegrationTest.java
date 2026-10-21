package com.planta.acopio.integration;

import com.planta.acopio.dto.AjusteInventarioRequest;
import com.planta.acopio.dto.CrearVentaCarritoRequest;
import com.planta.acopio.dto.DetalleVentaRequest;
import com.planta.acopio.dto.PrecioQuesoRequest;
import com.planta.acopio.dto.VentaRequest;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.AjusteInventarioService;
import com.planta.acopio.service.PrecioQuesoService;
import com.planta.acopio.service.VentaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 2 -- paso 0: LINEA BASE de concurrencia de ventas contra PostgreSQL REAL (hasta ahora solo existia la
 * simulacion con Mockito de InventarioServiceTest). Cada hilo abre su PROPIA transaccion real, igual que dos
 * peticiones HTTP simultaneas: se ejercita el SELECT ... FOR UPDATE de la presentacion de verdad.
 *
 * Producto SIN control de vencimiento (el camino historico). Las mismas invariantes deben seguir valiendo
 * cuando la Fase 2 agregue FEFO para productos controlados: nunca stock negativo, nunca dos ventas sobre las
 * mismas unidades, el saldo del libro coincide con lo vendido, y sin interbloqueos.
 *
 * Sin @Transactional a proposito. Cada test crea su propia presentacion (el stock es por presentacion), asi
 * que los tests no se contaminan entre si.
 */
@Testcontainers
@SpringBootTest
class VentaConcurrenciaIntegrationTest {

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

    private enum Resultado { OK, SIN_STOCK, CONFLICTO, OTRO }

    private record Intento(Resultado resultado, Throwable error) {}

    @Autowired private VentaService ventaService;
    @Autowired private AjusteInventarioService ajusteInventarioService;
    @Autowired private PrecioQuesoService precioQuesoService;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private JdbcTemplate jdbc;

    private Usuario usuario;

    @BeforeEach
    void seedUsuario() {
        String s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("VENTA_CONC_IT_" + s);
        rol = rolRepository.save(rol);
        Usuario u = new Usuario();
        u.setNombre("Venta Concurrencia " + s);
        u.setEmail("venta-conc-" + s + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        usuario = usuarioRepository.save(u);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(usuario.getEmail(), null));
    }

    /** Presentacion nueva (producto sin control de vencimiento) con precio PUBLICO y stock inicial por conteo. */
    private Presentacion presentacionConStock(int stock) {
        String s = String.valueOf(System.nanoTime());
        Familia f = new Familia();
        f.setNombre("Queso Conc " + s);
        Producto p = new Producto();
        p.setNombre("Queso Conc " + s);
        p.setFamilia(familiaRepository.save(f));
        p = productoRepository.save(p);
        Presentacion pr = new Presentacion();
        pr.setNombre("Pieza");
        pr.setProducto(p);
        pr.setUnidadConteo("pieza");
        pr = presentacionRepository.save(pr);
        precioQuesoService.crear(new PrecioQuesoRequest(pr.getId(), "PUBLICO", new BigDecimal("25"), LocalDate.now().minusDays(1)));
        if (stock > 0) {
            ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), stock, "Stock inicial de la prueba"));
        }
        return pr;
    }

    /** Lanza las tareas en hilos distintos, sincronizadas en una barrera, y recoge el resultado de cada una. */
    private List<Intento> correrConcurrentes(List<Callable<Void>> tareas) throws Exception {
        CyclicBarrier arranque = new CyclicBarrier(tareas.size());
        ExecutorService pool = Executors.newFixedThreadPool(tareas.size());
        List<Future<Intento>> futuros = new ArrayList<>();
        for (Callable<Void> tarea : tareas) {
            futuros.add(pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(usuario.getEmail(), null));
                try {
                    arranque.await(10, TimeUnit.SECONDS);
                    tarea.call();
                    return new Intento(Resultado.OK, null);
                } catch (ValidacionNegocioException ex) {
                    return new Intento(Resultado.SIN_STOCK, ex);
                } catch (ConflictoNegocioException ex) {
                    return new Intento(Resultado.CONFLICTO, ex);
                } catch (Throwable ex) {
                    return new Intento(Resultado.OTRO, ex);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }
        List<Intento> resultados = new ArrayList<>();
        for (Future<Intento> f : futuros) {
            resultados.add(f.get(30, TimeUnit.SECONDS)); // un interbloqueo no resuelto se ve como timeout, no se cuelga la suite
        }
        pool.shutdown();
        return resultados;
    }

    private long contar(List<Intento> intentos, Resultado r) {
        return intentos.stream().filter(i -> i.resultado() == r).count();
    }

    private int stockDelLibro(UUID presentacionId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(cantidad),0) FROM movimiento_inventario WHERE presentacion_id = ?",
                Integer.class, presentacionId);
    }

    private int salidasDeVenta(UUID presentacionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM movimiento_inventario WHERE presentacion_id = ? AND tipo_movimiento = 'SALIDA_VENTA'",
                Integer.class, presentacionId);
    }

    private Callable<Void> ventaMovil(UUID presentacionId, int cantidad) {
        return () -> {
            ventaService.registrarOIgnorarSiDuplicado(new VentaRequest(UUID.randomUUID().toString(), LocalDate.now(),
                    "PUBLICO", presentacionId, cantidad, new BigDecimal("25")));
            return null;
        };
    }

    // Stock 10, dos ventas simultaneas de 7: exactamente una debe pasar; la otra ve 3 y es rechazada.
    @Test
    void dosVentasDe7ConStock10SoloUnaTieneExitoYNuncaHayStockNegativo() throws Exception {
        Presentacion pr = presentacionConStock(10);

        List<Intento> r = correrConcurrentes(List.of(ventaMovil(pr.getId(), 7), ventaMovil(pr.getId(), 7)));

        assertThat(contar(r, Resultado.OTRO)).as("errores inesperados (interbloqueo, restriccion, etc.): %s", r).isZero();
        assertThat(contar(r, Resultado.OK)).isEqualTo(1);
        assertThat(contar(r, Resultado.SIN_STOCK)).isEqualTo(1);
        assertThat(stockDelLibro(pr.getId())).isEqualTo(3);
        assertThat(salidasDeVenta(pr.getId())).isEqualTo(1);
    }

    // Stock 10, ocho ventas simultaneas de 3: caben exactamente 3 (9 unidades); sobra 1; las otras 5 se rechazan.
    @Test
    void ochoVentasDe3ConStock10ExactamenteTresTienenExito() throws Exception {
        Presentacion pr = presentacionConStock(10);

        List<Callable<Void>> tareas = new ArrayList<>();
        for (int i = 0; i < 8; i++) tareas.add(ventaMovil(pr.getId(), 3));
        List<Intento> r = correrConcurrentes(tareas);

        assertThat(contar(r, Resultado.OTRO)).as("errores inesperados: %s", r).isZero();
        assertThat(contar(r, Resultado.OK)).isEqualTo(3);
        assertThat(contar(r, Resultado.SIN_STOCK)).isEqualTo(5);
        assertThat(stockDelLibro(pr.getId())).isEqualTo(1);
        assertThat(salidasDeVenta(pr.getId())).isEqualTo(3);
    }

    // Carritos que piden las MISMAS dos presentaciones en orden opuesto: el orden estable por id evita el
    // interbloqueo cruzado. Hay stock para todos (8 carritos x 1 unidad de cada presentacion = 8).
    @Test
    void carritosConLasMismasPresentacionesEnOrdenOpuestoNoSeInterbloquean() throws Exception {
        Presentacion a = presentacionConStock(8);
        Presentacion b = presentacionConStock(8);

        List<Callable<Void>> tareas = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            List<DetalleVentaRequest> lineas = (i % 2 == 0)
                    ? List.of(new DetalleVentaRequest(a.getId(), 1), new DetalleVentaRequest(b.getId(), 1))
                    : List.of(new DetalleVentaRequest(b.getId(), 1), new DetalleVentaRequest(a.getId(), 1));
            tareas.add(() -> {
                ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(
                        UUID.randomUUID().toString(), LocalDate.now(), "PUBLICO", lineas));
                return null;
            });
        }
        List<Intento> r = correrConcurrentes(tareas);

        assertThat(contar(r, Resultado.OTRO)).as("errores inesperados: %s", r).isZero();
        assertThat(contar(r, Resultado.OK)).isEqualTo(8);
        assertThat(stockDelLibro(a.getId())).isZero();
        assertThat(stockDelLibro(b.getId())).isZero();
        assertThat(salidasDeVenta(a.getId())).isEqualTo(8);
        assertThat(salidasDeVenta(b.getId())).isEqualTo(8);
    }

    // Un carrito que no puede completarse (una linea sin stock) no deja NINGUNA salida a medias, ni siquiera
    // de la linea que si tenia stock.
    @Test
    void unCarritoRechazadoPorUnaLineaSinStockNoDejaSalidasParciales() throws Exception {
        Presentacion conStock = presentacionConStock(5);
        Presentacion sinStock = presentacionConStock(1);

        List<Callable<Void>> tareas = List.of(() -> {
            ventaService.registrarCarritoOIgnorarSiDuplicado(new CrearVentaCarritoRequest(UUID.randomUUID().toString(),
                    LocalDate.now(), "PUBLICO", List.of(new DetalleVentaRequest(conStock.getId(), 2),
                            new DetalleVentaRequest(sinStock.getId(), 3))));
            return null;
        });
        List<Intento> r = correrConcurrentes(tareas);

        assertThat(r.get(0).resultado()).isEqualTo(Resultado.SIN_STOCK);
        assertThat(stockDelLibro(conStock.getId())).isEqualTo(5);
        assertThat(stockDelLibro(sinStock.getId())).isEqualTo(1);
        assertThat(salidasDeVenta(conStock.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM venta_detalle WHERE presentacion_id = ? OR presentacion_id = ?",
                Integer.class, conStock.getId(), sinStock.getId())).isZero();
    }

    // Reintentos simultaneos de la MISMA venta (mismo uuidCliente, mismo contenido): una sola venta y una sola
    // salida de inventario, pase lo que pase en la carrera por el UNIQUE(uuid_cliente).
    @Test
    void reintentosSimultaneosConElMismoUuidClienteDescuentanUnaSolaVez() throws Exception {
        Presentacion pr = presentacionConStock(10);
        String uuid = UUID.randomUUID().toString();

        List<Callable<Void>> tareas = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tareas.add(() -> {
                ventaService.registrarOIgnorarSiDuplicado(new VentaRequest(uuid, LocalDate.now(), "PUBLICO",
                        pr.getId(), 4, new BigDecimal("25")));
                return null;
            });
        }
        List<Intento> r = correrConcurrentes(tareas);

        assertThat(contar(r, Resultado.OTRO)).as("errores inesperados: %s", r).isZero();
        assertThat(contar(r, Resultado.OK)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM venta WHERE uuid_cliente = ?", Integer.class, uuid)).isEqualTo(1);
        assertThat(salidasDeVenta(pr.getId())).isEqualTo(1);
        assertThat(stockDelLibro(pr.getId())).isEqualTo(6);
    }

    // Venta de 7 y conteo fisico de 4, a la vez, sobre un stock de 10. Cualquiera sea el orden en que se
    // serialicen, el libro termina en 4: (venta -> 3, conteo a 4 -> +1) o (conteo a 4 -> -6, venta rechazada).
    @Test
    void ventaYAjusteSimultaneosSeSerializanYElLibroNuncaQuedaNegativo() throws Exception {
        Presentacion pr = presentacionConStock(10);

        List<Callable<Void>> tareas = List.of(
                ventaMovil(pr.getId(), 7),
                () -> {
                    ajusteInventarioService.crear(new AjusteInventarioRequest(pr.getId(), 4, "Conteo concurrente"));
                    return null;
                });
        List<Intento> r = correrConcurrentes(tareas);

        assertThat(contar(r, Resultado.OTRO)).as("errores inesperados: %s", r).isZero();
        // El ajuste siempre tiene exito (el delta se calcula bajo el bloqueo, nunca queda en cero aqui).
        assertThat(r.get(1).resultado()).isEqualTo(Resultado.OK);
        assertThat(stockDelLibro(pr.getId())).isEqualTo(4);
    }
}
