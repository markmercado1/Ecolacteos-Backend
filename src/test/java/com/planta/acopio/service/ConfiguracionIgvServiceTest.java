package com.planta.acopio.service;

import com.planta.acopio.dto.ConfiguracionIgvDtos.AfectacionIgvRequest;
import com.planta.acopio.dto.ConfiguracionIgvDtos.TasaIgvRequest;
import com.planta.acopio.entity.AfectacionIgv;
import com.planta.acopio.entity.AfectacionIgvPresentacion;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.TasaIgv;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.AfectacionIgvRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.TasaIgvRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Fase G: con el calculo APAGADO (por defecto) nada se desglosa aunque haya configuracion; encendido, solo lo clasificado y
// con tasa vigente a la fecha de la venta. La configuracion es append-only, con motivo.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConfiguracionIgvServiceTest {

    @Mock private TasaIgvRepository tasaRepository;
    @Mock private AfectacionIgvRepository afectacionRepository;
    @Mock private PresentacionRepository presentacionRepository;
    @Mock private UsuarioRepository usuarioRepository;

    // 2026-10-24 en Lima
    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-24T15:00:00Z"), ZoneId.of("America/Lima"));
    private static final LocalDate HOY = LocalDate.of(2026, 10, 24);

    private final UUID presentacionId = UUID.randomUUID();
    private Presentacion presentacion;

    @BeforeEach
    void setUp() {
        presentacion = new Presentacion();
        presentacion.setId(presentacionId);
        presentacion.setNombre("Queso fresco 1 kg");
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacion));
        when(presentacionRepository.existsById(presentacionId)).thenReturn(true);
        when(tasaRepository.save(any(TasaIgv.class))).thenAnswer(inv -> inv.getArgument(0));
        when(afectacionRepository.save(any(AfectacionIgvPresentacion.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ConfiguracionIgvService servicio(boolean habilitado) {
        return new ConfiguracionIgvService(tasaRepository, afectacionRepository, presentacionRepository, usuarioRepository, habilitado, RELOJ);
    }

    private AfectacionIgvPresentacion afectacion(AfectacionIgv valor, LocalDate desde) {
        AfectacionIgvPresentacion a = new AfectacionIgvPresentacion();
        a.setPresentacion(presentacion);
        a.setAfectacion(valor);
        a.setVigenteDesde(desde);
        a.setMotivo("Confirmado por el contador");
        return a;
    }

    private TasaIgv tasa(String valor, LocalDate desde) {
        TasaIgv t = new TasaIgv();
        t.setTasa(new BigDecimal(valor));
        t.setVigenteDesde(desde);
        t.setMotivo("Tasa general");
        return t;
    }

    private void configurado(AfectacionIgv valor) {
        when(afectacionRepository.findFirstByPresentacionIdAndVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(eq(presentacionId), any()))
                .thenReturn(Optional.of(afectacion(valor, HOY.minusDays(10))));
        when(tasaRepository.findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(any())).thenReturn(Optional.of(tasa("18.00", HOY.minusDays(10))));
    }

    // ------------------------------------------------------------------ calculo

    @Test
    void conElCalculoApagadoNadaSeDesglosaAunqueHayaConfiguracion() {
        configurado(AfectacionIgv.GRAVADO);
        ConfiguracionIgvService s = servicio(false);
        assertThat(s.desglosar(presentacionId, HOY, new BigDecimal("11.80"))).isNull();
        assertThat(s.gravadasConTasa(List.of(presentacionId), HOY)).isEmpty();
        verifyNoInteractions(afectacionRepository);
    }

    @Test
    void encendidoUnaPresentacionGravadaConTasaVigenteDesglosaDentroDelPrecio() {
        configurado(AfectacionIgv.GRAVADO);
        var d = servicio(true).desglosar(presentacionId, HOY, new BigDecimal("23.60"));
        assertThat(d.afectacion()).isEqualTo(AfectacionIgv.GRAVADO);
        assertThat(d.valorVenta()).isEqualByComparingTo("20.00");
        assertThat(d.igv()).isEqualByComparingTo("3.60");
    }

    @Test
    void encendidoLoSinClasificarOGravadoSinTasaQuedaSinDeterminar() {
        ConfiguracionIgvService s = servicio(true);
        when(afectacionRepository.findFirstByPresentacionIdAndVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(eq(presentacionId), any()))
                .thenReturn(Optional.empty());
        assertThat(s.desglosar(presentacionId, HOY, BigDecimal.TEN)).isNull();

        when(afectacionRepository.findFirstByPresentacionIdAndVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(eq(presentacionId), any()))
                .thenReturn(Optional.of(afectacion(AfectacionIgv.SIN_DEFINIR, HOY)));
        assertThat(s.desglosar(presentacionId, HOY, BigDecimal.TEN)).isNull();

        when(afectacionRepository.findFirstByPresentacionIdAndVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(eq(presentacionId), any()))
                .thenReturn(Optional.of(afectacion(AfectacionIgv.GRAVADO, HOY)));
        when(tasaRepository.findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(any())).thenReturn(Optional.empty());
        assertThat(s.desglosar(presentacionId, HOY, BigDecimal.TEN)).isNull();
    }

    @Test
    void encendidoExoneradoOInafectoRegistranIgvCero() {
        configurado(AfectacionIgv.EXONERADO);
        var d = servicio(true).desglosar(presentacionId, HOY, new BigDecimal("15.00"));
        assertThat(d.afectacion()).isEqualTo(AfectacionIgv.EXONERADO);
        assertThat(d.igv()).isEqualByComparingTo("0");
        assertThat(d.valorVenta()).isEqualByComparingTo("15.00");
    }

    @Test
    void laAfectacionYLaTasaSeResuelvenALaFechaDeLaVentaNoHoy() {
        ConfiguracionIgvService s = servicio(true);
        LocalDate ayer = HOY.minusDays(1);
        s.desglosar(presentacionId, ayer, BigDecimal.TEN);
        verify(afectacionRepository).findFirstByPresentacionIdAndVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(presentacionId, ayer);
    }

    @Test
    void incluyeIgvSoloLoGravadoVigenteHoyYConTasa() {
        UUID otra = UUID.randomUUID();
        Presentacion p2 = new Presentacion();
        p2.setId(otra);
        AfectacionIgvPresentacion exonerada = afectacion(AfectacionIgv.EXONERADO, HOY.minusDays(3));
        exonerada.setPresentacion(p2);
        when(tasaRepository.findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(HOY)).thenReturn(Optional.of(tasa("18", HOY.minusDays(30))));
        when(afectacionRepository.findByPresentacionIdIn(any())).thenReturn(List.of(
                afectacion(AfectacionIgv.GRAVADO, HOY.minusDays(5)),
                afectacion(AfectacionIgv.SIN_DEFINIR, HOY.plusDays(2)), // programada: todavia no cuenta
                exonerada));
        assertThat(servicio(true).gravadasConTasa(List.of(presentacionId, otra), HOY)).containsExactly(presentacionId);

        when(tasaRepository.findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(HOY)).thenReturn(Optional.empty());
        assertThat(servicio(true).gravadasConTasa(List.of(presentacionId, otra), HOY)).isEmpty();
    }

    // ------------------------------------------------------------------ configuracion

    @Test
    void registrarUnaTasaExigeRangoMotivoYUnaFechaLibre() {
        ConfiguracionIgvService s = servicio(false);
        assertThatThrownBy(() -> s.registrarTasa(new TasaIgvRequest(BigDecimal.ZERO, HOY, "x"))).isInstanceOf(ValidacionNegocioException.class);
        assertThatThrownBy(() -> s.registrarTasa(new TasaIgvRequest(new BigDecimal("100"), HOY, "x"))).isInstanceOf(ValidacionNegocioException.class);
        assertThatThrownBy(() -> s.registrarTasa(new TasaIgvRequest(new BigDecimal("18.005"), HOY, "x"))).isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("2 decimales");
        assertThatThrownBy(() -> s.registrarTasa(new TasaIgvRequest(new BigDecimal("18"), HOY, "   "))).isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("motivo");
        when(tasaRepository.existsByVigenteDesde(HOY)).thenReturn(true);
        assertThatThrownBy(() -> s.registrarTasa(new TasaIgvRequest(new BigDecimal("18"), HOY, "Tasa general"))).isInstanceOf(ConflictoNegocioException.class);
        verify(tasaRepository, never()).save(any());

        when(tasaRepository.existsByVigenteDesde(HOY)).thenReturn(false);
        var r = s.registrarTasa(new TasaIgvRequest(new BigDecimal("18"), HOY, " Confirmada por el contador "));
        assertThat(r.tasa()).isEqualByComparingTo("18.00");
        assertThat(r.motivo()).isEqualTo("Confirmada por el contador");
    }

    @Test
    void registrarUnaAfectacionValidaElValorYNoRepiteFecha() {
        ConfiguracionIgvService s = servicio(false);
        assertThatThrownBy(() -> s.registrarAfectacion(presentacionId, new AfectacionIgvRequest("EXENTO", HOY, "x")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("Afectación no válida");
        when(afectacionRepository.existsByPresentacionIdAndVigenteDesde(presentacionId, HOY)).thenReturn(true);
        assertThatThrownBy(() -> s.registrarAfectacion(presentacionId, new AfectacionIgvRequest("GRAVADO", HOY, "x")))
                .isInstanceOf(ConflictoNegocioException.class);

        when(afectacionRepository.existsByPresentacionIdAndVigenteDesde(presentacionId, HOY)).thenReturn(false);
        var r = s.registrarAfectacion(presentacionId, new AfectacionIgvRequest("gravado", HOY, "Contador: queso gravado"));
        assertThat(r.afectacion()).isEqualTo("GRAVADO");
        assertThat(r.presentacionId()).isEqualTo(presentacionId);
    }

    @Test
    void elEstadoQuedaPendienteMientrasElCalculoEsteApagadoYCuentaLoSinDefinir() {
        UUID otra = UUID.randomUUID();
        when(presentacionRepository.idsActivas()).thenReturn(List.of(presentacionId, otra));
        when(afectacionRepository.findByPresentacionIdIn(any())).thenReturn(List.of(afectacion(AfectacionIgv.GRAVADO, HOY.minusDays(1))));
        when(tasaRepository.findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(HOY)).thenReturn(Optional.empty());
        when(tasaRepository.findFirstByVigenteDesdeAfterOrderByVigenteDesdeAsc(HOY)).thenReturn(Optional.empty());

        var e = servicio(false).estado();
        assertThat(e.estado()).isEqualTo("PENDIENTE");
        assertThat(e.calculoHabilitado()).isFalse();
        assertThat(e.presentacionesActivas()).isEqualTo(2);
        assertThat(e.gravadas()).isEqualTo(1);
        assertThat(e.sinDefinir()).isEqualTo(1);
        assertThat(e.avisos()).anyMatch(a -> a.contains("cálculo del IGV está desactivado"))
                .anyMatch(a -> a.contains("No hay una tasa"))
                .anyMatch(a -> a.contains("1 presentación activa no tiene"))
                .anyMatch(a -> a.contains("gravadas pero ninguna tasa vigente"));

        assertThat(servicio(true).estado().estado()).isEqualTo("ACTIVO");
    }
}
