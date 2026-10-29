package com.planta.acopio.integration;

import com.planta.acopio.dto.KardexResponse;
import com.planta.acopio.dto.MovimientoKardexResponse;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.MovimientoInsumo;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.TipoMovimientoInsumo;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.entity.LoteInsumo;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.LoteInsumoRepository;
import com.planta.acopio.repository.MovimientoInsumoRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.KardexService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kardex de insumos contra PostgreSQL real:
 *  - orden estable (V40): movimientos con el MISMO creado_en salen en el orden en que se escribieron y su saldo acumulado
 *    sigue esa secuencia (antes se ordenaban por el UUID aleatorio);
 *  - zona horaria: la BD guarda creado_en en UTC (hibernate.jdbc.time_zone=UTC) y la aplicacion trabaja en hora local.
 *    Un movimiento de las 23:30 hora de Lima cae al dia SIGUIENTE en UTC: el filtro por dia del kardex debe seguir
 *    el dia que ve el usuario, no el dia UTC.
 * La zona por defecto de la JVM se fija en America/Lima durante la clase para que la prueba no dependa de la maquina.
 */
@SpringBootTest
class KardexOrdenYZonaHorariaIntegrationTest {

    private static final TimeZone ORIGINAL = TimeZone.getDefault();

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    @BeforeAll
    static void zonaDeLima() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Lima"));
    }

    @AfterAll
    static void restaurarZona() {
        TimeZone.setDefault(ORIGINAL);
    }

    @Autowired private KardexService kardexService;
    @Autowired private InsumoRepository insumoRepository;
    @Autowired private MovimientoInsumoRepository movimientoRepository;
    @Autowired private LoteInsumoRepository loteRepository;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private JdbcTemplate jdbc;

    private Insumo insumo;
    private LoteInsumo lote;
    private Usuario usuario;

    @BeforeEach
    void seed() {
        String s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("KARDEX_ORDEN_" + s);
        rol = rolRepository.save(rol);
        Usuario u = new Usuario();
        u.setNombre("Orden Test " + s);
        u.setEmail("orden-" + s + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        usuario = usuarioRepository.save(u);
        Insumo i = new Insumo();
        i.setNombre("Azucar orden " + s);
        i.setUnidadMedida("kg");
        insumo = insumoRepository.save(i);
        LoteInsumo l = new LoteInsumo();
        l.setInsumo(insumo);
        l.setCodigo("INICIAL");
        l.setFechaIngreso(LocalDate.of(2026, 10, 1));
        l.setCantidadInicial(BigDecimal.ZERO);
        l.setCantidadDisponible(BigDecimal.ZERO);
        l.setOrigen(LoteInsumo.Origen.INICIAL);
        lote = loteRepository.save(l);
    }

    private MovimientoInsumo ajuste(String cantidad, LocalDateTime creadoEn) {
        MovimientoInsumo m = new MovimientoInsumo();
        m.setInsumo(insumo);
        m.setLoteInsumo(lote);
        m.setTipoMovimiento(TipoMovimientoInsumo.AJUSTE);
        m.setCantidad(new BigDecimal(cantidad));
        m.setUsuario(usuario);
        m.setMotivo("prueba");
        m.setCreadoEn(creadoEn);
        return movimientoRepository.saveAndFlush(m);
    }

    @Test
    void movimientosConElMismoInstanteSalenEnElOrdenEnQueSeEscribieron() {
        LocalDateTime instante = LocalDateTime.of(2026, 10, 20, 15, 0, 0);
        String[] cantidades = {"5", "-3", "7", "-1", "4", "-2", "9", "-6", "8", "-4", "3", "-5"};
        List<BigDecimal> saldoEsperado = new ArrayList<>();
        BigDecimal acumulado = BigDecimal.ZERO;
        for (String c : cantidades) {
            ajuste(c, instante);
            acumulado = acumulado.add(new BigDecimal(c));
            saldoEsperado.add(acumulado);
        }

        KardexResponse k = kardexService.insumo(insumo.getId(), null, null, PageRequest.of(0, 50));
        List<MovimientoKardexResponse> filas = k.movimientos().contenido();

        assertThat(filas).hasSize(cantidades.length);
        // Entrada o salida segun el signo, en el orden de escritura.
        for (int i = 0; i < cantidades.length; i++) {
            BigDecimal cantidad = new BigDecimal(cantidades[i]);
            BigDecimal mostrada = cantidad.signum() > 0 ? filas.get(i).entrada() : filas.get(i).salida();
            assertThat(mostrada).as("fila %d", i).isEqualByComparingTo(cantidad.abs());
        }
        assertThat(filas).extracting(MovimientoKardexResponse::saldo)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactlyElementsOf(saldoEsperado);
        assertThat(k.saldoFinal()).isEqualByComparingTo(acumulado);
        List<Long> ordenes = jdbc.queryForList("SELECT orden FROM movimiento_insumo WHERE insumo_id = ? ORDER BY orden", Long.class, insumo.getId());
        assertThat(ordenes).isSorted().doesNotHaveDuplicates().hasSize(cantidades.length);
    }

    @Test
    void elFiltroPorDiaSigueElDiaLocalYNoElDiaUtc() {
        // 23:30 en Lima (UTC-5) = 04:30 UTC del dia siguiente.
        LocalDateTime local = LocalDateTime.of(2026, 10, 26, 23, 30, 0);
        ajuste("10", local);
        // Solo informativo (aparece en el informe): lo que quedo guardado en la base.
        String guardado = jdbc.queryForObject("SELECT creado_en::text FROM movimiento_insumo WHERE insumo_id = ?", String.class, insumo.getId());
        System.out.println("[ZONA] Hora local insertada: " + local + " -> guardada en la BD: " + guardado);

        KardexResponse dia26 = kardexService.insumo(insumo.getId(), LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 26), PageRequest.of(0, 50));
        KardexResponse dia27 = kardexService.insumo(insumo.getId(), LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 27), PageRequest.of(0, 50));

        // Lo que el usuario ve: el movimiento de las 23:30 del 26 pertenece al 26.
        assertThat(dia26.movimientos().contenido()).as("dia 26 (local)").hasSize(1);
        assertThat(dia26.movimientos().contenido().get(0).fecha()).isEqualTo(local);
        assertThat(dia26.totalEntradas()).isEqualByComparingTo("10");
        assertThat(dia27.movimientos().contenido()).as("dia 27 (local)").isEmpty();
        assertThat(dia27.saldoInicial()).as("saldo inicial del dia 27").isEqualByComparingTo("10");
    }
}
