package com.planta.acopio.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Fase G: forma del DNI y del RUC (digito verificador modulo 11). 20131312955 es el RUC publicado de SUNAT.
class DocumentoIdentidadTest {

    @Test
    void elDniSonExactamenteOchoDigitos() {
        assertThat(DocumentoIdentidad.dniValido("04567890")).isTrue();
        assertThat(DocumentoIdentidad.dniValido("4567890")).isFalse();
        assertThat(DocumentoIdentidad.dniValido("045678901")).isFalse();
        assertThat(DocumentoIdentidad.dniValido("0456789A")).isFalse();
        assertThat(DocumentoIdentidad.dniValido(null)).isFalse();
    }

    @Test
    void elRucValidaSuDigitoVerificador() {
        assertThat(DocumentoIdentidad.rucValido("20131312955")).isTrue();
        assertThat(DocumentoIdentidad.rucValido("20131312954")).isFalse();
        assertThat(DocumentoIdentidad.rucValido("10045678909")).isTrue();
        assertThat(DocumentoIdentidad.rucValido("10045678900")).isFalse();
        assertThat(DocumentoIdentidad.rucValido("10876543210")).isTrue();
        assertThat(DocumentoIdentidad.rucValido("2013131295")).isFalse();
        assertThat(DocumentoIdentidad.rucValido("2013131295X")).isFalse();
        assertThat(DocumentoIdentidad.rucValido(null)).isFalse();
    }

    @Test
    void limpiarQuitaEspaciosGuionesYPuntosYDejaNullSiQuedaVacio() {
        assertThat(DocumentoIdentidad.limpiar(" 0456 7890 ")).isEqualTo("04567890");
        assertThat(DocumentoIdentidad.limpiar("10-04567890-9")).isEqualTo("10045678909");
        assertThat(DocumentoIdentidad.limpiar("   ")).isNull();
        assertThat(DocumentoIdentidad.limpiar(null)).isNull();
    }

    @Test
    void elDniDeUnRucDePersonaNaturalSonSusOchoDigitosCentrales() {
        assertThat(DocumentoIdentidad.dniDelRuc("10045678909")).isEqualTo("04567890");
        assertThat(DocumentoIdentidad.dniDelRuc("20601234565")).isNull();
        assertThat(DocumentoIdentidad.dniDelRuc(null)).isNull();
    }
}
