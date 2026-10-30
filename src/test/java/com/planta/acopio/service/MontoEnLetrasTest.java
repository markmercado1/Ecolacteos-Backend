package com.planta.acopio.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// "SON: ..." del comprobante: castellano, con apocope delante de "mil" y "millon".
class MontoEnLetrasTest {

    private static String soles(String monto) {
        return MontoEnLetras.soles(new BigDecimal(monto));
    }

    @Test
    void losEjemplosDelComprobante() {
        assertThat(soles("37.62")).isEqualTo("TREINTA Y SIETE CON 62/100 SOLES");
        assertThat(soles("1.80")).isEqualTo("UNO CON 80/100 SOLES");
    }

    @Test
    void ceroUnidadesYDecenas() {
        assertThat(soles("0")).isEqualTo("CERO CON 00/100 SOLES");
        assertThat(soles("0.05")).isEqualTo("CERO CON 05/100 SOLES");
        assertThat(soles("10")).isEqualTo("DIEZ CON 00/100 SOLES");
        assertThat(soles("16")).isEqualTo("DIECISEIS CON 00/100 SOLES");
        assertThat(soles("20")).isEqualTo("VEINTE CON 00/100 SOLES");
        assertThat(soles("21")).isEqualTo("VEINTIUNO CON 00/100 SOLES");
        assertThat(soles("29")).isEqualTo("VEINTINUEVE CON 00/100 SOLES");
        assertThat(soles("30")).isEqualTo("TREINTA CON 00/100 SOLES");
        assertThat(soles("31")).isEqualTo("TREINTA Y UNO CON 00/100 SOLES");
        assertThat(soles("99")).isEqualTo("NOVENTA Y NUEVE CON 00/100 SOLES");
    }

    @Test
    void centenasConCienExactoYCientoDespues() {
        assertThat(soles("100")).isEqualTo("CIEN CON 00/100 SOLES");
        assertThat(soles("101")).isEqualTo("CIENTO UNO CON 00/100 SOLES");
        assertThat(soles("115.50")).isEqualTo("CIENTO QUINCE CON 50/100 SOLES");
        assertThat(soles("500")).isEqualTo("QUINIENTOS CON 00/100 SOLES");
        assertThat(soles("999.99")).isEqualTo("NOVECIENTOS NOVENTA Y NUEVE CON 99/100 SOLES");
    }

    @Test
    void milesConApocope() {
        assertThat(soles("1000")).isEqualTo("MIL CON 00/100 SOLES");
        assertThat(soles("1001")).isEqualTo("MIL UNO CON 00/100 SOLES");
        assertThat(soles("2000")).isEqualTo("DOS MIL CON 00/100 SOLES");
        assertThat(soles("21000")).isEqualTo("VEINTIUN MIL CON 00/100 SOLES");
        assertThat(soles("31000")).isEqualTo("TREINTA Y UN MIL CON 00/100 SOLES");
        assertThat(soles("100000")).isEqualTo("CIEN MIL CON 00/100 SOLES");
        assertThat(soles("1234.56")).isEqualTo("MIL DOSCIENTOS TREINTA Y CUATRO CON 56/100 SOLES");
    }

    @Test
    void millones() {
        assertThat(soles("1000000")).isEqualTo("UN MILLÓN CON 00/100 SOLES");
        assertThat(soles("1000001")).isEqualTo("UN MILLÓN UNO CON 00/100 SOLES");
        assertThat(soles("2000000")).isEqualTo("DOS MILLONES CON 00/100 SOLES");
        assertThat(soles("21000000")).isEqualTo("VEINTIUN MILLONES CON 00/100 SOLES");
        assertThat(soles("1100000")).isEqualTo("UN MILLÓN CIEN MIL CON 00/100 SOLES");
        assertThat(soles("1500000.50")).isEqualTo("UN MILLÓN QUINIENTOS MIL CON 50/100 SOLES");
    }

    @Test
    void redondeaALosCentimosYRechazaNegativosYMontosEnormes() {
        assertThat(soles("37.625")).isEqualTo("TREINTA Y SIETE CON 63/100 SOLES");
        assertThat(soles("37.999")).isEqualTo("TREINTA Y OCHO CON 00/100 SOLES");
        assertThatThrownBy(() -> soles("-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MontoEnLetras.soles(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> soles("1000000000000")).isInstanceOf(IllegalArgumentException.class);
    }
}
