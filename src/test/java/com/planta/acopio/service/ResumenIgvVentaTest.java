package com.planta.acopio.service;

import com.planta.acopio.entity.AfectacionIgv;
import com.planta.acopio.entity.VentaDetalle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Fase G: el resumen de IGV de una venta solo suma la foto tributaria de sus lineas; una linea sin foto queda "sin determinar".
class ResumenIgvVentaTest {

    private static VentaDetalle linea(AfectacionIgv afectacion, String tasa, String valor, String igv) {
        VentaDetalle d = new VentaDetalle();
        if (afectacion != null) {
            d.setDesgloseIgv(afectacion, tasa == null ? null : new BigDecimal(tasa), new BigDecimal(valor), new BigDecimal(igv));
        }
        return d;
    }

    @Test
    void ventasAnterioresOConElCalculoApagadoQuedanSinDeterminarConTodoElTotalPendiente() {
        var r = VentaService.resumenIgv(List.of(linea(null, null, null, null), linea(null, null, null, null)), new BigDecimal("30.00"));
        assertThat(r.estado()).isEqualTo("SIN_DETERMINAR");
        assertThat(r.incluyeIgv()).isFalse();
        assertThat(r.igvTotal()).isEqualByComparingTo("0");
        assertThat(r.montoSinDeterminar()).isEqualByComparingTo("30.00");
    }

    @Test
    void sumaPorAfectacionYMarcaParcialSiAlgunaLineaNoTieneFoto() {
        var r = VentaService.resumenIgv(List.of(
                linea(AfectacionIgv.GRAVADO, "18", "20.00", "3.60"),
                linea(AfectacionIgv.EXONERADO, null, "5.00", "0"),
                linea(AfectacionIgv.INAFECTO, null, "2.00", "0"),
                linea(null, null, null, null)), new BigDecimal("40.60"));
        assertThat(r.estado()).isEqualTo("PARCIAL");
        assertThat(r.incluyeIgv()).isTrue();
        assertThat(r.operacionesGravadas()).isEqualByComparingTo("20.00");
        assertThat(r.operacionesExoneradas()).isEqualByComparingTo("5.00");
        assertThat(r.operacionesInafectas()).isEqualByComparingTo("2.00");
        assertThat(r.igvTotal()).isEqualByComparingTo("3.60");
        assertThat(r.montoSinDeterminar()).isEqualByComparingTo("10.00");
    }

    @Test
    void todoDeterminadoSinNadaPendiente() {
        var r = VentaService.resumenIgv(List.of(linea(AfectacionIgv.EXONERADO, null, "12.00", "0")), new BigDecimal("12.00"));
        assertThat(r.estado()).isEqualTo("DETERMINADO");
        assertThat(r.incluyeIgv()).isFalse();
        assertThat(r.montoSinDeterminar()).isEqualByComparingTo("0");
    }
}
