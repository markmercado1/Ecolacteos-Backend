package com.planta.acopio.integration;

import com.planta.acopio.dto.DetalleSalidaRequest;
import com.planta.acopio.dto.DisponibilidadProduccionResponse;
import com.planta.acopio.dto.FinalizarLoteRequest;
import com.planta.acopio.dto.IngresoInsumoRequest;
import com.planta.acopio.dto.InsumoRequest;
import com.planta.acopio.dto.InsumoUsadoResponse;
import com.planta.acopio.dto.LoteProduccionResponse;
import com.planta.acopio.dto.PlanificarLoteRequest;
import com.planta.acopio.dto.RecepcionPlantaRequest;
import com.planta.acopio.dto.RecetaInsumoRequest;
import com.planta.acopio.dto.RecetaRequest;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.ConciliacionService;
import com.planta.acopio.service.InsumoService;
import com.planta.acopio.service.InventarioInsumoService;
import com.planta.acopio.service.LoteProduccionService;
import com.planta.acopio.service.RecetaService;
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
 * Produccion con lotes de insumo (V43) de punta a punta contra PostgreSQL real: el consumo sale de lotes en FEFO (un movimiento por lote
 * usado), un lote vencido nunca se consume ni cuenta como stock disponible, y el lote de produccion informa de que lotes de insumo
 * salio cada insumo (trazabilidad hacia atras).
 */
