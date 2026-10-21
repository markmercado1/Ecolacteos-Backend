package com.planta.acopio.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Bloque 10 (confirmado): logica de conteo pura, sin dependencia de tiempo
// real en estos tests (no se prueba la expiracion por ventana -- requeriria
// esperar minutos reales; se prueba el conteo y el reset, que es el
// comportamiento central).
class LoginAttemptServiceTest {

    private final LoginAttemptService service = new LoginAttemptService(3, 15);

    @Test
    void noBloqueaAntesDeAlcanzarElMaximoDeIntentos() {
        service.registrarFallo("acopiador@planta.com");
        service.registrarFallo("acopiador@planta.com");

        assertThat(service.estaBloqueado("acopiador@planta.com")).isFalse();
    }

    @Test
    void bloqueaAlAlcanzarElMaximoDeIntentosFallidosSeguidos() {
        service.registrarFallo("acopiador@planta.com");
        service.registrarFallo("acopiador@planta.com");
        service.registrarFallo("acopiador@planta.com");

        assertThat(service.estaBloqueado("acopiador@planta.com")).isTrue();
    }

    @Test
    void unLoginExitosoReseteaElContador() {
        service.registrarFallo("acopiador@planta.com");
        service.registrarFallo("acopiador@planta.com");
        service.registrarExito("acopiador@planta.com");
        service.registrarFallo("acopiador@planta.com");

        assertThat(service.estaBloqueado("acopiador@planta.com")).isFalse();
    }

    @Test
    void elBloqueoEsPorEmailNoAfectaAOtrosUsuarios() {
        service.registrarFallo("acopiador@planta.com");
        service.registrarFallo("acopiador@planta.com");
        service.registrarFallo("acopiador@planta.com");

        assertThat(service.estaBloqueado("otro@planta.com")).isFalse();
    }

    @Test
    void elEmailSeNormalizaAMinusculasParaElConteo() {
        service.registrarFallo("Acopiador@Planta.com");
        service.registrarFallo("acopiador@planta.com");
        service.registrarFallo("ACOPIADOR@PLANTA.COM");

        assertThat(service.estaBloqueado("acopiador@planta.com")).isTrue();
    }
}
