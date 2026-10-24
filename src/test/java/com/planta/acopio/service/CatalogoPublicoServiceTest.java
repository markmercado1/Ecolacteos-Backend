package com.planta.acopio.service;

import com.planta.acopio.dto.CatalogoPublicoResponse;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.PrecioQueso;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.TipoClienteVenta;
import com.planta.acopio.entity.UnidadContenido;
import com.planta.acopio.repository.PrecioQuesoRepository;
import com.planta.acopio.repository.PresentacionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Fase 3: catalogo publico. Reglas del usuario: solo productos con control de vencimiento, solo precio PUBLICO,
// disponibilidad por stock VENDIBLE (no fisico), estado y no cantidad.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogoPublicoServiceTest {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 11); // domingo; el reloj fijo es 10:00 Lima
    // Atencion todos los dias hasta las 18:00: la primera fecha de retiro posible es hoy.
    private static final ReservaParametros PARAMETROS = new ReservaParametros(3, 20, 2, 24,
            java.util.Set.of(1, 2, 3, 4, 5, 6, 7), java.time.LocalTime.of(18, 0), java.util.Set.of());

    @Mock private PresentacionRepository presentacionRepository;
    @Mock private PrecioQuesoRepository precioRepository;
    @Mock private InventarioService inventarioService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private ReservaCompromisos compromisos;
    @Mock private ReservaConfig reservaConfig;
    @Mock private ConfiguracionIgvService igvService;

    private final ReglaVencimiento regla = new ReglaVencimiento(
            Clock.fixed(HOY.atTime(10, 0).atZone(ZoneId.of("America/Lima")).toInstant(), ZoneId.of("America/Lima")));

    private final MutableClock reloj = new MutableClock();
    private CatalogoPublicoService service;
    private Producto yogurt;
    private Presentacion litro;
    private Presentacion medio;

    /** Reloj que se mueve a mano, para probar la vigencia de la cache. */
    private static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(1_000_000L);
        void avanzarSegundos(long s) { millis.addAndGet(s * 1000); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        @Override public long millis() { return millis.get(); }
    }

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(reservaConfig.actuales()).thenReturn(PARAMETROS);
        service = new CatalogoPublicoService(presentacionRepository, precioRepository, inventarioService, regla,
                compromisos, reservaConfig, transactionManager, igvService, 5, 30, reloj);

        Familia lacteos = new Familia();
        lacteos.setNombre("Yogurt");
        yogurt = new Producto();
        yogurt.setId(UUID.randomUUID());
        yogurt.setNombre("Yogurt de fresa");
        yogurt.setFamilia(lacteos);
        litro = presentacion(yogurt, "Botella 1 L", new BigDecimal("1"), UnidadContenido.L);
        medio = presentacion(yogurt, "Botella 500 ml", new BigDecimal("500"), UnidadContenido.ML);
        when(presentacionRepository.listarPublicables()).thenReturn(List.of(litro, medio));
        precios(precio(litro, "10.00", HOY.minusDays(30)), precio(medio, "6.00", HOY.minusDays(30)));
        vendible(litro, 50);
        vendible(medio, 50);
    }

    private Presentacion presentacion(Producto producto, String nombre, BigDecimal cantidad, UnidadContenido unidad) {
        Presentacion p = new Presentacion();
        p.setId(UUID.randomUUID());
        p.setNombre(nombre);
        p.setProducto(producto);
        p.setContenidoCantidad(cantidad);
        p.setContenidoUnidad(unidad);
        p.setUnidadConteo("botella");
        return p;
    }

    private PrecioQueso precio(Presentacion pr, String valor, LocalDate desde) {
        PrecioQueso p = new PrecioQueso();
        p.setPresentacion(pr);
        p.setModalidad(TipoClienteVenta.PUBLICO);
        p.setPrecio(new BigDecimal(valor));
        p.setVigenteDesde(desde);
        return p;
    }

    private void precios(PrecioQueso... filas) {
        when(precioRepository.findVigentesHasta(any(), eq(TipoClienteVenta.PUBLICO), eq(HOY))).thenReturn(List.of(filas));
    }

    /** Fisico alto a proposito: la disponibilidad debe salir del vendible, no del fisico. */
    private void vendible(Presentacion pr, int vendible) {
        List<Existencias.Lote> lotes = vendible == 0 ? List.of() : List.of(new Existencias.Lote(Existencias.Origen.LOTE, UUID.randomUUID(),
                UUID.randomUUID(), HOY.minusDays(5), HOY.plusDays(30), vendible, false));
        when(inventarioService.calcularExistencias(pr.getId()))
                .thenReturn(new Existencias(vendible + 500, 300, 200, vendible, lotes));
    }

    private CatalogoPublicoResponse.Presentacion de(CatalogoPublicoResponse r, Presentacion pr) {
        return r.productos().stream().flatMap(p -> p.presentaciones().stream())
                .filter(p -> p.id().equals(pr.getId())).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ Fase G: "Incluye IGV"

    @Test
    void incluyeIgvSoloEnLoQueElServicioDeIgvMarcaGravadoHoyYNuncaSinPrecio() {
        precios(precio(litro, "10.00", HOY.minusDays(30))); // medio queda "precio a confirmar"
        when(igvService.gravadasConTasa(any(), eq(HOY))).thenReturn(java.util.Set.of(litro.getId(), medio.getId()));

        var r = service.obtener();

        assertThat(de(r, litro).incluyeIgv()).isTrue();
        assertThat(de(r, medio).incluyeIgv()).isFalse(); // sin precio no hay importe que "incluya" nada
    }

    @Test
    void sinCalculoDeIgvNingunPrecioDiceIncluyeIgv() {
        var r = service.obtener(); // el mock devuelve un conjunto vacio, como el servicio con el calculo apagado
        assertThat(r.productos().get(0).presentaciones()).allMatch(p -> !p.incluyeIgv());
    }

    // ------------------------------------------------------------------ que se publica

    @Test
    void publicaProductoConSusPresentacionesPrecioYDatosBasicos() {
        var r = service.obtener();

        assertThat(r.productos()).hasSize(1);
        var p = r.productos().get(0);
        assertThat(p.nombre()).isEqualTo("Yogurt de fresa");
        assertThat(p.familia()).isEqualTo("Yogurt");
        assertThat(p.presentaciones()).extracting(x -> x.nombre()).containsExactly("Botella 1 L", "Botella 500 ml");
        var l = de(r, litro);
        assertThat(l.precio()).isEqualByComparingTo("10.00");
        assertThat(l.contenidoUnidad()).isEqualTo("L");
        assertThat(l.unidadConteo()).isEqualTo("botella");
    }

    @Test
    void consultaElPrecioDeModalidadPublicoConLaFechaDeHoyEnLima() {
        service.obtener();

        verify(precioRepository).findVigentesHasta(any(), eq(TipoClienteVenta.PUBLICO), eq(HOY));
    }

    @Test
    void unaPresentacionSinPrecioPublicoSeListaComoPrecioAConfirmarYSePuedeReservar() {
        precios(precio(litro, "10.00", HOY.minusDays(30))); // medio no tiene precio PUBLICO vigente

        var r = service.obtener();

        assertThat(de(r, litro).precio()).isEqualByComparingTo("10.00");
        assertThat(de(r, litro).precioAConfirmar()).isFalse();
        assertThat(de(r, medio)).as("sin precio PUBLICO se lista igual").isNotNull();
        assertThat(de(r, medio).precio()).as("nunca se inventa un precio").isNull();
        assertThat(de(r, medio).precioAConfirmar()).isTrue();
        assertThat(de(r, medio).disponibilidad()).as("la disponibilidad no depende del precio").isEqualTo(DisponibilidadPublica.DISPONIBLE);
    }

    @Test
    void sinNingunPrecioPublicoTodasSeListanAConfirmar() {
        precios();

        var r = service.obtener();

        assertThat(r.productos()).hasSize(1);
        assertThat(r.productos().get(0).presentaciones()).allSatisfy(p -> {
            assertThat(p.precio()).isNull();
            assertThat(p.precioAConfirmar()).isTrue();
        });
    }

    @Test
    void conVariosPreciosVigentesElegidoEsElDeVigenteDesdeMasReciente() {
        precios(precio(litro, "8.00", HOY.minusDays(90)), precio(litro, "10.50", HOY.minusDays(2)),
                precio(litro, "9.00", HOY.minusDays(40)), precio(medio, "6.00", HOY));

        var r = service.obtener();

        assertThat(de(r, litro).precio()).isEqualByComparingTo("10.50");
        assertThat(de(r, medio).precio()).isEqualByComparingTo("6.00"); // vigente desde hoy: ya vale
    }

    @Test
    void sinCandidatasDevuelveUnCatalogoVacioSinConsultarPreciosNiExistencias() {
        when(presentacionRepository.listarPublicables()).thenReturn(List.of());

        assertThat(service.obtener().productos()).isEmpty();

        verify(precioRepository, never()).findVigentesHasta(any(), any(), any());
        verify(inventarioService, never()).calcularExistencias(any());
    }

    // ------------------------------------------------------------------ disponibilidad

    @Test
    void laDisponibilidadSaleDelStockVendibleNuncaDelFisico() {
        vendible(litro, 0);   // fisico 500: todo vencido o sin trazabilidad
        vendible(medio, 3);

        var r = service.obtener();

        assertThat(de(r, litro).disponibilidad()).isEqualTo(DisponibilidadPublica.AGOTADO);
        assertThat(de(r, medio).disponibilidad()).isEqualTo(DisponibilidadPublica.POCAS_UNIDADES);
    }

    @Test
    void laCantidadSePublicaConPocasUnidadesYNoEnAgotado() {
        vendible(litro, 0);
        vendible(medio, 3);

        var r = service.obtener();

        assertThat(de(r, medio).unidadesDisponibles()).as("pocas: se informa cuantas quedan").isEqualTo(3);
        assertThat(de(r, litro).unidadesDisponibles()).as("agotado: sin cantidad").isNull();
    }

    @Test
    void laCantidadNoSePublicaPorEncimaDelUmbralYElLimiteEsInclusivo() {
        vendible(litro, 6);
        vendible(medio, 5);

        var r = service.obtener();

        assertThat(de(r, medio).unidadesDisponibles()).as("5 = umbral inclusive").isEqualTo(5);
        assertThat(de(r, litro).unidadesDisponibles()).as("por encima del umbral no se publica la cantidad").isNull();
    }

    @Test
    void losLimitesDelUmbralSonInclusivos() {
        vendible(litro, 5);
        vendible(medio, 6);

        var r = service.obtener();

        assertThat(de(r, litro).disponibilidad()).isEqualTo(DisponibilidadPublica.POCAS_UNIDADES);
        assertThat(de(r, medio).disponibilidad()).isEqualTo(DisponibilidadPublica.DISPONIBLE);
    }

    @Test
    void unaPresentacionAgotadaSigueListadaComoAgotada() {
        vendible(litro, 0);

        assertThat(de(service.obtener(), litro)).isNotNull();
    }

    @Test
    void existenciasInconsistentesSePublicanComoAgotadoSinTumbarElRestoDelCatalogo() {
        when(inventarioService.calcularExistencias(litro.getId())).thenThrow(new IllegalStateException("no cuadra con el libro"));

        var r = service.obtener();

        assertThat(de(r, litro).disponibilidad()).isEqualTo(DisponibilidadPublica.AGOTADO);
        assertThat(de(r, medio).disponibilidad()).isEqualTo(DisponibilidadPublica.DISPONIBLE);
    }

    // ------------------------------------------------------------------ fotos

    @Test
    void laFotoDeLaPresentacionTienePrioridadYSinEllaSeUsaLaDelProducto() {
        yogurt.setFotoArchivo("producto.jpg");
        litro.setFotoArchivo("litro.jpg");

        var r = service.obtener();

        assertThat(r.productos().get(0).fotoUrl()).isEqualTo(FotoCatalogoService.urlDe("producto.jpg"));
        assertThat(de(r, litro).fotoUrl()).isEqualTo(FotoCatalogoService.urlDe("litro.jpg"));
        assertThat(de(r, medio).fotoUrl()).isEqualTo(FotoCatalogoService.urlDe("producto.jpg"));
    }

    @Test
    void sinNingunaFotoLaUrlEsNull() {
        var r = service.obtener();

        assertThat(r.productos().get(0).fotoUrl()).isNull();
        assertThat(de(r, litro).fotoUrl()).isNull();
    }

    // ------------------------------------------------------------------ cache

    @Test
    void elResultadoSeConservaMientrasDuraLaCacheYSeRecalculaDespues() {
        service.obtener();
        service.obtener();
        reloj.avanzarSegundos(29);
        service.obtener();
        verify(presentacionRepository, times(1)).listarPublicables();

        reloj.avanzarSegundos(2); // 31 s desde la primera
        vendible(litro, 0);
        var r = service.obtener();

        verify(presentacionRepository, times(2)).listarPublicables();
        assertThat(de(r, litro).disponibilidad()).as("tras vencer la cache se ve el stock nuevo").isEqualTo(DisponibilidadPublica.AGOTADO);
    }

    @Test
    void conCacheDeCeroSegundosSiempreRecalcula() {
        var sinCache = new CatalogoPublicoService(presentacionRepository, precioRepository, inventarioService, regla,
                compromisos, reservaConfig, transactionManager, igvService, 5, 0, reloj);

        sinCache.obtener();
        sinCache.obtener();

        verify(presentacionRepository, times(2)).listarPublicables();
    }

    // ------------------------------------------------------------------ Fase 4: disponibilidad neta de reservas

    private Existencias conLotes(int sinTrazabilidad, Existencias.Lote... lotes) {
        int vendible = java.util.Arrays.stream(lotes).filter(l -> !l.vencido()).mapToInt(Existencias.Lote::saldo).sum();
        int vencido = java.util.Arrays.stream(lotes).filter(Existencias.Lote::vencido).mapToInt(Existencias.Lote::saldo).sum();
        return new Existencias(vendible + vencido + sinTrazabilidad, sinTrazabilidad, vencido, vendible, List.of(lotes));
    }

    private Existencias.Lote lote(LocalDate vence, int saldo) {
        return new Existencias.Lote(Existencias.Origen.LOTE, UUID.randomUUID(), UUID.randomUUID(), HOY.minusDays(5), vence, saldo, vence.isBefore(HOY));
    }

    private void reservas(Presentacion pr, int cantidad, LocalDate retiro) {
        when(compromisos.compromisosDe(any(java.util.Collection.class))).thenReturn(java.util.Map.of(pr.getId(),
                List.of(new CoberturaReservas.DemandaReserva(UUID.randomUUID(), HOY.atTime(9, 0), retiro, cantidad))));
    }

    @Test
    void laDisponibilidadDescuentaLasUnidadesComprometidasPorReservas() {
        when(inventarioService.calcularExistencias(litro.getId())).thenReturn(conLotes(0, lote(HOY.plusDays(20), 12)));
        reservas(litro, 10, HOY.plusDays(2)); // quedan 2 libres

        var r = service.obtener();

        assertThat(de(r, litro).disponibilidad()).as("12 vendibles - 10 reservadas = 2").isEqualTo(DisponibilidadPublica.POCAS_UNIDADES);
        assertThat(de(r, litro).unidadesDisponibles()).as("la cantidad ya descuenta las reservas").isEqualTo(2);
    }

    @Test
    void siLasReservasComprometenTodoLoVendibleSeVeAgotado() {
        when(inventarioService.calcularExistencias(litro.getId())).thenReturn(conLotes(0, lote(HOY.plusDays(20), 12)));
        reservas(litro, 12, HOY.plusDays(2));

        assertThat(de(service.obtener(), litro).disponibilidad()).isEqualTo(DisponibilidadPublica.AGOTADO);
    }

    @Test
    void unaReservaVencidaOCanceladaNoDescuentaPorqueNoApareceEnLosCompromisos() {
        when(inventarioService.calcularExistencias(litro.getId())).thenReturn(conLotes(0, lote(HOY.plusDays(20), 12)));
        when(compromisos.compromisosDe(any(java.util.Collection.class))).thenReturn(java.util.Map.of()); // ninguna vigente

        assertThat(de(service.obtener(), litro).disponibilidad()).isEqualTo(DisponibilidadPublica.DISPONIBLE);
    }

    @Test
    void unLoteQueVenceAntesDeLaFechaDeReferenciaNoCuentaComoDisponible() {
        // Hoy (domingo, atencion todos los dias): la primera fecha de retiro es hoy; un lote que vence hoy aun cuenta.
        when(inventarioService.calcularExistencias(litro.getId())).thenReturn(conLotes(0, lote(HOY, 30)));
        assertThat(de(service.obtener(), litro).disponibilidad()).isEqualTo(DisponibilidadPublica.DISPONIBLE);

        // Un lote ya vencido no cuenta (el desglose lo marca vencido y no es vendible).
        reloj.avanzarSegundos(31);
        when(inventarioService.calcularExistencias(litro.getId())).thenReturn(conLotes(0, lote(HOY.minusDays(1), 30)));
        assertThat(de(service.obtener(), litro).disponibilidad()).isEqualTo(DisponibilidadPublica.AGOTADO);
    }

    @Test
    void laFechaDeReferenciaEsLaPrimeraFechaDeRetiroPosibleSegunLaConfiguracion() {
        // Atencion SOLO los miercoles (3) y el plazo es de 3 dias: desde el domingo 11, el primer dia valido es el miercoles 14.
        when(reservaConfig.actuales()).thenReturn(new ReservaParametros(3, 20, 2, 24, java.util.Set.of(3),
                java.time.LocalTime.of(18, 0), java.util.Set.of()));
        // Este lote vence el martes 13: para retirar el miercoles no sirve.
        when(inventarioService.calcularExistencias(litro.getId())).thenReturn(conLotes(0, lote(HOY.plusDays(2), 30)));

        assertThat(de(service.obtener(), litro).disponibilidad()).isEqualTo(DisponibilidadPublica.AGOTADO);
    }
}
