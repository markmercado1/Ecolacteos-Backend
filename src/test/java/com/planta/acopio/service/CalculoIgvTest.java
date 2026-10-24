package com.planta.acopio.service;

import com.planta.acopio.entity.AfectacionIgv;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

// Fase G: el IGV se desglosa DENTRO del precio final (nunca se suma): valor + IGV = lo cobrado, siempre.
class CalculoIgvTest {

    @Test
    void desglosaElIgvContenidoEnElPrecioFinalSinCambiarLoCobrado() {
        var d = CalculoIgv.gravado(new BigDecimal("11.80"), new BigDecimal("18.00"));
        assertThat(d.afectacion()).isEqualTo(AfectacionIgv.GRAVADO);
        assertThat(d.valorVenta()).isEqualByComparingTo("10.00");
        assertThat(d.igv()).isEqualByComparingTo("1.80");
        assertThat(d.tasa()).isEqualByComparingTo("18");
    }

    @Test
    void conRedondeoElValorMasElIgvSiguenSumandoExactamenteElSubtotal() {
        for (String subtotal : new String[]{"0.01", "1.00", "2.50", "9.99", "10.00", "15.50", "33.33", "100.00", "1234.57"}) {
            var d = CalculoIgv.gravado(new BigDecimal(subtotal), new BigDecimal("18"));
            assertThat(d.valorVenta().add(d.igv())).isEqualByComparingTo(subtotal);
            assertThat(d.valorVenta().scale()).isEqualTo(2);
            assertThat(d.igv().scale()).isEqualTo(2);
        }
        // 10.00 / 1.18 = 8.4745... -> 8.47 y 1.53
        var diez = CalculoIgv.gravado(new BigDecimal("10.00"), new BigDecimal("18"));
        assertThat(diez.valorVenta()).isEqualByComparingTo("8.47");
        assertThat(diez.igv()).isEqualByComparingTo("1.53");
    }

    @Test
    void exoneradoOInafectoNoTienenIgvYSuValorEsElSubtotal() {
        var d = CalculoIgv.sinIgv(AfectacionIgv.EXONERADO, new BigDecimal("12.5"));
        assertThat(d.tasa()).isNull();
        assertThat(d.igv()).isEqualByComparingTo("0");
        assertThat(d.valorVenta()).isEqualByComparingTo("12.50");
        assertThat(CalculoIgv.sinIgv(AfectacionIgv.INAFECTO, BigDecimal.TEN).afectacion()).isEqualTo(AfectacionIgv.INAFECTO);
    }
}
