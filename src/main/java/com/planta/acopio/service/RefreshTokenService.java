package com.planta.acopio.service;

import com.planta.acopio.entity.RefreshToken;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.RefreshTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

// Bloque "Token offline" (confirmado): par access+refresh token -- el access
// token (JWT, 8h, sin cambios) sigue siendo lo que se manda en cada request.
// Este refresh token es un valor aleatorio opaco de larga duracion (dias),
// pensado para que la app movil pueda recuperar una sesion utilizable
// despues de varios dias sin conexion, sin volver a pedir la contrasena.
// ROTATIVO: cada uso valido invalida el token usado y emite uno nuevo --
// limita el impacto de un refresh token filtrado (el patron natural del
// plugin Auth de Ktor, que ya espera reemplazar el par completo en cada
// refresh). Se guarda SOLO el hash SHA-256, nunca el valor en claro.
@Service
public class RefreshTokenService {

    private final RefreshTokenRepository repository;
    private final long diasValidez;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository,
                                @Value("${app.security.jwt.refresh-token-dias:30}") long diasValidez) {
        this.repository = repository;
        this.diasValidez = diasValidez;
    }

    @Transactional
    public String generar(Usuario usuario) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String tokenPlano = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken entidad = new RefreshToken();
        entidad.setUsuario(usuario);
        entidad.setTokenHash(hash(tokenPlano));
        entidad.setExpiraEn(LocalDateTime.now().plusDays(diasValidez));
        repository.save(entidad);

        return tokenPlano;
    }

    // Valida el token y lo ROTA: si es valido (existe, no revocado, no
    // vencido), lo marca revocado y devuelve el Usuario -- el llamador debe
    // generar uno nuevo para el siguiente ciclo.
    @Transactional
    public Usuario validarYRotar(String tokenPlano) {
        RefreshToken entidad = repository.findByTokenHash(hash(tokenPlano))
                .filter(t -> !Boolean.TRUE.equals(t.getRevocado()))
                .filter(t -> t.getExpiraEn().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new ValidacionNegocioException(
                        "Refresh token invalido, expirado o ya utilizado -- inicie sesion nuevamente"));

        entidad.setRevocado(true);
        return entidad.getUsuario();
    }

    // Bloque "Token offline" (confirmado): invalida sesiones offline viejas
    // al cambiar contraseña o al desactivar un usuario -- ver AuthService.
    // cambiarPassword() y UsuarioService.desactivar().
    @Transactional
    public void revocarTodosDeUsuario(UUID usuarioId) {
        repository.revocarTodosDeUsuario(usuarioId);
    }

    public long getDiasValidez() {
        return diasValidez;
    }

    private String hash(String valor) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(valor.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
