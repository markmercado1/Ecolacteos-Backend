package com.planta.acopio.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.function.Function;

// JWT con expiracion corta (~8h) + los datos minimos para no requerir consulta
// a BD en cada request (CORE_DESIGN.md §15). Sin OAuth2 externo, intencional-
// mente simple.
@Component
public class JwtService {

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(
            @Value("${app.security.jwt.secret}") String secret,
            @Value("${app.security.jwt.expiration-minutes}") long expirationMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
    }

    public String generarToken(String email, String rolNombre, String usuarioId) {
        Date ahora = new Date();
        Date expira = new Date(ahora.getTime() + expirationMinutes * 60_000);
        return Jwts.builder()
                .subject(email)
                .claims(Map.of("rol", rolNombre, "usuarioId", usuarioId))
                .issuedAt(ahora)
                .expiration(expira)
                .signWith(key)
                .compact();
    }

    public String extraerEmail(String token) {
        return extraerClaim(token, Claims::getSubject);
    }

    public String extraerRol(String token) {
        return extraerClaim(token, c -> c.get("rol", String.class));
    }

    public boolean esValido(String token, String email) {
        return email.equals(extraerEmail(token)) && !estaExpirado(token);
    }

    public long getExpirationMinutes() {
        return expirationMinutes;
    }

    private boolean estaExpirado(String token) {
        return extraerClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extraerClaim(String token, Function<Claims, T> resolver) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return resolver.apply(claims);
    }
}
