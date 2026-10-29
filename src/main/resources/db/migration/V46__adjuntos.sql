-- Adjuntos genericos (foto o PDF del recibo, factura o guia). El archivo vive FUERA de la base de datos, en una carpeta
-- configurable (app.adjuntos.directorio / variable de entorno APP_ADJUNTOS_DIRECTORIO); aqui solo queda la referencia.
--
--   * Generico: entidad_tipo + entidad_id (sin FK a proposito: hoy solo DOCUMENTO_INGRESO; mañana, otras entidades).
--   * texto_extraido queda NULO: reservado para OCR a futuro (no se implementa OCR).
--   * Imagenes (JPEG, PNG, WEBP) y PDF, maximo 10 MB; el servidor valida el tipo por el contenido, no solo por la extension.
--   * El mismo archivo (sha256) no se adjunta dos veces al mismo documento.
--
-- Migracion ADITIVA. Reversa: DROP TABLE adjunto; (y borrar la fila 46 de flyway_schema_history; los archivos quedan en disco).

CREATE TABLE adjunto (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    entidad_tipo VARCHAR(30) NOT NULL CHECK (entidad_tipo IN ('DOCUMENTO_INGRESO')),
    entidad_id UUID NOT NULL,
    nombre_original VARCHAR(200) NOT NULL,
    content_type VARCHAR(60) NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp', 'application/pdf')),
    tamano_bytes BIGINT NOT NULL CHECK (tamano_bytes > 0 AND tamano_bytes <= 10485760),
    ruta_almacenamiento VARCHAR(300) NOT NULL UNIQUE,
    sha256 VARCHAR(64) NOT NULL,
    texto_extraido TEXT,
    subido_por UUID NOT NULL REFERENCES usuario(id),
    subido_en TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_adjunto_entidad ON adjunto (entidad_tipo, entidad_id);
CREATE UNIQUE INDEX uq_adjunto_entidad_sha ON adjunto (entidad_tipo, entidad_id, sha256);
