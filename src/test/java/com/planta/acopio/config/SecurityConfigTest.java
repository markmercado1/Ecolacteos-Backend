package com.planta.acopio.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

// Bloque 8 (confirmado): CORS restrictivo, sin "*", origenes configurables
// por properties/env (CORS_ALLOWED_ORIGINS) -- sin perfiles dev/prod en el
// proyecto. corsConfigurationSource() es una funcion pura del parametro
// allowedOrigins, se prueba directamente sin levantar contexto de Spring.
class SecurityConfigTest {

    // jwtAuthFilter/usuarioDetailsService no los usa este metodo -- null es seguro aqui.
    private final SecurityConfig securityConfig = new SecurityConfig(null, null);

    @Test
    void parseaOrigenesSeparadosPorComasYLosRecorta() {
        var source = securityConfig.corsConfigurationSource("http://localhost:4200, https://panel.ecolacteos.pe");

        CorsConfiguration resuelta = source.getCorsConfiguration(peticionA("/api/proveedores"));

        assertThat(resuelta).isNotNull();
        assertThat(resuelta.getAllowedOrigins()).containsExactlyInAnyOrder(
                "http://localhost:4200", "https://panel.ecolacteos.pe");
    }

    @Test
    void nuncaPermiteWildcardYNoExponeCredentials() {
        var source = securityConfig.corsConfigurationSource("http://localhost:4200");

        CorsConfiguration resuelta = source.getCorsConfiguration(peticionA("/api/proveedores"));

        assertThat(resuelta.getAllowedOrigins()).doesNotContain("*");
        assertThat(resuelta.getAllowCredentials()).isFalse();
    }

    @Test
    void soloAplicaBajoApiNoATodaLaAplicacion() {
        var source = securityConfig.corsConfigurationSource("http://localhost:4200");

        assertThat(source.getCorsConfiguration(peticionA("/swagger-ui.html"))).isNull();
    }

    private MockHttpServletRequest peticionA(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }
}
