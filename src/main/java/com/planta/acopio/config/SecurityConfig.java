package com.planta.acopio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.dto.ErrorResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

// CORE_DESIGN.md §15: seguridad intencionalmente simple -- un solo filtro JWT
// + roles por endpoint via @PreAuthorize en cada Controller, sin OAuth2 externo
// ni microservicios.
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // habilita @PreAuthorize en los controllers
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final UsuarioDetailsService usuarioDetailsService;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, UsuarioDetailsService usuarioDetailsService) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.usuarioDetailsService = usuarioDetailsService;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(usuarioDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    // Segunda auditoria (flujos end-to-end §9/§14-F): sin este entry point,
    // un request sin JWT (o con uno invalido/expirado) es rechazado por
    // Spring Security ANTES del DispatcherServlet -- nunca llega a
    // GlobalExceptionHandler -- y devuelve una respuesta minima que no sigue
    // la misma forma {timestamp, status, error, mensaje} que el resto de la
    // API. Esto lo hace consistente para que Angular/Flutter puedan parsear
    // cualquier error con el mismo shape sin importar en que capa ocurrio.
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) -> {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ErrorResponse body = new ErrorResponse(
                    Instant.now().toString(), 401, "Unauthorized",
                    "Token de autenticacion ausente, invalido o expirado");
            objectMapper.writeValue(response.getWriter(), body);
        };
    }

    // Bloque 8 (confirmado): sin perfiles dev/prod en el proyecto -- origenes
    // configurables por properties/env (CORS_ALLOWED_ORIGINS), lista separada
    // por comas, nunca "*". allowCredentials=false a proposito: la API es
    // stateless via Bearer JWT (Authorization header), no usa cookies de
    // sesion -- no hace falta exponer credentials para que el header
    // Authorization funcione entre origenes.
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.security.cors.allowed-origins:http://localhost:4200}") String allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AuthenticationEntryPoint authenticationEntryPoint,
                                            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // API stateless consumida por Flutter/panel web, sin cookies de sesion
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint))
            .authorizeHttpRequests(auth -> auth
                // /login y /refresh-token quedan sin autenticar (el refresh
                // token del body ES la credencial en ese segundo caso --
                // bloque "Token offline", confirmado). /refresh (sin -token)
                // SI requiere un JWT todavia valido (lo lee del contexto de
                // seguridad que ya puebla JwtAuthFilter) -- segunda auditoria §11/§14-E.
                .requestMatchers("/api/auth/login", "/api/auth/refresh-token").permitAll()
                // Documentacion OpenAPI/Swagger UI (esta tarea): la UI necesita
                // cargar sin JWT para poder despues usar el boton "Authorize".
                // Fotos del catalogo (producto/presentacion): publicas a proposito, solo lectura. Es un directorio
                // propio (FotoCatalogoService), distinto del de las fotos de acopio, que siguen exigiendo rol.
                .requestMatchers(HttpMethod.GET, "/api/catalogo/fotos/**").permitAll()
                // Catalogo publico (Fase 3): solo lectura (GET), sin datos internos. Cualquier otro metodo o ruta bajo
                // /api/catalogo/ distinta de estas dos sigue exigiendo autenticacion.
                .requestMatchers(HttpMethod.GET, "/api/catalogo/productos").permitAll()
                // Reservas publicas (Fase 4): solo crear una y consultar la PROPIA (codigo + telefono). Nada mas bajo
                // /api/catalogo/reservas es publico; la gestion es de VENTAS/ADMIN en /api/reservas.
                .requestMatchers(HttpMethod.POST, "/api/catalogo/reservas", "/api/catalogo/reservas/consulta").permitAll()
                // Fase 6: reglas de reserva para la web publica (solo lectura, sin datos de clientes).
                .requestMatchers(HttpMethod.GET, "/api/catalogo/reservas/reglas").permitAll()
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated()
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
