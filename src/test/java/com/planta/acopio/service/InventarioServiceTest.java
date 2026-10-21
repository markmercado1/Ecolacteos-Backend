package com.planta.acopio.service;

import com.planta.acopio.entity.AjusteInventario;
import com.planta.acopio.entity.AsignacionLote;
import com.planta.acopio.entity.IncidenciaCobertura;
import com.planta.acopio.entity.LoteProduccion;
import com.planta.acopio.entity.LoteProduccionDetalle;
import com.planta.acopio.entity.MovimientoInventario;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.TipoMovimientoInventario;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.VentaDetalle;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.AsignacionLoteRepository;
import com.planta.acopio.repository.IncidenciaCoberturaRepository;
import com.planta.acopio.repository.LoteProduccionDetalleRepository;
import com.planta.acopio.repository.MovimientoInventarioRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.RegularizacionTrazabilidadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Inventario (V7__inventario_precio_queso.sql). Fase 3 (Produccion con varias presentaciones de salida e
// Inventario por presentacion): el stock nunca es una tabla mutable, siempre SUM(cantidad) del libro mayor
// de movimientos, ahora agrupado por PRESENTACION (no por producto, ver InventarioService).
@ExtendWith(MockitoExtension.class)
class InventarioServiceTest {

    @Mock private MovimientoInventarioRepository movimientoRepository;
    @Mock private PresentacionRepository presentacionRepository;
    @Mock private AsignacionLoteRepository asignacionLoteRepository;
    @Mock private LoteProduccionDetalleRepository detalleRepository;
    @Mock private RegularizacionTrazabilidadRepository regularizacionRepository;
    @Mock private ReservaCompromisos reservaCompromisos;
    @Mock private IncidenciaCoberturaRepository incidenciaRepository;

    // "Hoy" fijo: 2026-10-11 en America/Lima (el dia de vencimiento es inclusive).
    private static final LocalDate HOY = LocalDate.of(2026, 10, 11);
    private final ReglaVencimiento regla = new ReglaVencimiento(
            Clock.fixed(HOY.atTime(10, 0).atZone(ZoneId.of("America/Lima")).toInstant(), ZoneId.of("America/Lima")));

    private InventarioService service;
    private final UUID productoId = UUID.randomUUID();
    private final UUID presentacionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new InventarioService(movimientoRepository, presentacionRepository, asignacionLoteRepository,
                detalleRepository, regularizacionRepository, regla, reservaCompromisos, incidenciaRepository);
    }

    private Producto quesoFresco() {
        Producto t = new Producto();
        t.setId(productoId);
        t.setNombre("Queso Fresco");
        return t;
    }

    private Presentacion botella1L() {
        Presentacion p = new Presentacion();
        p.setId(presentacionId);
        p.setNombre("Botella 1 L");
        p.setProducto(quesoFresco());
        return p;
    }

    @Test
    void registrarEntradaProduccionUsaLaCantidadObtenidaDelDetalleComoCantidad() {
        Presentacion presentacion = botella1L();
        LoteProduccion lote = new LoteProduccion();
        lote.setId(UUID.randomUUID());
        lote.setProducto(presentacion.getProducto());
        LoteProduccionDetalle detalle = new LoteProduccionDetalle();
        detalle.setId(UUID.randomUUID());
        detalle.setLote(lote);
        detalle.setPresentacion(presentacion);
        detalle.setCantidadObtenida(90);

        service.registrarEntradaProduccion(detalle);

        ArgumentCaptor<MovimientoInventario> captor = ArgumentCaptor.forClass(MovimientoInventario.class);
        verify(movimientoRepository).save(captor.capture());
        MovimientoInventario movimiento = captor.getValue();
        assertThat(movimiento.getTipoMovimiento()).isEqualTo(TipoMovimientoInventario.ENTRADA_PRODUCCION);
        assertThat(movimiento.getCantidad()).isEqualTo(90);
        assertThat(movimiento.getPresentacion()).isSameAs(presentacion);
        assertThat(movimiento.getLoteProduccionDetalle()).isSameAs(detalle);
        assertThat(movimiento.getVentaDetalle()).isNull();
    }

    @Test
    void obtenerStockDePresentacionDevuelveLaSumaDelLibroMayor() {
        when(movimientoRepository.sumarCantidadPorPresentacion(presentacionId)).thenReturn(130L);

        var stock = service.obtenerStockDePresentacion(presentacionId);

        assertThat(stock.presentacionId()).isEqualTo(presentacionId);
        assertThat(stock.unidadesDisponibles()).isEqualTo(130);
    }

    @Test
    void listarStockPaginadoMapeaLasFilasDeLaConsultaNativaAlDto() {
        UUID familiaId = UUID.randomUUID();
        Object[] fila = new Object[] {
                familiaId, "Quesos", productoId, "Queso Fresco", true,
                presentacionId, "Botella 1 L", "botella", true, 42, false
        };
        when(presentacionRepository.buscarFilasStock(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.<Object[]>of(fila), PageRequest.of(0, 20), 1));

        var resultado = service.listarStockPaginado(familiaId, "botella", PageRequest.of(0, 20));

        assertThat(resultado.totalElementos()).isEqualTo(1);
        var f = resultado.contenido().get(0);
        assertThat(f.familiaId()).isEqualTo(familiaId);
        assertThat(f.productoNombre()).isEqualTo("Queso Fresco");
        assertThat(f.presentacionNombre()).isEqualTo("Botella 1 L");
        assertThat(f.stock()).isEqualTo(42);
        assertThat(f.presentacionActivo()).isTrue();
        // Sin control de vencimiento, vendible = fisico y no se consulta ningun reparto por lote.
        assertThat(f.controlaVencimiento()).isFalse();
        assertThat(f.stockVendible()).isEqualTo(42);
        verify(asignacionLoteRepository, never()).listarExistenciasPorLote(any());
    }

    @Test
    void listarStockPaginadoCalculaElVendibleSoloParaLasFilasDeProductosControlados() {
        Object[] fila = new Object[] {
                UUID.randomUUID(), "Lacteos", productoId, "Yogurt", true, presentacionId, "Botella 1 L", "botella", true, 100, true
        };
        when(presentacionRepository.buscarFilasStock(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.<Object[]>of(fila), PageRequest.of(0, 20), 1));
        escenarioControlado(20, 100); // 20 sin trazabilidad + 10 vencidas + 5 que vencen hoy + 65 vigentes

        var f = service.listarStockPaginado(null, null, PageRequest.of(0, 20)).contenido().get(0);

        assertThat(f.stock()).isEqualTo(100);
        assertThat(f.controlaVencimiento()).isTrue();
        assertThat(f.stockVendible()).isEqualTo(70);
        // Sin reservas: nada comprometido y todo lo vendible esta libre.
        assertThat(f.stockComprometido()).isZero();
        assertThat(f.stockLibre()).isEqualTo(70);
    }

    @Test
    void listarStockPaginadoInformaLoComprometidoPorReservasYLoLibreParaVenderSinCalcularNadaEnElCliente() {
        Object[] fila = new Object[] {
                UUID.randomUUID(), "Lacteos", productoId, "Yogurt", true, presentacionId, "Botella 1 L", "botella", true, 100, true
        };
        when(presentacionRepository.buscarFilasStock(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.<Object[]>of(fila), PageRequest.of(0, 20), 1));
        escenarioControlado(20, 100); // vendible 70: H(5, vence hoy) + L(65, llega al 2026-10-20)
        reservaDe(60);                // una reserva de 60 para el 2026-10-20: solo la respalda L

        var f = service.listarStockPaginado(null, null, PageRequest.of(0, 20)).contenido().get(0);

        assertThat(f.stock()).isEqualTo(100);
        assertThat(f.stockVendible()).isEqualTo(70);
        assertThat(f.stockComprometido()).isEqualTo(60);
        assertThat(f.stockLibre()).as("H(5) + los 5 de L que sobran sobre lo reservado, igual que la validacion de venta").isEqualTo(10);
    }

    @Test
    void validarStockSuficienteNoLanzaNadaSiHayStockSuficiente() {
        when(presentacionRepository.buscarConBloqueoParaActualizarStock(presentacionId)).thenReturn(Optional.of(botella1L()));
        when(movimientoRepository.sumarCantidadPorPresentacion(presentacionId)).thenReturn(90L);

        service.validarStockSuficiente(presentacionId, 10);
    }

    @Test
    void validarStockSuficienteRechazaSiLaCantidadSolicitadaSuperaElStock() {
        when(presentacionRepository.buscarConBloqueoParaActualizarStock(presentacionId)).thenReturn(Optional.of(botella1L()));
        when(movimientoRepository.sumarCantidadPorPresentacion(presentacionId)).thenReturn(50L);

        assertThatThrownBy(() -> service.validarStockSuficiente(presentacionId, 60))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("disponibles 50")
                .hasMessageContaining("solicitadas 60");
    }

    @Test
    void validarStockSuficienteFallaSiLaPresentacionNoExiste() {
        when(presentacionRepository.buscarConBloqueoParaActualizarStock(presentacionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validarStockSuficiente(presentacionId, 1))
                .isInstanceOf(RecursoNoEncontradoException.class);

        verify(movimientoRepository, never()).sumarCantidadPorPresentacion(any());
    }

    @Test
    void registrarSalidaVentaGuardaLaCantidadEnNegativo() {
        VentaDetalle detalle = new VentaDetalle();
        detalle.setId(UUID.randomUUID());
        detalle.setPresentacion(botella1L());
        detalle.setCantidad(10);

        service.registrarSalidaVenta(detalle);

        ArgumentCaptor<MovimientoInventario> captor = ArgumentCaptor.forClass(MovimientoInventario.class);
        verify(movimientoRepository).save(captor.capture());
        MovimientoInventario movimiento = captor.getValue();
        assertThat(movimiento.getTipoMovimiento()).isEqualTo(TipoMovimientoInventario.SALIDA_VENTA);
        assertThat(movimiento.getCantidad()).isEqualTo(-10);
        assertThat(movimiento.getVentaDetalle()).isSameAs(detalle);
        assertThat(movimiento.getLoteProduccionDetalle()).isNull();
    }

    // Concurrencia (requisito explicito): con 10 unidades y dos ventas simultaneas de 7, una debe
    // ejecutarse y la otra debe ver el stock ya actualizado y ser rechazada. Fase 3: dos presentaciones
    // DISTINTAS del mismo producto nunca comparten este bloqueo -- cada una tiene su propia fila.
    // NOTA HONESTA: esto prueba la logica de secuenciamiento del service (bloquear -> leer -> validar),
    // no el comportamiento real de PostgreSQL bajo carga -- eso requiere una prueba de integracion con
    // BD real (ver ProduccionMultiPresentacionIntegrationTest).
    @Test
    void dosVentasConcurrentesDeLaMismaPresentacionSoloUnaTieneExito() throws InterruptedException {
        Presentacion presentacion = botella1L();
        ReentrantLock lockFila = new ReentrantLock();
        AtomicInteger stock = new AtomicInteger(10);

        when(presentacionRepository.buscarConBloqueoParaActualizarStock(presentacionId)).thenAnswer(inv -> {
            lockFila.lock();
            return Optional.of(presentacion);
        });
        when(movimientoRepository.sumarCantidadPorPresentacion(presentacionId)).thenAnswer(inv -> (long) stock.get());

        List<Boolean> resultados = Collections.synchronizedList(new ArrayList<>());
        Runnable ventaDe7 = () -> {
            try {
                service.validarStockSuficiente(presentacionId, 7);
                stock.addAndGet(-7); // equivalente a registrarSalidaVenta + commit
                resultados.add(true);
            } catch (ValidacionNegocioException ex) {
                resultados.add(false);
            } finally {
                if (lockFila.isHeldByCurrentThread()) {
                    lockFila.unlock();
                }
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(ventaDe7);
        pool.submit(ventaDe7);
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(resultados).containsExactlyInAnyOrder(true, false);
        assertThat(stock.get()).isEqualTo(3); // nunca negativo, nunca doble descuento de las mismas unidades
    }

    // ------------------------------------------------------------------------------------------------
    // Fase 1 (control de vencimiento): guardas de falla cerrada y entrada trazada.
    // ------------------------------------------------------------------------------------------------

    private void productoControlado() {
        when(presentacionRepository.controlaVencimientoDe(presentacionId)).thenReturn(Optional.of(true));
    }

    private LoteProduccionDetalle detalleDeEntrada(int cantidad, LocalDate vencimiento) {
        LoteProduccion lote = new LoteProduccion();
        lote.setId(UUID.randomUUID());
        LoteProduccionDetalle detalle = new LoteProduccionDetalle();
        detalle.setId(UUID.randomUUID());
        detalle.setLote(lote);
        detalle.setPresentacion(botella1L());
        detalle.setCantidadObtenida(cantidad);
        detalle.setFechaVencimiento(vencimiento);
        return detalle;
    }

    // ------------------------------------------------------------------------------------------------
    // Fase 2 (FEFO, bloqueo de vencidos, ajustes con asignacion). Escenario base (HOY = 2026-10-11):
    //   20 sin trazabilidad + lote V (vence 2026-10-10, VENCIDO) 10 + lote H (vence 2026-10-11, vence HOY, vendible)
    //   5 + lote L (vence 2026-11-01) 65  =  100 fisicas, 70 vendibles.
    // ------------------------------------------------------------------------------------------------

    private final UUID loteV = UUID.randomUUID();
    private final UUID loteH = UUID.randomUUID();
    private final UUID loteL = UUID.randomUUID();

    private Object[] filaLote(UUID id, LocalDate vence, long saldo) {
        return new Object[] {"LOTE", id, UUID.randomUUID(), java.sql.Date.valueOf(HOY.minusDays(30)), java.sql.Date.valueOf(vence), saldo};
    }

    private void escenarioControlado(int inicial, long fisico) {
        lenient().when(presentacionRepository.buscarConBloqueoParaActualizarStock(presentacionId)).thenReturn(Optional.of(botella1L()));
        lenient().when(presentacionRepository.controlaVencimientoDe(presentacionId)).thenReturn(Optional.of(true));
        lenient().when(presentacionRepository.saldoSinTrazabilidadInicialDe(presentacionId)).thenReturn(Optional.of(inicial));
        lenient().when(movimientoRepository.sumarCantidadPorPresentacion(presentacionId)).thenReturn(fisico);
        lenient().when(asignacionLoteRepository.sumarSinTrazabilidadPorPresentacion(presentacionId)).thenReturn(0L);
        lenient().when(regularizacionRepository.sumarPorPresentacion(presentacionId)).thenReturn(0L);
        lenient().when(asignacionLoteRepository.listarExistenciasPorLote(presentacionId)).thenReturn(List.<Object[]>of(
                filaLote(loteV, HOY.minusDays(1), 10), filaLote(loteH, HOY, 5), filaLote(loteL, HOY.plusDays(21), 65)));
        lenient().when(movimientoRepository.save(any(MovimientoInventario.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(detalleRepository.getReferenceById(any())).thenAnswer(inv -> {
            LoteProduccionDetalle d = new LoteProduccionDetalle();
            d.setId(inv.getArgument(0));
            return d;
        });
    }

    private VentaDetalle ventaDe(int cantidad) {
        VentaDetalle detalle = new VentaDetalle();
        detalle.setId(UUID.randomUUID());
        detalle.setPresentacion(botella1L());
        detalle.setCantidad(cantidad);
        return detalle;
    }

    /** Asignaciones guardadas, como "lote o sin-trazabilidad -> cantidad". */
    private List<String> asignacionesGuardadas() {
        ArgumentCaptor<AsignacionLote> c = ArgumentCaptor.forClass(AsignacionLote.class);
        verify(asignacionLoteRepository, org.mockito.Mockito.atLeast(0)).save(c.capture());
        return c.getAllValues().stream().map(a -> {
            String origen = a.getLoteDetalle() != null ? (a.getLoteDetalle().getId().equals(loteV) ? "V"
                    : a.getLoteDetalle().getId().equals(loteH) ? "H" : "L") : a.getRegularizacion() != null ? "REG" : "SIN";
            return origen + ":" + a.getCantidad();
        }).toList();
    }

    @Test
    void elDesgloseDeExistenciasSeparaLoFisicoLoVendibleLoVencidoYLoSinTrazabilidad() {
        escenarioControlado(20, 100);

        Existencias e = service.calcularExistencias(presentacionId);

        assertThat(e.fisico()).isEqualTo(100);
        assertThat(e.sinTrazabilidad()).isEqualTo(20);
        assertThat(e.vencido()).isEqualTo(10);
        assertThat(e.vendible()).isEqualTo(70);
        // El dia de vencimiento es inclusive: el lote que vence HOY sigue vendible; el de ayer ya no.
        assertThat(e.lotes()).extracting(Existencias.Lote::vencido).containsExactly(true, false, false);
    }

    @Test
    void laVentaSaleEnFefoDeLosLotesVigentesSaltandoLoVencidoYLoSinTrazabilidad() {
        escenarioControlado(20, 100);

        service.registrarSalidaVenta(ventaDe(8));

        ArgumentCaptor<MovimientoInventario> mov = ArgumentCaptor.forClass(MovimientoInventario.class);
        verify(movimientoRepository).save(mov.capture());
        assertThat(mov.getValue().getTipoMovimiento()).isEqualTo(TipoMovimientoInventario.SALIDA_VENTA);
        assertThat(mov.getValue().getCantidad()).isEqualTo(-8);
        // 5 del lote que vence hoy (sale primero) y 3 del siguiente; nada del vencido ni de lo sin trazabilidad.
        assertThat(asignacionesGuardadas()).containsExactly("H:-5", "L:-3");
    }

    @Test
    void laVentaPuedeConsumirTodoLoVendibleYNadaMas() {
        escenarioControlado(20, 100);

        service.registrarSalidaVenta(ventaDe(70));

        assertThat(asignacionesGuardadas()).containsExactly("H:-5", "L:-65");
    }

    @Test
    void laVentaQueSuperaLoVendibleSeRechazaAunqueElInventarioFisicoAlcance() {
        escenarioControlado(20, 100); // hay 100 fisicas pero solo 70 vendibles

        assertThatThrownBy(() -> service.registrarSalidaVenta(ventaDe(71)))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("vendibles 70").hasMessageContaining("solicitadas 71");

        verify(movimientoRepository, never()).save(any());
        verify(asignacionLoteRepository, never()).save(any());
    }

    @Test
    void validarStockSuficienteUsaElVendibleNoElFisico() {
        escenarioControlado(20, 100);

        service.validarStockSuficiente(presentacionId, 70);
        assertThatThrownBy(() -> service.validarStockSuficiente(presentacionId, 71))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("vendibles 70").hasMessageContaining("100").hasMessageContaining("10 vencidas")
                .hasMessageContaining("20 sin trazabilidad");
    }

    @Test
    void unAjusteNegativoDescuentaPrimeroLoVencidoLuegoLoSinTrazabilidadYDespuesFefo() {
        escenarioControlado(20, 100);
        AjusteInventario ajuste = new AjusteInventario();
        ajuste.setPresentacion(botella1L());

        service.registrarAjuste(ajuste, -37); // 10 vencidas + 20 sin trazabilidad + 7 vigentes (5 de H y 2 de L)

        assertThat(asignacionesGuardadas()).containsExactly("V:-10", "SIN:-20", "H:-5", "L:-2");
    }

    @Test
    void unAjusteNegativoPequenoSoloTocaLoVencidoPrimero() {
        escenarioControlado(20, 100);
        AjusteInventario ajuste = new AjusteInventario();
        ajuste.setPresentacion(botella1L());

        service.registrarAjuste(ajuste, -4);

        assertThat(asignacionesGuardadas()).containsExactly("V:-4");
    }

    @Test
    void unAjustePositivoEntraComoSinTrazabilidadYNoSeVende() {
        escenarioControlado(20, 100);
        AjusteInventario ajuste = new AjusteInventario();
        ajuste.setPresentacion(botella1L());

        service.registrarAjuste(ajuste, 6);

        ArgumentCaptor<MovimientoInventario> mov = ArgumentCaptor.forClass(MovimientoInventario.class);
        verify(movimientoRepository).save(mov.capture());
        assertThat(mov.getValue().getTipoMovimiento()).isEqualTo(TipoMovimientoInventario.AJUSTE);
        assertThat(mov.getValue().getCantidad()).isEqualTo(6);
        assertThat(asignacionesGuardadas()).containsExactly("SIN:6");
    }

    @Test
    void unAjusteNegativoMayorAlInventarioFisicoSeRechazaSinEscribirNada() {
        escenarioControlado(20, 100);
        AjusteInventario ajuste = new AjusteInventario();
        ajuste.setPresentacion(botella1L());

        assertThatThrownBy(() -> service.registrarAjuste(ajuste, -101)).isInstanceOf(ValidacionNegocioException.class);

        verify(movimientoRepository, never()).save(any());
        verify(asignacionLoteRepository, never()).save(any());
    }

    @Test
    void lasExistenciasDescuadradasConElLibroFallanCerradoYNoSeEscribeNada() {
        escenarioControlado(20, 101); // el libro dice 101 pero el reparto suma 100

        assertThatThrownBy(() -> service.registrarSalidaVenta(ventaDe(1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no cuadra con el libro");

        verify(movimientoRepository, never()).save(any());
        verify(asignacionLoteRepository, never()).save(any());
    }

    @Test
    void unaRegularizacionCuentaComoLoteConSuPropioSaldoYSeDescuentaDeLoSinTrazabilidad() {
        escenarioControlado(30, 100); // 30 de saldo inicial, 10 de ellas ya regularizadas -> 20 sin trazabilidad
        UUID reg = UUID.randomUUID();
        when(regularizacionRepository.sumarPorPresentacion(presentacionId)).thenReturn(10L);
        when(asignacionLoteRepository.listarExistenciasPorLote(presentacionId)).thenReturn(List.<Object[]>of(
                filaLote(loteV, HOY.minusDays(1), 10), filaLote(loteH, HOY, 5),
                new Object[] {"REGULARIZACION", reg, null, java.sql.Date.valueOf(HOY), java.sql.Date.valueOf(HOY.plusDays(3)), 10L},
                filaLote(loteL, HOY.plusDays(21), 55)));

        Existencias e = service.calcularExistencias(presentacionId);

        assertThat(e.sinTrazabilidad()).isEqualTo(20);
        assertThat(e.vendible()).isEqualTo(70); // 5 + 10 (regularizadas, vigentes) + 55
        assertThat(e.fisico()).isEqualTo(100);
    }

    @Test
    void obtenerExistenciasDeUnProductoSinControlDevuelveFisicoIgualAVendibleYSinLotes() {
        when(presentacionRepository.existsById(presentacionId)).thenReturn(true);
        when(movimientoRepository.sumarCantidadPorPresentacion(presentacionId)).thenReturn(42L);

        var r = service.obtenerExistencias(presentacionId);

        assertThat(r.controlaVencimiento()).isFalse();
        assertThat(r.stockFisico()).isEqualTo(42);
        assertThat(r.stockVendible()).isEqualTo(42);
        assertThat(r.stockVencido()).isZero();
        assertThat(r.lotes()).isEmpty();
        assertThat(r.fechaReferencia()).isEqualTo(HOY);
    }

    @Test
    void obtenerExistenciasDeUnProductoControladoInformaDiasParaVencerYElEstadoDeCadaLote() {
        escenarioControlado(20, 100);
        when(presentacionRepository.existsById(presentacionId)).thenReturn(true);

        var r = service.obtenerExistencias(presentacionId);

        assertThat(r.controlaVencimiento()).isTrue();
        assertThat(r.stockFisico()).isEqualTo(100);
        assertThat(r.stockVendible()).isEqualTo(70);
        assertThat(r.stockVencido()).isEqualTo(10);
        assertThat(r.stockSinTrazabilidad()).isEqualTo(20);
        assertThat(r.lotes()).extracting(l -> l.diasParaVencer()).containsExactly(-1L, 0L, 21L);
        assertThat(r.lotes()).extracting(l -> l.vencido()).containsExactly(true, false, false);
    }

    @Test
    void obtenerExistenciasDeUnaPresentacionInexistenteEs404() {
        when(presentacionRepository.existsById(presentacionId)).thenReturn(false);

        assertThatThrownBy(() -> service.obtenerExistencias(presentacionId)).isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void laEntradaPorElCaminoLegadoDeUnaPresentacionControladaSeRechaza() {
        productoControlado();

        assertThatThrownBy(() -> service.registrarEntradaProduccion(detalleDeEntrada(5, null)))
                .isInstanceOf(ValidacionNegocioException.class);

        verify(movimientoRepository, never()).save(any());
    }

    @Test
    void laGuardaBloqueaLaPresentacionANTESDeLeerElIndicador() {
        productoControlado();

        assertThatThrownBy(() -> service.registrarEntradaProduccion(detalleDeEntrada(1, null)))
                .isInstanceOf(ValidacionNegocioException.class);

        InOrder orden = inOrder(presentacionRepository);
        orden.verify(presentacionRepository).buscarConBloqueoParaActualizarStock(presentacionId);
        orden.verify(presentacionRepository).controlaVencimientoDe(presentacionId);
    }

    @Test
    void bloquearPresentacionesLoHaceEnOrdenDeIdYSinRepetir() {
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);
        UUID c = new UUID(0, 3);

        service.bloquearPresentaciones(List.of(c, a, b, a));

        InOrder orden = inOrder(presentacionRepository);
        orden.verify(presentacionRepository).buscarConBloqueoParaActualizarStock(a);
        orden.verify(presentacionRepository).buscarConBloqueoParaActualizarStock(b);
        orden.verify(presentacionRepository).buscarConBloqueoParaActualizarStock(c);
        verify(presentacionRepository, org.mockito.Mockito.times(1)).buscarConBloqueoParaActualizarStock(a);
    }

    @Test
    void laEntradaTrazadaEscribeElMovimientoYSuRepartoCompletoEnElLote() {
        productoControlado();
        when(presentacionRepository.buscarConBloqueoParaActualizarStock(presentacionId)).thenReturn(Optional.of(botella1L()));
        when(presentacionRepository.saldoSinTrazabilidadInicialDe(presentacionId)).thenReturn(Optional.of(100));
        LoteProduccionDetalle detalle = detalleDeEntrada(60, LocalDate.of(2026, 11, 20));

        service.registrarEntradaProduccionTrazada(detalle);

        ArgumentCaptor<MovimientoInventario> mov = ArgumentCaptor.forClass(MovimientoInventario.class);
        verify(movimientoRepository).save(mov.capture());
        assertThat(mov.getValue().getTipoMovimiento()).isEqualTo(TipoMovimientoInventario.ENTRADA_PRODUCCION);
        assertThat(mov.getValue().getCantidad()).isEqualTo(60);
        ArgumentCaptor<AsignacionLote> asig = ArgumentCaptor.forClass(AsignacionLote.class);
        verify(asignacionLoteRepository).save(asig.capture());
        assertThat(asig.getValue().getMovimiento()).isSameAs(mov.getValue());
        assertThat(asig.getValue().getLoteDetalle()).isSameAs(detalle);
        assertThat(asig.getValue().getCantidad()).isEqualTo(60);
    }

    @Test
    void laEntradaTrazadaFallaCerradaSiFaltaFechaSaldoInicialOControl() {
        when(presentacionRepository.buscarConBloqueoParaActualizarStock(presentacionId)).thenReturn(Optional.of(botella1L()));

        // Sin control activo.
        assertThatThrownBy(() -> service.registrarEntradaProduccionTrazada(detalleDeEntrada(5, LocalDate.of(2026, 11, 20))))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("no pertenece");

        // Con control, pero sin fecha.
        productoControlado();
        assertThatThrownBy(() -> service.registrarEntradaProduccionTrazada(detalleDeEntrada(5, null)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("fecha de vencimiento");

        // Con control y fecha, pero sin saldo inicial (presentacion que no se inicializo).
        when(presentacionRepository.saldoSinTrazabilidadInicialDe(presentacionId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.registrarEntradaProduccionTrazada(detalleDeEntrada(5, LocalDate.of(2026, 11, 20))))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("saldo inicial");

        verify(movimientoRepository, never()).save(any());
        verify(asignacionLoteRepository, never()).save(any());
    }

    // ------------------------------------------------------------------------------------------------
    // Fase 4 (reservas): una venta directa no puede dejar sin cobertura a reservas validas; un ajuste por perdida fisica
    // si se registra siempre y deja una incidencia. Escenario base (HOY = 2026-10-11): 20 sin trazabilidad + lote V
    // vencido 10 + lote H (vence hoy) 5 + lote L (vence 2026-11-01) 65 = 100 fisicas, 70 vendibles. Una reserva de 60
    // unidades para el 2026-10-20 solo puede respaldarse con L (el unico lote que llega a esa fecha).
    // ------------------------------------------------------------------------------------------------

    private static final LocalDate RETIRO = LocalDate.of(2026, 10, 20);

    private void reservaDe(int cantidad) {
        lenient().when(reservaCompromisos.demandasDe(presentacionId)).thenReturn(List.of(new CoberturaReservas.Demanda(RETIRO, cantidad)));
    }

    @Test
    void unaVentaQueNoDejaSinCoberturaALasReservasSePermite() {
        escenarioControlado(20, 100);
        reservaDe(60);

        service.registrarSalidaVenta(ventaDe(10)); // H(5) + L(5): L queda en 60 = lo que necesita la reserva

        assertThat(asignacionesGuardadas()).containsExactly("H:-5", "L:-5");
    }

    @Test
    void unaVentaQueDejaSinCoberturaAUnaReservaSeRechazaSinEscribirNada() {
        escenarioControlado(20, 100);
        reservaDe(60);

        assertThatThrownBy(() -> service.registrarSalidaVenta(ventaDe(11))) // dejaria L en 59 < 60
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("comprometido por reservas").hasMessageContaining("reservadas 60");

        verify(movimientoRepository, never()).save(any());
        verify(asignacionLoteRepository, never()).save(any());
    }

    @Test
    void validarStockSuficienteTambienRespetaLasReservas() {
        escenarioControlado(20, 100);
        reservaDe(60);

        service.validarStockSuficiente(presentacionId, 10);
        assertThatThrownBy(() -> service.validarStockSuficiente(presentacionId, 11))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("comprometido por reservas");
    }

    @Test
    void sinReservasLaVentaNoConsultaMasQueElStockVendible() {
        escenarioControlado(20, 100);

        service.registrarSalidaVenta(ventaDe(70));

        verify(incidenciaRepository, never()).save(any());
    }

    @Test
    void unAjusteNegativoQueNoRompeLaCoberturaNoDejaIncidencia() {
        escenarioControlado(20, 100);
        reservaDe(60);
        AjusteInventario ajuste = new AjusteInventario();
        ajuste.setId(UUID.randomUUID());
        ajuste.setPresentacion(botella1L());
        // Tras el ajuste de -37 (V 10, sin trazabilidad 20, H 5, L 2): L = 63 >= 60.
        when(movimientoRepository.sumarCantidadPorPresentacion(presentacionId)).thenReturn(100L, 63L);
        when(asignacionLoteRepository.sumarSinTrazabilidadPorPresentacion(presentacionId)).thenReturn(0L, -20L);
        when(asignacionLoteRepository.listarExistenciasPorLote(presentacionId)).thenReturn(
                List.<Object[]>of(filaLote(loteV, HOY.minusDays(1), 10), filaLote(loteH, HOY, 5), filaLote(loteL, HOY.plusDays(21), 65)),
                List.<Object[]>of(filaLote(loteL, HOY.plusDays(21), 63)));

        service.registrarAjuste(ajuste, -37);

        verify(incidenciaRepository, never()).save(any());
    }

    @Test
    void unAjusteQueDejaSinCoberturaUnaReservaSeRegistraIgualYDejaUnaIncidencia() {
        escenarioControlado(20, 100);
        reservaDe(60);
        AjusteInventario ajuste = new AjusteInventario();
        ajuste.setId(UUID.randomUUID());
        ajuste.setPresentacion(botella1L());
        // Tras el ajuste de -42 (V 10, sin trazabilidad 20, H 5, L 7): L = 58 < 60 -> faltan 2.
        when(movimientoRepository.sumarCantidadPorPresentacion(presentacionId)).thenReturn(100L, 58L);
        when(asignacionLoteRepository.sumarSinTrazabilidadPorPresentacion(presentacionId)).thenReturn(0L, -20L);
        when(asignacionLoteRepository.listarExistenciasPorLote(presentacionId)).thenReturn(
                List.<Object[]>of(filaLote(loteV, HOY.minusDays(1), 10), filaLote(loteH, HOY, 5), filaLote(loteL, HOY.plusDays(21), 65)),
                List.<Object[]>of(filaLote(loteL, HOY.plusDays(21), 58)));

        service.registrarAjuste(ajuste, -42); // la perdida fisica NO se bloquea

        ArgumentCaptor<MovimientoInventario> mov = ArgumentCaptor.forClass(MovimientoInventario.class);
        verify(movimientoRepository).save(mov.capture());
        assertThat(mov.getValue().getCantidad()).isEqualTo(-42);
        ArgumentCaptor<IncidenciaCobertura> inc = ArgumentCaptor.forClass(IncidenciaCobertura.class);
        verify(incidenciaRepository).save(inc.capture());
        assertThat(inc.getValue().getUnidadesFaltantes()).isEqualTo(2);
        assertThat(inc.getValue().getAjuste()).isSameAs(ajuste);
    }

    @Test
    void unAjustePositivoNuncaDejaIncidencia() {
        escenarioControlado(20, 100);
        reservaDe(60);
        AjusteInventario ajuste = new AjusteInventario();
        ajuste.setPresentacion(botella1L());

        service.registrarAjuste(ajuste, 6);

        verify(incidenciaRepository, never()).save(any());
    }
}
