package com.planta.acopio.integration;

import com.planta.acopio.dto.AjusteInsumoRequest;
import com.planta.acopio.dto.IngresoInsumoRequest;
import com.planta.acopio.dto.InsumoRequest;
import com.planta.acopio.dto.SalidaInventarioRequest;
import com.planta.acopio.entity.MotivoSalida;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.InsumoService;
import com.planta.acopio.service.InventarioInsumoService;
import com.planta.acopio.service.LoteInsumoService;
import com.planta.acopio.service.SalidaInventarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lotes de insumo (V43) contra PostgreSQL real: cada ingreso crea su lote (codigo autogenerado), el consumo y las salidas se
 * reparten en FEFO (vence antes primero; sin vencimiento, FIFO por ingreso), los lotes vencidos no se consumen en produccion y
 * salen primero en una salida por vencimiento, y en todo momento stock = suma de lotes = suma de movimientos (y, por lote,
 * disponible = suma de sus movimientos).
 */
@SpringBootTest
class LoteInsumoIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    @Autowired private InsumoService insumoService;
    @Autowired private InsumoRepository insumoRepository;
    @Autowired private InventarioInsumoService inventarioInsumoService;
    @Autowired private SalidaInventarioService salidaService;
    @Autowired private LoteInsumoService loteService;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private String s;

    @BeforeEach
    void seed() {
        s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("LOTE_IT_" + s);
        rol = rolRepository.save(rol);
        Usuario u = new Usuario();
        u.setNombre("Lotes Test " + s);
        u.setEmail("lotes-" + s + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        usuarioRepository.save(u);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u.getEmail(), null));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private UUID crearInsumo(String stock) {
        return insumoService.crear(new InsumoRequest("Azucar lotes " + s, "kg", new BigDecimal(stock))).id();
    }

    /** Ingreso de `cantidad` kg; si se indica `vence`, el lote nuevo lo lleva (el ingreso con vencimiento llega en el paso 6). */
    private String ingreso(UUID insumoId, String cantidad, LocalDate vence) {
        inventarioInsumoService.registrarIngreso(insumoId, new IngresoInsumoRequest(new BigDecimal(cantidad), null, null));
        String codigo = jdbc.queryForObject("SELECT codigo FROM lote_insumo WHERE insumo_id = ? ORDER BY creado_en DESC, id DESC LIMIT 1", String.class, insumoId);
        if (vence != null) {
            jdbc.update("UPDATE lote_insumo SET fecha_vencimiento = ? WHERE insumo_id = ? AND codigo = ?", vence, insumoId, codigo);
        }
        return codigo;
    }

    private BigDecimal disponible(UUID insumoId, String codigo) {
        return jdbc.queryForObject("SELECT cantidad_disponible FROM lote_insumo WHERE insumo_id = ? AND codigo = ?", BigDecimal.class, insumoId, codigo);
    }

    private void salida(UUID insumoId, String cantidad, MotivoSalida tipo) {
        salidaService.registrar(insumoId, true, new SalidaInventarioRequest(UUID.randomUUID(), new BigDecimal(cantidad), tipo, "prueba", null, null));
    }

    /** stock del insumo = suma de lotes = suma de movimientos; y por lote: disponible = suma de sus movimientos. */
    private void invariantes(UUID insumoId) {
        BigDecimal stock = jdbc.queryForObject("SELECT stock FROM insumo WHERE id = ?", BigDecimal.class, insumoId);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(cantidad_disponible), 0) FROM lote_insumo WHERE insumo_id = ?", BigDecimal.class, insumoId)).isEqualByComparingTo(stock);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_insumo WHERE insumo_id = ?", BigDecimal.class, insumoId)).isEqualByComparingTo(stock);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lote_insumo l WHERE l.insumo_id = ? AND l.cantidad_disponible <> "
                + "(SELECT COALESCE(SUM(m.cantidad), 0) FROM movimiento_insumo m WHERE m.lote_insumo_id = l.id)", Integer.class, insumoId)).isZero();
    }

    @Test
    void cadaIngresoCreaSuLoteConCodigoAutogeneradoYUnInsumoNuevoNaceConSuLoteInicial() {
        UUID id = crearInsumo("10");
        assertThat(jdbc.queryForMap("SELECT codigo, cantidad_inicial, cantidad_disponible, origen FROM lote_insumo WHERE insumo_id = ?", id))
                .containsEntry("codigo", "INICIAL").containsEntry("origen", "INICIAL");

        String primero = ingreso(id, "5", null);
        String segundo = ingreso(id, "7.5", null);

        String hoy = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd").format(java.time.LocalDate.now(java.time.ZoneId.of("America/Lima")));
        assertThat(primero).isEqualTo("AZU-" + hoy + "-01");
        assertThat(segundo).isEqualTo("AZU-" + hoy + "-02");
        assertThat(disponible(id, primero)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lote_insumo WHERE insumo_id = ?", Integer.class, id)).isEqualTo(3);
        invariantes(id);
        // Cada movimiento de ingreso referencia SU lote.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_insumo m JOIN lote_insumo l ON l.id = m.lote_insumo_id WHERE m.insumo_id = ? AND m.tipo_movimiento = 'INGRESO' AND l.origen = 'INGRESO'",
                Integer.class, id)).isEqualTo(2);
    }

    @Test
    void lasSalidasSeRepartenEnFefoYDejanUnMovimientoPorLote() {
        UUID id = crearInsumo("10");                                                    // INICIAL: sin vencimiento, el mas antiguo
        LocalDate hoy = LocalDate.now();
        String a = ingreso(id, "3", hoy.plusDays(10));
        String b = ingreso(id, "4", hoy.plusDays(20));
        String c = ingreso(id, "2", hoy.plusDays(5));

        salida(id, "8", MotivoSalida.OTRA);       // vence antes primero: C(2), A(3), B(3 de 4); INICIAL no se toca

        assertThat(disponible(id, c)).isEqualByComparingTo("0");
        assertThat(disponible(id, a)).isEqualByComparingTo("0");
        assertThat(disponible(id, b)).isEqualByComparingTo("1");
        assertThat(disponible(id, "INICIAL")).isEqualByComparingTo("10");
        assertThat(jdbc.queryForList("SELECT l.codigo FROM movimiento_insumo m JOIN lote_insumo l ON l.id = m.lote_insumo_id WHERE m.insumo_id = ? "
                + "AND m.tipo_movimiento = 'SALIDA_OTRA' ORDER BY m.orden", String.class, id)).containsExactly(c, a, b);
        invariantes(id);

        // Sin vencimiento en juego, FIFO por ingreso: despues de B, sigue el INICIAL.
        salida(id, "4", MotivoSalida.DETERIORO);
        assertThat(disponible(id, b)).isEqualByComparingTo("0");
        assertThat(disponible(id, "INICIAL")).isEqualByComparingTo("7");
        invariantes(id);
    }

    @Test
    void unLoteVencidoNoSeConsumeEnProduccionYSaleExclusivamentePorUnaSalidaPorVencimiento() {
        UUID id = crearInsumo("4");
        LocalDate hoy = LocalDate.now();
        String vencido = ingreso(id, "6", hoy.minusDays(2));
        String vigente = ingreso(id, "5", hoy.plusDays(30));

        BigDecimal consumible = tx.execute((TransactionCallback<BigDecimal>) st -> loteService.stockConsumible(insumoRepository.findById(id).orElseThrow()));
        assertThat(consumible).isEqualByComparingTo("9");   // INICIAL 4 + vigente 5
        // Produccion pide 10: hay 15 de stock, pero 6 estan vencidos y no se consumen.
        assertThatThrownBy(() -> tx.executeWithoutResult(st -> {
            insumoRepository.bloquearStock(id);
            loteService.asignar(insumoRepository.findById(id).orElseThrow(), new BigDecimal("10"), LoteInsumoService.Prioridad.CONSUMO);
        })).isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("lotes vencidos").hasMessageContaining("solo hay 9");
        assertThat(disponible(id, vencido)).isEqualByComparingTo("6");   // nada cambio

        // La salida por vencimiento toma PRIMERO el lote vencido, aunque haya otros con existencia.
        salida(id, "6", MotivoSalida.VENCIMIENTO);
        assertThat(disponible(id, vencido)).isEqualByComparingTo("0");
        assertThat(disponible(id, vigente)).isEqualByComparingTo("5");
        invariantes(id);

        // Sin lotes vencidos, una salida por vencimiento sigue pudiendo declararse sobre el stock vigente (compatibilidad).
        salida(id, "2", MotivoSalida.VENCIMIENTO);
        invariantes(id);
    }

    @Test
    void elConteoEnContraSaleDeLosLotesEnFefoYElConteoAFavorVaAlLoteMasReciente() {
        UUID id = crearInsumo("10");
        LocalDate hoy = LocalDate.now();
        String a = ingreso(id, "5", hoy.plusDays(10));
        String b = ingreso(id, "5", hoy.plusDays(40));
        // Stock 20; se cuentan 12: faltan 8 -> A(5) y 3 de B.
        inventarioInsumoService.ajustar(id, new AjusteInsumoRequest(new BigDecimal("12"), "Conteo"));
        assertThat(disponible(id, a)).isEqualByComparingTo("0");
        assertThat(disponible(id, b)).isEqualByComparingTo("2");
        invariantes(id);

        // Se cuentan 15: sobran 3 -> al lote mas reciente (B).
        inventarioInsumoService.ajustar(id, new AjusteInsumoRequest(new BigDecimal("15"), "Conteo"));
        assertThat(disponible(id, b)).isEqualByComparingTo("5");
        invariantes(id);
    }

    @Test
    void unaSalidaQueNoAlcanzaNoDejaNadaAMedias() {
        UUID id = crearInsumo("3");
        ingreso(id, "2", LocalDate.now().plusDays(9));

        assertThatThrownBy(() -> salida(id, "6", MotivoSalida.OTRA)).isInstanceOf(ValidacionNegocioException.class);

        assertThat(jdbc.queryForObject("SELECT stock FROM insumo WHERE id = ?", BigDecimal.class, id)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_insumo WHERE insumo_id = ? AND tipo_movimiento = 'SALIDA_OTRA'", Integer.class, id)).isZero();
        invariantes(id);
    }
}