@SpringBootTest
class ProduccionFefoLotesIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    @Autowired private InsumoService insumoService;
    @Autowired private InventarioInsumoService inventarioInsumoService;
    @Autowired private RecetaService recetaService;
    @Autowired private LoteProduccionService loteProduccionService;
    @Autowired private ConciliacionService conciliacionService;
    @Autowired private com.planta.acopio.service.TrazabilidadService trazabilidad;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private com.planta.acopio.controller.PlanLotesProduccionController planLotes;
    @Autowired private JdbcTemplate jdbc;

    private String s;
    private Unidad unidad;
    private Producto producto;
    private Presentacion vaso;
    private UUID azucar;

    @BeforeEach
    void seed() {
        s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        Rol rol = new Rol();
        rol.setNombre("PROD_FEFO_" + s);
        rol = rolRepository.save(rol);
        Usuario u = new Usuario();
        u.setNombre("Prod Fefo " + s);
        u.setEmail("prod-fefo-" + s + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        Usuario usuario = usuarioRepository.save(u);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(usuario.getEmail(), null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        Unidad un = new Unidad();
        un.setPlaca("FEF-" + s);
        un.setResponsable(usuario);
        unidad = unidadRepository.save(un);
        Familia f = new Familia();
        f.setNombre("Familia Fefo " + s);
        Producto p = new Producto();
        p.setNombre("Yogurt Fefo " + s);
        p.setFamilia(familiaRepository.save(f));
        producto = productoRepository.save(p);
        Presentacion pr = new Presentacion();
        pr.setNombre("Vaso");
        pr.setProducto(producto);
        pr.setUnidadConteo("vaso");
        vaso = presentacionRepository.save(pr);
        azucar = insumoService.crear(new InsumoRequest("Azucar fefo " + s, "kg", new BigDecimal("10"))).id();   // lote INICIAL: 10, sin vencimiento
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private UUID ingreso(String cantidad, LocalDate vence) {
        inventarioInsumoService.registrarIngreso(azucar, new IngresoInsumoRequest(new BigDecimal(cantidad), null, null));
        UUID lote = jdbc.queryForObject("SELECT l.id FROM movimiento_insumo m JOIN lote_insumo l ON l.id = m.lote_insumo_id WHERE m.insumo_id = ? AND m.cantidad > 0 ORDER BY m.orden DESC LIMIT 1", UUID.class, azucar);
        jdbc.update("UPDATE lote_insumo SET fecha_vencimiento = ? WHERE id = ?", vence, lote);
        return lote;
    }

    private BigDecimal disponible(UUID lote) {
        return jdbc.queryForObject("SELECT cantidad_disponible FROM lote_insumo WHERE id = ?", BigDecimal.class, lote);
    }

    /** Receta que consume `porBase` kg de azucar por cada 100 L de leche; se planifica con `litros`. */
    private LoteProduccionResponse planificar(String porBase, String litros) {
        var receta = recetaService.crear(new RecetaRequest("Receta " + s + porBase, producto.getId(), vaso.getId(), new BigDecimal("100"), BigDecimal.ONE,
                List.of(new RecetaInsumoRequest(azucar, new BigDecimal(porBase)))));
        return loteProduccionService.planificar(new PlanificarLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), receta.id(), new BigDecimal(litros)));
    }

    private void invariantes() {
        BigDecimal stock = jdbc.queryForObject("SELECT stock FROM insumo WHERE id = ?", BigDecimal.class, azucar);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(cantidad_disponible), 0) FROM lote_insumo WHERE insumo_id = ?", BigDecimal.class, azucar)).isEqualByComparingTo(stock);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_insumo WHERE insumo_id = ?", BigDecimal.class, azucar)).isEqualByComparingTo(stock);
    }

    @Test
    void laProduccionConsumeEnFefoDeVariosLotesYElLoteDeProduccionInformaDeCualesSalio() {
        conciliacionService.registrarYConciliar(new RecepcionPlantaRequest(LocalDate.now(), null, unidad.getId(), new BigDecimal("3000"), new BigDecimal("3000"), List.of()));
        UUID vencePronto = ingreso("3", LocalDate.now().plusDays(10));    // vence antes: se usa primero
        UUID venceLuego = ingreso("4", LocalDate.now().plusDays(60));
        // 1000 L con 1 kg por cada 100 L = 10 kg: vencePronto(3) + venceLuego(4) + INICIAL(3 de 10, sin vencimiento, al final).
        LoteProduccionResponse plan = planificar("1", "1000");

        loteProduccionService.iniciar(plan.id());

        assertThat(disponible(vencePronto)).isEqualByComparingTo("0");
        assertThat(disponible(venceLuego)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT cantidad_disponible FROM lote_insumo WHERE insumo_id = ? AND codigo = 'INICIAL'", BigDecimal.class, azucar)).isEqualByComparingTo("7");
        assertThat(jdbc.queryForList("SELECT -m.cantidad FROM movimiento_insumo m WHERE m.lote_produccion_id = ? AND m.tipo_movimiento = 'SALIDA_PRODUCCION' ORDER BY m.orden",
                BigDecimal.class, plan.id())).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("3"), new BigDecimal("4"), new BigDecimal("3"));
        invariantes();

        // Trazabilidad hacia atras: el lote de produccion sabe de que lotes de insumo salio.
        LoteProduccionResponse detalle = loteProduccionService.obtener(plan.id());
        InsumoUsadoResponse usado = detalle.insumos().get(0);
        assertThat(usado.lotes()).hasSize(3);
        assertThat(usado.lotes()).extracting(InsumoUsadoResponse.LoteUsado::loteId).contains(vencePronto, venceLuego);
        assertThat(usado.lotes().stream().map(InsumoUsadoResponse.LoteUsado::cantidad).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("10");
        loteProduccionService.finalizar(plan.id(), new FinalizarLoteRequest(List.of(new DetalleSalidaRequest(vaso.getId(), 9)), null));

        // Trazabilidad hacia adelante: ¿en que lotes de produccion se uso el lote de insumo que vence pronto? Y hacia atras desde la produccion.
        String codigoPronto = jdbc.queryForObject("SELECT codigo FROM lote_insumo WHERE id = ?", String.class, vencePronto);
        var delLote = trazabilidad.buscar(codigoPronto).lotesInsumo().get(0);
        assertThat(delLote.producciones()).hasSize(1);
        assertThat(delLote.producciones().get(0).loteProduccionId()).isEqualTo(plan.id());
        assertThat(delLote.producciones().get(0).cantidad()).isEqualByComparingTo("3");
        var deLaProduccion = trazabilidad.buscar(plan.id().toString()).lotesProduccion();
        assertThat(deLaProduccion).hasSize(1);
        assertThat(deLaProduccion.get(0).insumos().get(0).lotes()).extracting(l -> l.loteId()).contains(vencePronto, venceLuego);
    }

    @Test
    void unLoteVencidoNoSeConsumeNiCuentaComoStockDisponible() {
        conciliacionService.registrarYConciliar(new RecepcionPlantaRequest(LocalDate.now(), null, unidad.getId(), new BigDecimal("3000"), new BigDecimal("3000"), List.of()));
        UUID vencido = ingreso("6", LocalDate.now().minusDays(1));     // stock total 16, pero solo 10 consumibles
        LoteProduccionResponse plan = planificar("1.5", "1000");      // necesita 15 kg

        DisponibilidadProduccionResponse disp = loteProduccionService.disponibilidad(plan.id());
        assertThat(disp.insumos().get(0).disponible()).isEqualByComparingTo("10");     // no cuenta lo vencido
        assertThat(disp.insumos().get(0).suficiente()).isFalse();

        assertThatThrownBy(() -> loteProduccionService.iniciar(plan.id())).isInstanceOf(ValidacionNegocioException.class);
        assertThat(disponible(vencido)).isEqualByComparingTo("6");     // el vencido no se toco
        assertThat(jdbc.queryForObject("SELECT stock FROM insumo WHERE id = ?", BigDecimal.class, azucar)).isEqualByComparingTo("16");
        invariantes();

        // Una produccion que cabe en lo consumible (8 kg) si sale, y deja el lote vencido intacto.
        LoteProduccionResponse ok = planificar("0.8", "1000");
        loteProduccionService.iniciar(ok.id());
        assertThat(disponible(vencido)).isEqualByComparingTo("6");
        assertThat(jdbc.queryForObject("SELECT stock FROM insumo WHERE id = ?", BigDecimal.class, azucar)).isEqualByComparingTo("8");
        invariantes();
    }

    @Test
    void sePuedenElegirALosLotesDeInsumoAlIniciarYElPlanMuestraLaPropuestaFefo() {
        conciliacionService.registrarYConciliar(new RecepcionPlantaRequest(LocalDate.now(), null, unidad.getId(), new BigDecimal("3000"), new BigDecimal("3000"), List.of()));
        UUID a = ingreso("3", LocalDate.now().plusDays(10));
        UUID b = ingreso("4", LocalDate.now().plusDays(60));
        UUID vencido = ingreso("2", LocalDate.now().minusDays(1));
        UUID inicial = jdbc.queryForObject("SELECT id FROM lote_insumo WHERE insumo_id = ? AND codigo = 'INICIAL'", UUID.class, azucar);
        LoteProduccionResponse plan = planificar("1", "1000");      // necesita 10 kg

        // Plan: propone FEFO (a 3, b 4, INICIAL 3) y ofrece como elegibles solo los lotes no vencidos.
        var propuesto = planLotes.planLotes(plan.id()).getBody().insumos().get(0);
        assertThat(propuesto.requerido()).isEqualByComparingTo("10");
        assertThat(propuesto.propuesta()).extracting(com.planta.acopio.dto.PlanLotesProduccionResponse.Parte::loteId).containsExactly(a, b, inicial);
        assertThat(propuesto.disponibles()).extracting(com.planta.acopio.dto.PlanLotesProduccionResponse.Disponible::loteId).containsExactlyInAnyOrder(a, b, inicial).doesNotContain(vencido);
        assertThat(disponible(a)).isEqualByComparingTo("3");         // consultar el plan no toca nada

        var elegir = (java.util.function.Function<List<Object[]>, com.planta.acopio.dto.IniciarLoteRequest>) partes -> new com.planta.acopio.dto.IniciarLoteRequest(List.of(
                new com.planta.acopio.dto.IniciarLoteRequest.InsumoLotes(azucar, partes.stream()
                        .map(p -> new com.planta.acopio.dto.IniciarLoteRequest.Parte((UUID) p[0], new BigDecimal((String) p[1]))).toList())));

        // Elecciones invalidas: no suma lo necesario, lote vencido, repetido. Nada se consume.
        assertThatThrownBy(() -> loteProduccionService.iniciar(plan.id(), elegir.apply(List.<Object[]>of(new Object[]{inicial, "9"}))))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("suman 9");
        assertThatThrownBy(() -> loteProduccionService.iniciar(plan.id(), elegir.apply(List.<Object[]>of(new Object[]{vencido, "2"}, new Object[]{inicial, "8"}))))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("vencido");
        assertThatThrownBy(() -> loteProduccionService.iniciar(plan.id(), elegir.apply(List.<Object[]>of(new Object[]{a, "1"}, new Object[]{a, "9"}))))
                .isInstanceOf(ValidacionNegocioException.class);
        assertThat(disponible(a)).isEqualByComparingTo("3");
        assertThat(disponible(vencido)).isEqualByComparingTo("2");
        invariantes();

        // Eleccion valida: el INICIAL (6) y b (4) en lugar del orden FEFO.
        loteProduccionService.iniciar(plan.id(), elegir.apply(List.<Object[]>of(new Object[]{inicial, "6"}, new Object[]{b, "4"})));

        assertThat(disponible(a)).isEqualByComparingTo("3");         // a no se uso aunque vencia antes
        assertThat(disponible(b)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT cantidad_disponible FROM lote_insumo WHERE id = ?", BigDecimal.class, inicial)).isEqualByComparingTo("4");
        assertThat(jdbc.queryForList("SELECT li.id FROM movimiento_insumo m JOIN lote_insumo li ON li.id = m.lote_insumo_id WHERE m.lote_produccion_id = ? "
                + "AND m.tipo_movimiento = 'SALIDA_PRODUCCION' ORDER BY m.orden", UUID.class, plan.id())).containsExactlyInAnyOrder(inicial, b);
        invariantes();
        // El detalle del lote de produccion informa de los lotes que se eligieron.
        assertThat(loteProduccionService.obtener(plan.id()).insumos().get(0).lotes()).extracting(l -> l.loteId()).containsExactlyInAnyOrder(inicial, b);
    }
}
