package com.planta.acopio.dto;

// Bloque "Token offline" (confirmado): refreshToken/refreshExpiraEnSegundos
// son nuevos, aditivos -- el resto de campos no cambia. Se emiten en login()
// y en refrescarConRefreshToken() (rotacion); el /refresh viejo (access
// token todavia vigente) sigue sin tocar refresh tokens -- devuelve
// refreshToken=null a proposito, ver AuthService.
public record LoginResponse(
    String token,
    String rol,
    String nombre,
    long expiraEnSegundos,
    String refreshToken,
    long refreshExpiraEnSegundos
) {}
