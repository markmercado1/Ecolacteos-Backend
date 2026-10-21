package com.planta.acopio.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

// Fase 4: telefono movil peruano. Todo formato valido debe normalizar al MISMO valor (+519XXXXXXXX), para que el limite de
// reservas pendientes por telefono no se pueda eludir cambiando separadores o prefijos.
class TelefonoPeruTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "987654321", "987 654 321", "987-654-321", "  987654321  ", "(987) 654-321",
            "51987654321", "51 987 654 321", "51-987-654-321",
            "+51987654321", "+51 987 654 321", "+51-987-654-321", "+51 987-654 321"})
    void todosLosFormatosValidosNormalizanAlMismoNumero(String crudo) {
        assertThat(TelefonoPeru.normalizar(crudo)).contains("+51987654321");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "87654321",        // 8 digitos
            "887654321",       // no empieza con 9
            "9876543210",      // 10 digitos
            "98765432",        // 8 digitos que empiezan con 9
            "+52987654321",    // otro pais
            "52987654321",
            "+5198765432",     // movil incompleto
            "5198765432",
            "+51 887654321",   // con prefijo pero no es movil
            "++51987654321",
            "98765432a",
            "abcdefghi",
            "987.654.321",     // el punto no es un separador admitido
            "987/654/321",
            "+51(987)654321x",
            "٩٨٧٦٥٤٣٢١"})      // digitos no ASCII
    void losNumerosQueNoCumplenSeRechazan(String crudo) {
        assertThat(TelefonoPeru.normalizar(crudo)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "-", "+", "+51", "51"})
    void vacioNuloOSoloPrefijoSeRechaza(String crudo) {
        assertThat(TelefonoPeru.normalizar(crudo)).isEmpty();
    }

    @Test
    void elValorNormalizadoSiempreTieneElFormatoDeLaBase() {
        assertThat(TelefonoPeru.normalizar("999 000 111").orElseThrow()).matches("\\+519[0-9]{8}");
    }
}
