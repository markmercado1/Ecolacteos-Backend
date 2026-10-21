package com.planta.acopio.service;

import com.planta.acopio.dto.CorregirVencimientoRequest;
import com.planta.acopio.dto.RegularizacionRequest;
import com.planta.acopio.entity.LoteProduccion;
import com.planta.acopio.entity.LoteProduccionDetalle;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.AsignacionLoteRepository;
import com.planta.acopio.repository.LoteProduccionDetalleRepository;
import com.planta.acopio.repository.MovimientoInventarioRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.RegularizacionTrazabilidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Fase 1: activar/desactivar el control de vencimiento y corregir fechas. Las propiedades de bloqueo y
// concurrencia reales (SELECT ... FOR UPDATE contra PostgreSQL) se prueban en ControlVencimientoIntegrationTest;
// aqui se verifica la logica y el ORDEN (bloquear primero, leer despues).
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ControlVencimientoServiceTest {

    @Mock private ProductoRepository productoRepository;
    @Mock private PresentacionRepository presentacionRepository;
    @Mock private MovimientoInventarioRepository movimientoRepository;
    @Mock private AsignacionLoteRepository asignacionLoteRepository;
    @Mock private LoteProduccionDetalleRepository detalleRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private InventarioService inventarioService;
    @Mock private RegularizacionTrazabilidadRepository regularizacionRepository;

    // "Hoy" fijo: 2026-10-11 en America/Lima.
    private static final LocalDate HOY_LIMA = LocalDate.of(2026, 10, 11);
    private static final ReglaVencimiento REGLA = new ReglaVencimiento(
            Clock.fixed(HOY_LIMA.atTime(10, 0).atZone(ZoneId.of("America/Lima")).toInstant(), ZoneId.of("America/Lima")));

    private ControlVencimientoService service;
    private ControlVencimientoService serviceDeshabilitado;
    private Producto yogurt;
    private Presentacion litro;
    private Presentacion medio;

    @BeforeEach
    void setUp() {
        service = new ControlVencimientoService(productoRepository, presentacionRepository, movimientoRepository,
                asignacionLoteRepository, detalleRepository, usuarioRepository, inventarioService, regularizacionRepository, REGLA, true);
        serviceDeshabilitado = new ControlVencimientoService(productoRepository, presentacionRepository, movimientoRepository,
                asignacionLoteRepository, detalleRepository, usuarioRepository, inventarioService, regularizacionRepository, REGLA, false);
        yogurt = new Producto();
        yogurt.setId(UUID.randomUUID());
        yogurt.setNombre("Yogurt");
        litro = presentacion("Botella 1 L");
        medio = presentacion("Botella 500 ml");
        when(productoRepository.findById(yogurt.getId())).thenReturn(Optional.of(yogurt));
        when(presentacionRepository.findByProductoIdOrderByNombre(yogurt.getId())).thenReturn(List.of(medio, litro));
        when(movimientoRepository.sumarCantidadPorPresentacion(litro.getId())).thenReturn(100L);
        when(movimientoRepository.sumarCantidadPorPresentacion(medio.getId())).thenReturn(0L);
        when(productoRepository.controlaVencimiento(yogurt.getId())).thenReturn(Optional.of(false));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private Presentacion presentacion(String nombre) {
        Presentacion p = new Presentacion();
        p.setId(UUID.randomUUID());
        p.setNombre(nombre);
        p.setProducto(yogurt);
        return p;
    }

    // ------------------------------------------------------------------ activar

    @Test
    void activarCapturaElStockDelLibroDeCadaPresentacionComoSaldoInicialSinTrazabilidad() {
        var r = service.activar(yogurt.getId());

        assertThat(r.controlaVencimiento()).isTrue();
        assertThat(yogurt.getControlaVencimiento()).isTrue();
        assertThat(litro.getSaldoSinTrazabilidadInicial()).isEqualTo(100);
        assertThat(medio.getSaldoSinTrazabilidadInicial()).isEqualTo(0);
        assertThat(r.presentaciones()).extracting(p -> p.saldoSinTrazabilidadInicial()).containsExactlyInAnyOrder(100, 0);
    }

    @Test
    void activarBloqueaLasPresentacionesANTESDeLeerElIndicadorYElStock() {
        service.activar(yogurt.getId());

        var orden = inOrder(inventarioService, productoRepository, movimientoRepository);
        orden.verify(inventarioService).bloquearPresentaciones(any());
        orden.verify(productoRepository).controlaVencimiento(yogurt.getId());
        orden.verify(movimientoRepository, org.mockito.Mockito.atLeastOnce()).sumarCantidadPorPresentacion(any());
    }

    @Test
    void activarEsIdempotenteYNoRecapturaElSaldo() {
        when(productoRepository.controlaVencimiento(yogurt.getId())).thenReturn(Optional.of(true));
        litro.setSaldoSinTrazabilidadInicial(40);

        service.activar(yogurt.getId());

        assertThat(litro.getSaldoSinTrazabilidadInicial()).isEqualTo(40); // no se pisa con el stock actual (100)
    }

    @Test
    void activarEstaDeshabilitadoPorDefectoYNoCambiaNada() {
        assertThatThrownBy(() -> serviceDeshabilitado.activar(yogurt.getId()))
                .isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("deshabilitada");

        assertThat(yogurt.getControlaVencimiento()).isFalse();
        assertThat(litro.getSaldoSinTrazabilidadInicial()).isNull();
    }

    @Test
    void activarUnProductoInexistenteDa404() {
        assertThatThrownBy(() -> service.activar(UUID.randomUUID())).isInstanceOf(RecursoNoEncontradoException.class);
    }

    // ------------------------------------------------------------------ desactivar

    private void productoActivadoConSaldos(long stockLitro, Integer inicialLitro) {
        yogurt.setControlaVencimiento(true);
        litro.setSaldoSinTrazabilidadInicial(inicialLitro);
        medio.setSaldoSinTrazabilidadInicial(0);
        when(productoRepository.controlaVencimiento(yogurt.getId())).thenReturn(Optional.of(true));
        when(movimientoRepository.sumarCantidadPorPresentacion(litro.getId())).thenReturn(stockLitro);
        when(presentacionRepository.saldoSinTrazabilidadInicialDe(litro.getId())).thenReturn(Optional.ofNullable(inicialLitro));
        when(presentacionRepository.saldoSinTrazabilidadInicialDe(medio.getId())).thenReturn(Optional.of(0));
    }

    @Test
    void desactivarSinAsignacionesYConElStockIgualAlInicialRevierteLaActivacion() {
        productoActivadoConSaldos(100, 100);
        when(asignacionLoteRepository.contarPorProducto(yogurt.getId())).thenReturn(0L);

        var r = service.desactivar(yogurt.getId());

        assertThat(r.controlaVencimiento()).isFalse();
        assertThat(yogurt.getControlaVencimiento()).isFalse();
        assertThat(litro.getSaldoSinTrazabilidadInicial()).isNull();
        assertThat(medio.getSaldoSinTrazabilidadInicial()).isNull();
    }

    @Test
    void desactivarFuncionAunqueLaActivacionEsteDeshabilitada() {
        productoActivadoConSaldos(100, 100);
        when(asignacionLoteRepository.contarPorProducto(yogurt.getId())).thenReturn(0L);

        serviceDeshabilitado.desactivar(yogurt.getId());

        assertThat(yogurt.getControlaVencimiento()).isFalse();
    }

    @Test
    void desactivarSeRechazaSiYaExistenAsignaciones() {
        productoActivadoConSaldos(160, 100);
        when(asignacionLoteRepository.contarPorProducto(yogurt.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.desactivar(yogurt.getId()))
                .isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("asignados a lotes");

        assertThat(yogurt.getControlaVencimiento()).isTrue();
        assertThat(litro.getSaldoSinTrazabilidadInicial()).isEqualTo(100);
    }

    @Test
    void desactivarSeRechazaSiElStockYaNoCoincideConElSaldoInicial() {
        productoActivadoConSaldos(97, 100);
        when(asignacionLoteRepository.contarPorProducto(yogurt.getId())).thenReturn(0L);

        assertThatThrownBy(() -> service.desactivar(yogurt.getId()))
                .isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("ya no coincide");

        assertThat(yogurt.getControlaVencimiento()).isTrue();
    }

    @Test
    void desactivarPermiteRepararUnaPresentacionSinSaldoInicialSiNoTieneStock() {
        productoActivadoConSaldos(0, null);
        when(asignacionLoteRepository.contarPorProducto(yogurt.getId())).thenReturn(0L);

        service.desactivar(yogurt.getId());

        assertThat(yogurt.getControlaVencimiento()).isFalse();
    }

    @Test
    void desactivarBloqueaANTESDeComprobarAsignacionesYStock() {
        productoActivadoConSaldos(100, 100);
        when(asignacionLoteRepository.contarPorProducto(yogurt.getId())).thenReturn(0L);

        service.desactivar(yogurt.getId());

        var orden = inOrder(inventarioService, asignacionLoteRepository, movimientoRepository);
        orden.verify(inventarioService).bloquearPresentaciones(any());
        orden.verify(asignacionLoteRepository).contarPorProducto(yogurt.getId());
        orden.verify(movimientoRepository, org.mockito.Mockito.atLeastOnce()).sumarCantidadPorPresentacion(any());
    }

    @Test
    void desactivarUnProductoSinControlEsIdempotente() {
        var r = service.desactivar(yogurt.getId());

        assertThat(r.controlaVencimiento()).isFalse();
    }

    // ------------------------------------------------------------------ corregir vencimiento

    private LoteProduccionDetalle detalleConFecha(LocalDate fechaLote, LocalDate vencimiento) {
        LoteProduccion lote = new LoteProduccion();
        lote.setId(UUID.randomUUID());
        lote.setFecha(fechaLote);
        LoteProduccionDetalle d = new LoteProduccionDetalle();
        d.setId(UUID.randomUUID());
        d.setLote(lote);
        d.setPresentacion(litro);
        d.setCantidadObtenida(60);
        d.setFechaVencimiento(vencimiento);
        when(detalleRepository.presentacionIdDe(d.getId())).thenReturn(Optional.of(litro.getId()));
        when(detalleRepository.findById(d.getId())).thenReturn(Optional.of(d));
        when(inventarioService.controlaVencimiento(litro.getId())).thenReturn(true);
        when(asignacionLoteRepository.existsByLoteDetalleIdAndCantidadLessThan(d.getId(), 0)).thenReturn(false);
        Usuario u = new Usuario();
        u.setId(UUID.randomUUID());
        when(usuarioRepository.findByEmail("produccion@planta.com")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("produccion@planta.com", null));
        return d;
    }

    @Test
    void corregirGuardaLaNuevaFechaConservandoLaOriginalYQuienCuandoYPorQue() {
        var hoy = LocalDate.now();
        var d = detalleConFecha(hoy, hoy.plusDays(400));

        var r = service.corregirVencimiento(d.getId(), new CorregirVencimientoRequest(hoy.plusDays(40), "  Se digitó 2027 por error "));

        assertThat(d.getFechaVencimiento()).isEqualTo(hoy.plusDays(40));
        assertThat(d.getFechaVencimientoOriginal()).isEqualTo(hoy.plusDays(400));
        assertThat(d.getVencimientoMotivoCorreccion()).isEqualTo("Se digitó 2027 por error");
        assertThat(d.getVencimientoCorregidoPor()).isNotNull();
        assertThat(d.getVencimientoCorregidoEn()).isNotNull();
        assertThat(r.fechaVencimientoOriginal()).isEqualTo(hoy.plusDays(400));
    }

    @Test
    void unaSegundaCorreccionNoPisaLaFechaOriginal() {
        var hoy = LocalDate.now();
        var d = detalleConFecha(hoy, hoy.plusDays(400));
        service.corregirVencimiento(d.getId(), new CorregirVencimientoRequest(hoy.plusDays(40), "primera"));

        service.corregirVencimiento(d.getId(), new CorregirVencimientoRequest(hoy.plusDays(30), "segunda"));

        assertThat(d.getFechaVencimientoOriginal()).isEqualTo(hoy.plusDays(400));
        assertThat(d.getFechaVencimiento()).isEqualTo(hoy.plusDays(30));
        assertThat(d.getVencimientoMotivoCorreccion()).isEqualTo("segunda");
    }

    @Test
    void corregirSeRechazaSiElLoteYaTieneSalidasAsignadas() {
        var hoy = LocalDate.now();
        var d = detalleConFecha(hoy, hoy.plusDays(400));
        when(asignacionLoteRepository.existsByLoteDetalleIdAndCantidadLessThan(d.getId(), 0)).thenReturn(true);

        assertThatThrownBy(() -> service.corregirVencimiento(d.getId(), new CorregirVencimientoRequest(hoy.plusDays(40), "x")))
                .isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("ya fue utilizado");

        assertThat(d.getFechaVencimiento()).isEqualTo(hoy.plusDays(400));
        assertThat(d.getFechaVencimientoOriginal()).isNull();
    }

    @Test
    void corregirSeRechazaParaUnProductoSinControlOSinFechaRegistrada() {
        var hoy = LocalDate.now();
        var sinControl = detalleConFecha(hoy, hoy.plusDays(40));
        when(inventarioService.controlaVencimiento(litro.getId())).thenReturn(false);
        assertThatThrownBy(() -> service.corregirVencimiento(sinControl.getId(), new CorregirVencimientoRequest(hoy.plusDays(30), "x")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("no controla");

        var sinFecha = detalleConFecha(hoy, null);
        assertThatThrownBy(() -> service.corregirVencimiento(sinFecha.getId(), new CorregirVencimientoRequest(hoy.plusDays(30), "x")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("sin trazabilidad");
    }

    @Test
    void corregirRechazaFechasAnterioresAlLoteOIgualesALaActual() {
        var hoy = LocalDate.now();
        var d = detalleConFecha(hoy, hoy.plusDays(40));

        assertThatThrownBy(() -> service.corregirVencimiento(d.getId(), new CorregirVencimientoRequest(hoy.minusDays(1), "x")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("anterior a la fecha del lote");
        assertThatThrownBy(() -> service.corregirVencimiento(d.getId(), new CorregirVencimientoRequest(hoy.plusDays(40), "x")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("la misma");

        assertThat(d.getFechaVencimientoOriginal()).isNull();
    }

    @Test
    void corregirBloqueaLaPresentacionAntesDeCargarElDetalle() {
        var hoy = LocalDate.now();
        var d = detalleConFecha(hoy, hoy.plusDays(400));

        service.corregirVencimiento(d.getId(), new CorregirVencimientoRequest(hoy.plusDays(40), "x"));

        var orden = inOrder(detalleRepository, inventarioService);
        orden.verify(detalleRepository).presentacionIdDe(d.getId());
        orden.verify(inventarioService).bloquearPresentaciones(List.of(litro.getId()));
        orden.verify(detalleRepository).findById(d.getId());
    }

    @Test
    void corregirUnDetalleInexistenteDa404() {
        assertThatThrownBy(() -> service.corregirVencimiento(UUID.randomUUID(), new CorregirVencimientoRequest(LocalDate.now(), "x")))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    // ------------------------------------------------------------------ regularizar (Fase 2)

    private void autenticarAdmin() {
        Usuario u = new Usuario();
        u.setId(UUID.randomUUID());
        u.setEmail("admin@planta.com");
        when(usuarioRepository.findByEmail("admin@planta.com")).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null));
    }

    private void presentacionControlada(int sinTrazabilidad) {
        when(presentacionRepository.buscarConBloqueoParaActualizarStock(litro.getId())).thenReturn(Optional.of(litro));
        when(inventarioService.controlaVencimiento(litro.getId())).thenReturn(true);
        when(inventarioService.calcularExistencias(litro.getId()))
                .thenReturn(new Existencias(100, sinTrazabilidad, 0, 100 - sinTrazabilidad, List.of()));
        when(regularizacionRepository.save(any())).thenAnswer(inv -> {
            var r = (com.planta.acopio.entity.RegularizacionTrazabilidad) inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
    }

    @Test
    void regularizarReclasificaLasUnidadesSinEscribirEnElLibro() {
        autenticarAdmin();
        presentacionControlada(60);

        var r = service.regularizar(litro.getId(), new RegularizacionRequest(25, HOY_LIMA.plusDays(10), "  Etiqueta del lote viejo  "));

        assertThat(r.cantidad()).isEqualTo(25);
        assertThat(r.fechaVencimiento()).isEqualTo(HOY_LIMA.plusDays(10));
        assertThat(r.vencido()).isFalse();
        assertThat(r.motivo()).isEqualTo("Etiqueta del lote viejo");
        assertThat(r.sinTrazabilidadRestante()).isEqualTo(35);
        verify(regularizacionRepository).save(any());
        verify(movimientoRepository, never()).save(any());
    }

    @Test
    void regularizarPermiteTodoLoSinTrazabilidadYUnaFechaPasadaQuedaVencida() {
        autenticarAdmin();
        presentacionControlada(40);

        var r = service.regularizar(litro.getId(), new RegularizacionRequest(40, HOY_LIMA.minusDays(1), "Producto viejo hallado"));

        assertThat(r.sinTrazabilidadRestante()).isZero();
        assertThat(r.vencido()).isTrue();
    }

    @Test
    void regularizarMasDeLoQueHaySinTrazabilidadSeRechazaSinGuardarNada() {
        autenticarAdmin();
        presentacionControlada(40);

        assertThatThrownBy(() -> service.regularizar(litro.getId(), new RegularizacionRequest(41, HOY_LIMA.plusDays(5), "x")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("Solo hay 40");

        verify(regularizacionRepository, never()).save(any());
    }

    @Test
    void regularizarUnProductoSinControlOUnaPresentacionInexistenteSeRechaza() {
        autenticarAdmin();
        when(presentacionRepository.buscarConBloqueoParaActualizarStock(litro.getId())).thenReturn(Optional.of(litro));
        when(inventarioService.controlaVencimiento(litro.getId())).thenReturn(false);

        assertThatThrownBy(() -> service.regularizar(litro.getId(), new RegularizacionRequest(1, HOY_LIMA, "x")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("no controla vencimiento");
        assertThatThrownBy(() -> service.regularizar(UUID.randomUUID(), new RegularizacionRequest(1, HOY_LIMA, "x")))
                .isInstanceOf(RecursoNoEncontradoException.class);

        verify(regularizacionRepository, never()).save(any());
    }

    @Test
    void regularizarBloqueaLaPresentacionANTESDeLeerLasExistencias() {
        autenticarAdmin();
        presentacionControlada(10);

        service.regularizar(litro.getId(), new RegularizacionRequest(5, HOY_LIMA.plusDays(5), "x"));

        var orden = inOrder(presentacionRepository, inventarioService);
        orden.verify(presentacionRepository).buscarConBloqueoParaActualizarStock(litro.getId());
        orden.verify(inventarioService).controlaVencimiento(litro.getId());
        orden.verify(inventarioService).calcularExistencias(litro.getId());
    }
}
