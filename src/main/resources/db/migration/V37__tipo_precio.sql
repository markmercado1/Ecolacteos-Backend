-- Tipos de precio administrables, etapa 1 (aditiva): un catalogo persistente de los tres tipos que ya existen y el registro de cada
-- activacion o desactivacion. NO toca precio_queso, precio_presentacion, venta ni cliente: sus valores y CHECK siguen igual, asi que
-- ningun dato historico cambia. El significado de cada tipo sigue en el servidor (MAYORISTA exige cliente habilitado, PROVEEDOR un proveedor
-- del padron, PUBLICO alimenta el catalogo publico y las reservas). PUBLICO queda protegido: no se puede desactivar.
CREATE TABLE tipo_precio (
    codigo      VARCHAR(15)  PRIMARY KEY CHECK (codigo IN ('MAYORISTA', 'PROVEEDOR', 'PUBLICO')),
    nombre      VARCHAR(60)  NOT NULL CHECK (length(btrim(nombre)) > 0),
    descripcion VARCHAR(300) NOT NULL,
    activo      BOOLEAN      NOT NULL DEFAULT TRUE,
    protegido   BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_tipo_precio_protegido_activo CHECK (NOT protegido OR activo)
);

INSERT INTO tipo_precio (codigo, nombre, descripcion, activo, protegido) VALUES
    ('MAYORISTA', 'Mayorista', 'Venta a un cliente habilitado como mayorista por el administrador.', TRUE, FALSE),
    ('PROVEEDOR', 'Proveedor', 'Venta a un proveedor del padron.', TRUE, FALSE),
    ('PUBLICO',   'Público',   'Precio publico: catalogo de la web, reservas y retiros. No se puede desactivar.', TRUE, TRUE);

-- Append-only: quien, cuando y por que se activo o desactivo cada tipo.
CREATE TABLE cambio_tipo_precio (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    codigo      VARCHAR(15) NOT NULL REFERENCES tipo_precio(codigo),
    activo      BOOLEAN     NOT NULL,
    motivo      TEXT        NOT NULL CHECK (length(btrim(motivo)) > 0),
    usuario_id  UUID        REFERENCES usuario(id),
    creado_en   TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_cambio_tipo_precio_codigo ON cambio_tipo_precio (codigo, creado_en DESC);
