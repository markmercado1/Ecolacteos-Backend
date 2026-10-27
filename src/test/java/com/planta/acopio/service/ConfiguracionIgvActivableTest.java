package com.planta.acopio.service;

import com.planta.acopio.dto.ConfiguracionIgvDtos.AfectacionLoteRequest;
import com.planta.acopio.dto.ConfiguracionIgvDtos.AfectacionLoteResponse;
import com.planta.acopio.dto.ConfiguracionIgvDtos.EstadoIgvResponse;
import com.planta.acopio.entity.AfectacionIgv;
import com.planta.acopio.entity.AfectacionIgvPresentacion;
import com.planta.acopio.entity.CambioCalculoIgv;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.TasaIgv;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.AfectacionIgvRepository;
import com.planta.acopio.repository.CambioCalculoIgvRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.TasaIgvRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// IGV activable desde el panel (V35): el interruptor general lo decide un ADMIN (con motivo, registrado) y manda sobre el valor por defecto; la
// clasificacion en lote solo completa lo que falta. Nada de esto recalcula ventas ya registradas.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConfiguracionIgvActivableTest {

    @Mock private TasaIgvRepository tasaRepository;
    @Mock private AfectacionIgvRepository afectacionRepository;
    @Mock private PresentacionRepository presentacionRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private CambioCalculoIgvRepository cambioRepository;

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-26T15:00:00Z"), ZoneId.of("America/Lima"));
    private static final LocalDate HOY = LocalDate.of(2026, 10, 26);

    private final AtomicReference<CambioCalculoIgv> ultimo = new AtomicReference<>();
    private final UUID presentacionId = UUID.randomUUID();

    @BeforeEach
    void preparar() {
        when(cambioRepository.findFirstByOrderByCreadoEnDescIdDesc()).thenAnswer(inv -> Optional.ofNullable(ultimo.get()));
        when(cambioRepository.save(any(CambioCalculoIgv.class))).thenAnswer(inv -> {
            ultimo.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(tasaRepository.findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(any())).thenReturn(Optional.of(tasa()));
        when(afectacionRepository.save(any(AfectacionIgvPresentacion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(presentacionRepository.getReferenceById(any())).thenAnswer(inv -> presentacion(inv.getArgument(0)));
    }

    private ConfiguracionIgvService servicio(boolean porDefecto) {
        return new ConfiguracionIgvService(tasaRepository, afectacionRepository, presentacionRepository, usuarioRepository, cambioRepository, porDefecto, RELOJ);
    }

    private static TasaIgv tasa() {
        TasaIgv t = new TasaIgv();
        t.setTasa(new BigDecimal("18.00"));
        t.setVigenteDesde(HOY.minusDays(2));
        t.setMotivo("Tasa general");
        return t;
    }

    private static Presentacion presentacion(UUID id) {
        Presentacion p = new Presentacion();
        p.setId(id);
        p.setNombre("Presentacion " + id.toString().substring(0, 4));
        return p;
    }

    private static CambioCalculoIgv cambio(boolean habilitado) {
        CambioCalculoIgv c = new CambioCalculoIgv();
        c.setHabilitado(habilitado);
        c.setMotivo("Decision del administrador");
        return c;
    }

    private static AfectacionIgvPresentacion afectacion(UUID presentacionId, AfectacionIgv valor, LocalDate desde) {
        AfectacionIgvPresentacion a = new AfectacionIgvPresentacion();
        a.setPresentacion(presentacion(presentacionId));
        a.setAfectacion(valor);
        a.setVigenteDesde(desde);
        a.setMotivo("Previo");
        return a;
    }

    // ------------------------------------------------------------------ el interruptor

    @Test
    void sinNingunaDecisionEnElPanelRigeElValorPorDefecto() {
        assertThat(servicio(false).calculoHabilitado()).isFalse();
        assertThat(servicio(true).calculoHabilitado()).isTrue();
    }

    @Test
    void laDecisionDelPanelMandaSobreElValorPorDefecto() {
        ultimo.set(cambio(true));
        assertThat(servicio(false).calculoHabilitado()).isTrue();
        ultimo.set(cambio(false));
        assertThat(servicio(true).calculoHabilitado()).isFalse();
    }

    @Test
    void activarDesdeElPanelRegistraMotivoYSeNotaAlInstanteEnLasVentasNuevas() {
        UUID id = presentacionId;
        when(afectacionRepository.findFirstByPresentacionIdAndVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(eq(id), any()))
                .thenReturn(Optional.of(afectacion(id, AfectacionIgv.GRAVADO, HOY.minusDays(1))));
        ConfiguracionIgvService s = servicio(false);
        assertThat(s.desglosar(id, HOY, new BigDecimal("118.00"))).isNull(); // apagado: como siempre

        EstadoIgvResponse estado = s.cambiarCalculo(true, "  El administrador decidió desglosar el IGV  ");

        ArgumentCaptor<CambioCalculoIgv> guardado = ArgumentCaptor.forClass(CambioCalculoIgv.class);
        verify(cambioRepository).save(guardado.capture());
        assertThat(guardado.getValue().isHabilitado()).isTrue();
        assertThat(guardado.getValue().getMotivo()).isEqualTo("El administrador decidió desglosar el IGV"); // sin espacios sobrantes
        assertThat(estado.calculoHabilitado()).isTrue();
        assertThat(estado.estado()).isEqualTo("ACTIVO");
        assertThat(estado.ultimoCambio()).isNotNull();
        assertThat(estado.ultimoCambio().habilitado()).isTrue();
        // la venta nueva ya desglosa: 118.00 con 18 % => valor 100.00 + IGV 18.00 (el precio no cambia)
        var desglose = s.desglosar(id, HOY, new BigDecimal("118.00"));
        assertThat(desglose).isNotNull();
        assertThat(desglose.valorVenta()).isEqualByComparingTo("100.00");
        assertThat(desglose.igv()).isEqualByComparingTo("18.00");
    }

    @Test
    void desactivarDeNuevoVuelveAVentasSinDesglose() {
        ConfiguracionIgvService s = servicio(false);
        s.cambiarCalculo(true, "Encender");
        assertThat(s.calculoHabilitado()).isTrue();
        EstadoIgvResponse estado = s.cambiarCalculo(false, "Apagar");
        assertThat(estado.calculoHabilitado()).isFalse();
        assertThat(estado.estado()).isEqualTo("PENDIENTE");
        assertThat(estado.avisos()).anyMatch(a -> a.contains("desactivado") && a.contains("administrador"));
        assertThat(s.gravadasConTasa(List.of(presentacionId), HOY)).isEmpty();
    }

    @Test
    void pedirElEstadoEnElQueYaEstaEsUnConflictoYNoRegistraNada() {
        ConfiguracionIgvService s = servicio(false);
        assertThatThrownBy(() -> s.cambiarCalculo(false, "Ya estaba apagado")).isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("desactivado");
        ultimo.set(cambio(true));
        assertThatThrownBy(() -> servicio(false).cambiarCalculo(true, "Ya estaba encendido")).isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("activado");
        verify(cambioRepository, never()).save(any());
    }

    @Test
    void elMotivoEsObligatorioParaCambiarElInterruptor() {
        assertThatThrownBy(() -> servicio(false).cambiarCalculo(true, "   ")).isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("motivo");
        verify(cambioRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ clasificacion en lote

    private AfectacionLoteRequest lote(String afectacion, boolean soloSinDefinir) {
        return new AfectacionLoteRequest(afectacion, HOY, "Todos los productos están gravados", soloSinDefinir);
    }

    @Test
    void elLoteSoloCompletaLoQueFaltaYNuncaPisaUnaDecisionYaTomada() {
        UUID sinDefinir = UUID.randomUUID(), gravada = UUID.randomUUID(), exonerada = UUID.randomUUID();
        when(presentacionRepository.idsActivas()).thenReturn(List.of(sinDefinir, gravada, exonerada));
        when(afectacionRepository.findByPresentacionIdIn(any())).thenReturn(new ArrayList<>(List.of(
                afectacion(gravada, AfectacionIgv.GRAVADO, HOY.minusDays(5)), afectacion(exonerada, AfectacionIgv.EXONERADO, HOY.minusDays(5)))));

        AfectacionLoteResponse r = servicio(false).registrarAfectacionEnLote(lote("gravado", true));

        assertThat(r.aplicadas()).isEqualTo(1);
        assertThat(r.omitidas()).isEqualTo(2);
        ArgumentCaptor<AfectacionIgvPresentacion> guardada = ArgumentCaptor.forClass(AfectacionIgvPresentacion.class);
        verify(afectacionRepository).save(guardada.capture());
        assertThat(guardada.getValue().getPresentacion().getId()).isEqualTo(sinDefinir);
        assertThat(guardada.getValue().getAfectacion()).isEqualTo(AfectacionIgv.GRAVADO);
        assertThat(guardada.getValue().getVigenteDesde()).isEqualTo(HOY);
        assertThat(guardada.getValue().getMotivo()).isEqualTo("Todos los productos están gravados");
    }

    @Test
    void conSoloSinDefinirFalsoTambienCambiaLasYaClasificadasPeroNoLasQueYaEstanComoSePide() {
        UUID sinDefinir = UUID.randomUUID(), gravada = UUID.randomUUID(), exonerada = UUID.randomUUID();
        when(presentacionRepository.idsActivas()).thenReturn(List.of(sinDefinir, gravada, exonerada));
        when(afectacionRepository.findByPresentacionIdIn(any())).thenReturn(new ArrayList<>(List.of(
                afectacion(gravada, AfectacionIgv.GRAVADO, HOY.minusDays(5)), afectacion(exonerada, AfectacionIgv.EXONERADO, HOY.minusDays(5)))));

        AfectacionLoteResponse r = servicio(false).registrarAfectacionEnLote(lote("GRAVADO", false));

        assertThat(r.aplicadas()).isEqualTo(2); // la sin definir y la exonerada
        assertThat(r.omitidas()).isEqualTo(1);  // la que ya era gravada
    }

    @Test
    void unaPresentacionConUnRegistroDeEsaMismaFechaSeOmiteEnVezDeFallar() {
        UUID a = UUID.randomUUID();
        when(presentacionRepository.idsActivas()).thenReturn(List.of(a));
        when(afectacionRepository.findByPresentacionIdIn(any())).thenReturn(new ArrayList<>(List.of(afectacion(a, AfectacionIgv.SIN_DEFINIR, HOY))));

        AfectacionLoteResponse r = servicio(false).registrarAfectacionEnLote(lote("GRAVADO", true));

        assertThat(r.aplicadas()).isZero();
        assertThat(r.omitidas()).isEqualTo(1);
        verify(afectacionRepository, never()).save(any());
    }

    @Test
    void elLoteRechazaSinDefinirUnaAfectacionDesconocidaYUnMotivoVacio() {
        when(presentacionRepository.idsActivas()).thenReturn(List.of());
        ConfiguracionIgvService s = servicio(false);
        assertThatThrownBy(() -> s.registrarAfectacionEnLote(lote("SIN_DEFINIR", true))).isInstanceOf(ValidacionNegocioException.class);
        assertThatThrownBy(() -> s.registrarAfectacionEnLote(lote("TALVEZ", true))).isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("TALVEZ");
        assertThatThrownBy(() -> s.registrarAfectacionEnLote(new AfectacionLoteRequest("GRAVADO", HOY, "  ", true)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("motivo");
        verify(afectacionRepository, never()).save(any());
    }
}
