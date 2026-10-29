package com.planta.acopio.integration;

import com.planta.acopio.dto.ConteoInsumoRequest;
import com.planta.acopio.dto.ConteoInsumoResponse;
import com.planta.acopio.dto.IngresoInsumoRequest;
import com.planta.acopio.dto.InsumoRequest;
import com.planta.acopio.dto.LoteInsumoResponse;
import com.planta.acopio.dto.SalidaInventarioRequest;
import com.planta.acopio.entity.MotivoSalida;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.InsumoService;
import com.planta.acopio.service.InventarioInsumoService;
import com.planta.acopio.service.LoteInsumoConsultaService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Salidas y conteos por lote (V45) contra PostgreSQL real: salida desde un lote elegido o repartida en FEFO, motivos nuevos de
 * insumos (solo insumos), conteo fisico por lote, anulacion de un ingreso no usado y estado calculado de los lotes. En todo momento
 * stock = suma de lotes = suma de movimientos.
 */
@SpringBootTest
class SalidasConteosPorLoteIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    @Autowired private InsumoService insumoService;
    @Autowired private InventarioInsumoService inventarioInsumoService;
    @Autowired private SalidaInventarioService salidaService;
    @Autowired private LoteInsumoConsultaService loteConsulta;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private JdbcTemplate jdbc;

    private String s;
    private UUID insumo;

    @BeforeEach
    void seed() {
        s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("SAL_LOTE_" + s);
        rol = rolRepository.save(rol);
        Usuario u = new Usuario();
        u.setNombre("Salidas Lote " + s);
        u.setEmail("sal-lote-" + s + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        usuarioRepository.save(u);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u.getEmail(), null));
        insumo = insumoService.crear(new InsumoRequest("Azucar salidas " + s, "kg", new BigDecimal("10"))).id();
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private UUID ingreso(String cantidad, LocalDate vence) {
        inventarioInsumoService.registrarIngreso(insumo, new IngresoInsumoRequest(new BigDecimal(cantidad), null, null));
        UUID lote = jdbc.queryForObject("SELECT l.id FROM movimiento_insumo m JOIN lote_insumo l ON l.id = m.lote_insumo_id WHERE m.insumo_id = ? AND m.cantidad > 0 ORDER BY m.orden DESC LIMIT 1", UUID.class, insumo);
        if (vence != null) jdbc.update("UPDATE lote_insumo SET fecha_vencimiento = ? WHERE id = ?", vence, lote);
        return lote;
    }

    private UUID loteInicial() {
        return jdbc.queryForObject("SELECT id FROM lote_insumo WHERE insumo_id = ? AND codigo = 'INICIAL'", UUID.class, insumo);
    }

    private BigDecimal disponible(UUID lote) {
        return jdbc.queryForObject("SELECT cantidad_disponible FROM lote_insumo WHERE id = ?", BigDecimal.class, lote);
    }

    private void invariantes() {
        BigDecimal stock = jdbc.queryForObject("SELECT stock FROM insumo WHERE id = ?", BigDecimal.class, insumo);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(cantidad_disponible), 0) FROM lote_insumo WHERE insumo_id = ?", BigDecimal.class, insumo)).isEqualByComparingTo(stock);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_insumo WHERE insumo_id = ?", BigDecimal.class, insumo)).isEqualByComparingTo(stock);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lote_insumo l WHERE l.insumo_id = ? AND l.cantidad_disponible <> "
                + "(SELECT COALESCE(SUM(m.cantidad), 0) FROM movimiento_insumo m WHERE m.lote_insumo_id = l.id)", Integer.class, insumo)).isZero();
    }

    private SalidaInventarioRequest salida(String cantidad, MotivoSalida tipo, UUID lote) {
        return new SalidaInventarioRequest(UUID.randomUUID(), new BigDecimal(cantidad), tipo, "prueba", lote == null ? null : "INSUMO", lote);
    }

    @Test
    void laSalidaSaleDelLoteElegidoConSuMotivoYSiNoAlcanzaPideOtroLote() {
        UUID a = ingreso("5", LocalDate.now().plusDays(20));
        UUID b = ingreso("8", LocalDate.now().plusDays(40));

        // Elegido el lote B (aunque A venza antes): sale de B, con el motivo nuevo.
        var r = salidaService.registrar(insumo, true, salida("3", MotivoSalida.DERRAME, b));
        assertThat(r.tipo()).isEqualTo("DERRAME");
        assertThat(disponible(b)).isEqualByComparingTo("5");
        assertThat(disponible(a)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("SELECT tipo_movimiento FROM movimiento_insumo WHERE id = ?", String.class, r.movimientoId())).isEqualTo("SALIDA_DERRAME");

        // El lote elegido no alcanza: se rechaza y no queda nada a medias.
        assertThatThrownBy(() -> salidaService.registrar(insumo, true, salida("6", MotivoSalida.MERMA, b)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("solo tiene 5");
        assertThat(disponible(b)).isEqualByComparingTo("5");
        invariantes();

        // Sin lote elegido se reparte en FEFO: A(5) y 1 del INICIAL? (A vence antes que B; INICIAL no vence y va al final).
        salidaService.registrar(insumo, true, salida("7", MotivoSalida.DEVOLUCION_PROVEEDOR, null));
        assertThat(disponible(a)).isEqualByComparingTo("0");
        assertThat(disponible(b)).isEqualByComparingTo("3");
        assertThat(jdbc.queryForList("SELECT DISTINCT tipo_movimiento FROM movimiento_insumo WHERE insumo_id = ? AND tipo_movimiento LIKE 'SALIDA%'", String.class, insumo))
                .contains("SALIDA_DERRAME", "SALIDA_DEVOLUCION_PROVEEDOR");
        invariantes();
        // Lote de otro insumo: se rechaza.
        UUID otroInsumo = insumoService.crear(new InsumoRequest("Sal salidas " + s, "kg", new BigDecimal("2"))).id();
        assertThatThrownBy(() -> salidaService.registrar(otroInsumo, true, salida("1", MotivoSalida.MERMA, a)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("no es de");
    }

    @Test
    void losMotivosNuevosSonSoloDeInsumosYUnLoteSoloSeIndicaConOrigenInsumo() {
        // El producto terminado rechaza los motivos de insumos y el origen INSUMO.
        UUID presentacionInexistente = UUID.randomUUID();
        assertThatThrownBy(() -> salidaService.registrar(presentacionInexistente, false, salida("1", MotivoSalida.MERMA, null)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("solo para insumos");
        // Para un insumo, el origen del lote debe ser INSUMO.
        assertThatThrownBy(() -> salidaService.registrar(insumo, true, new SalidaInventarioRequest(UUID.randomUUID(), BigDecimal.ONE, MotivoSalida.OTRA, "x", "LOTE", loteInicial())))
                .isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void elConteoPorLoteAjustaCadaLoteConSuMovimientoYElMotivo() {
        UUID a = ingreso("5", LocalDate.now().plusDays(20));
        UUID inicial = loteInicial();
        // Sistema: INICIAL 10, A 5 (stock 15). Se cuentan INICIAL 7 (faltan 3) y A 6 (sobra 1); neto -2.
        ConteoInsumoResponse r = inventarioInsumoService.registrarConteoPorLote(insumo, new ConteoInsumoRequest("Conteo de octubre",
                List.of(new ConteoInsumoRequest.Linea(inicial, new BigDecimal("7")), new ConteoInsumoRequest.Linea(a, new BigDecimal("6")))));

        assertThat(r.saldoAnterior()).isEqualByComparingTo("15");
        assertThat(r.saldoResultante()).isEqualByComparingTo("13");
        assertThat(r.diferencias()).extracting(ConteoInsumoResponse.Diferencia::variacion).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("-3"), new BigDecimal("1"));
        assertThat(disponible(inicial)).isEqualByComparingTo("7");
        assertThat(disponible(a)).isEqualByComparingTo("6");
        assertThat(jdbc.queryForList("SELECT motivo FROM movimiento_insumo WHERE insumo_id = ? AND tipo_movimiento = 'AJUSTE'", String.class, insumo)).containsOnly("Conteo de octubre");
        invariantes();

        // Un lote que coincide no genera movimiento; si NINGUNO difiere, se rechaza.
        assertThatThrownBy(() -> inventarioInsumoService.registrarConteoPorLote(insumo, new ConteoInsumoRequest("Otra vez",
                List.of(new ConteoInsumoRequest.Linea(inicial, new BigDecimal("7.00")), new ConteoInsumoRequest.Linea(a, new BigDecimal("6"))))))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("no hay ninguna diferencia");
        // Un lote repetido o de otro insumo se rechaza sin cambiar nada.
        assertThatThrownBy(() -> inventarioInsumoService.registrarConteoPorLote(insumo, new ConteoInsumoRequest("x",
                List.of(new ConteoInsumoRequest.Linea(a, new BigDecimal("1")), new ConteoInsumoRequest.Linea(a, new BigDecimal("2"))))))
                .isInstanceOf(ValidacionNegocioException.class);
        assertThat(disponible(a)).isEqualByComparingTo("6");
        invariantes();
    }

    @Test
    void anularUnIngresoNoUsadoLoReversaYUnoUsadoOElInicialNoSePuedeAnular() {
        UUID a = ingreso("5", null);
        UUID b = ingreso("4", null);
        salidaService.registrar(insumo, true, salida("1", MotivoSalida.OTRA, b));     // B ya se uso

        inventarioInsumoService.anularIngreso(a, "Se cargó dos veces");
        assertThat(disponible(a)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT tipo_movimiento || ':' || cantidad FROM movimiento_insumo WHERE lote_insumo_id = ? AND tipo_movimiento = 'ANULACION_INGRESO'", String.class, a))
                .isEqualTo("ANULACION_INGRESO:-5.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_insumo WHERE lote_insumo_id = ? AND tipo_movimiento = 'INGRESO'", Integer.class, a)).isEqualTo(1);   // el original sigue
        invariantes();

        assertThatThrownBy(() -> inventarioInsumoService.anularIngreso(b, "x")).isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("ya se usó");
        assertThatThrownBy(() -> inventarioInsumoService.anularIngreso(loteInicial(), "x")).isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("INICIAL");
        assertThatThrownBy(() -> inventarioInsumoService.anularIngreso(a, "otra vez")).isInstanceOf(ConflictoNegocioException.class);   // ya anulado: disponible 0 != inicial 5
        invariantes();
    }

    @Test
    void elEstadoDeCadaLoteSeCalculaVigentePorVencerVencidoOAgotado() {
        LocalDate hoy = LocalDate.now();
        UUID vigente = ingreso("1", hoy.plusDays(60));
        UUID porVencer = ingreso("1", hoy.plusDays(5));
        UUID vencido = ingreso("1", hoy.minusDays(3));
        UUID agotado = ingreso("1", hoy.plusDays(60));
        salidaService.registrar(insumo, true, salida("1", MotivoSalida.OTRA, agotado));

        List<LoteInsumoResponse> lotes = loteConsulta.listar(insumo, false);
        java.util.function.Function<UUID, LoteInsumoResponse> por = id -> lotes.stream().filter(l -> l.id().equals(id)).findFirst().orElseThrow();
        assertThat(por.apply(vigente).estado()).isEqualTo("VIGENTE");
        assertThat(por.apply(porVencer).estado()).isEqualTo("POR_VENCER");
        assertThat(por.apply(porVencer).diasParaVencer()).isBetween(4L, 5L);
        assertThat(por.apply(vencido).estado()).isEqualTo("VENCIDO");
        assertThat(por.apply(agotado).estado()).isEqualTo("AGOTADO");
        assertThat(por.apply(loteInicial()).estado()).isEqualTo("VIGENTE");            // sin vencimiento nunca vence
        assertThat(loteConsulta.listar(insumo, true)).extracting(LoteInsumoResponse::id).doesNotContain(agotado);
    }
}
