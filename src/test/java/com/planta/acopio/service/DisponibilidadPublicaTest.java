package com.planta.acopio.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DisponibilidadPublicaTest {

    @Test
    void sinStockVendibleEsAgotadoYUnNegativoTambien() {
        assertThat(DisponibilidadPublica.de(0, 5)).isEqualTo(DisponibilidadPublica.AGOTADO);
        assertThat(DisponibilidadPublica.de(-3, 5)).isEqualTo(DisponibilidadPublica.AGOTADO);
    }

    @Test
    void desdeUnoHastaElUmbralInclusiveSonPocasUnidades() {
        assertThat(DisponibilidadPublica.de(1, 5)).isEqualTo(DisponibilidadPublica.POCAS_UNIDADES);
        assertThat(DisponibilidadPublica.de(5, 5)).isEqualTo(DisponibilidadPublica.POCAS_UNIDADES);
    }

    @Test
    void porEncimaDelUmbralEsDisponible() {
        assertThat(DisponibilidadPublica.de(6, 5)).isEqualTo(DisponibilidadPublica.DISPONIBLE);
        assertThat(DisponibilidadPublica.de(500, 5)).isEqualTo(DisponibilidadPublica.DISPONIBLE);
    }

    @Test
    void conUmbralCeroCualquierStockPositivoEsDisponible() {
        assertThat(DisponibilidadPublica.de(1, 0)).isEqualTo(DisponibilidadPublica.DISPONIBLE);
        assertThat(DisponibilidadPublica.de(0, 0)).isEqualTo(DisponibilidadPublica.AGOTADO);
    }
}
