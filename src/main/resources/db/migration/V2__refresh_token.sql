-- Bloque "Token offline" (confirmado): par access+refresh token, patron
-- estandar compatible con el plugin Auth de Ktor (bearer { loadTokens {...}
-- refreshTokens {...} }) del cliente KMP. El access token (JWT, 8h) no
-- cambia. Este refresh token es un valor aleatorio opaco, ROTATIVO (cada uso
-- valido invalida el anterior y emite uno nuevo), guardado SOLO como hash
-- SHA-256 (igual que una contrasena -- una fuga de BD no expone tokens
-- utilizables directamente).
CREATE TABLE refresh_token (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    usuario_id UUID NOT NULL REFERENCES usuario(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expira_en TIMESTAMP NOT NULL,
    revocado BOOLEAN NOT NULL DEFAULT FALSE,
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_token_usuario ON refresh_token(usuario_id);
