package com.planta.acopio.repository;

import com.planta.acopio.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // Bloque "Token offline" (confirmado): revoca todos los refresh tokens
    // vigentes de un usuario -- usado al cambiar contraseña y al desactivar
    // un usuario, para que credenciales/sesiones viejas no sigan siendo
    // validas mas alla del access token que ya vencio.
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revocado = true WHERE r.usuario.id = :usuarioId AND r.revocado = false")
    void revocarTodosDeUsuario(@Param("usuarioId") UUID usuarioId);
}
