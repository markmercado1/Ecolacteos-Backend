-- Tipos de precio dinamicos, etapa 2. Los tres tipos originales (MAYORISTA, PROVEEDOR, PUBLICO) conservan su codigo, sus precios, sus ventas y
-- sus relaciones; el codigo es el identificador estable y NUNCA cambia (se edita el nombre, no el codigo). Los precios, las ventas y el canal
-- habitual de cada cliente pasan a referenciar el catalogo con clave foranea en lugar de un CHECK de tres valores. Cada tipo declara quien puede
-- comprar a ese precio (regla_comprador); la habilitacion de un cliente para un tipo vive en cliente_tipo_precio, unica fuente de verdad.
-- No toca precios ni ventas. Las columnas cliente.mayorista* quedan sin uso (la habilitacion se migra aqui) y se retiraran en otra migracion.

ALTER TABLE tipo_precio ADD COLUMN regla_comprador VARCHAR(20) NOT NULL DEFAULT 'CUALQUIERA';
ALTER TABLE tipo_precio ADD CONSTRAINT ck_tipo_precio_regla CHECK (regla_comprador IN ('CUALQUIERA', 'CLIENTE_HABILITADO', 'PROVEEDOR_PADRON'));
UPDATE tipo_precio SET regla_comprador = 'CLIENTE_HABILITADO' WHERE codigo = 'MAYORISTA';
UPDATE tipo_precio SET regla_comprador = 'PROVEEDOR_PADRON'   WHERE codigo = 'PROVEEDOR';

ALTER TABLE tipo_precio DROP CONSTRAINT tipo_precio_codigo_check;
ALTER TABLE tipo_precio ADD CONSTRAINT ck_tipo_precio_codigo_forma CHECK (codigo ~ '^[A-Z][A-Z0-9_]{1,14}$');
ALTER TABLE tipo_precio ADD COLUMN creado_en TIMESTAMP NOT NULL DEFAULT now();

-- La auditoria existente tambien registra la creacion y la edicion de datos.
ALTER TABLE cambio_tipo_precio ADD COLUMN accion VARCHAR(10) NOT NULL DEFAULT 'ESTADO';
ALTER TABLE cambio_tipo_precio ADD CONSTRAINT ck_cambio_tipo_precio_accion CHECK (accion IN ('CREACION', 'EDICION', 'ESTADO'));

-- Precios, ventas y canal habitual: del CHECK de tres valores a la clave foranea del catalogo (mismos valores, mismos datos).
ALTER TABLE precio_presentacion DROP CONSTRAINT chk_precio_presentacion_modalidad;
ALTER TABLE precio_presentacion ADD CONSTRAINT fk_precio_presentacion_tipo FOREIGN KEY (modalidad) REFERENCES tipo_precio (codigo);

ALTER TABLE venta DROP CONSTRAINT venta_tipo_cliente_check;
ALTER TABLE venta ADD CONSTRAINT fk_venta_tipo_precio FOREIGN KEY (tipo_cliente) REFERENCES tipo_precio (codigo);

ALTER TABLE cliente DROP CONSTRAINT ck_cliente_canal_mayorista;   -- pasa a la validacion del servidor: el tipo habitual exige la habilitacion del cliente
ALTER TABLE cliente DROP CONSTRAINT ck_cliente_canal_habitual;
ALTER TABLE cliente ADD CONSTRAINT fk_cliente_canal_habitual FOREIGN KEY (canal_habitual) REFERENCES tipo_precio (codigo);

-- Habilitacion de un cliente para un tipo cuya regla es CLIENTE_HABILITADO: quien, cuando y por que.
CREATE TABLE cliente_tipo_precio (
    cliente_id    UUID         NOT NULL REFERENCES cliente(id),
    tipo_codigo   VARCHAR(15)  NOT NULL REFERENCES tipo_precio(codigo),
    habilitado_por UUID        REFERENCES usuario(id),
    habilitado_en TIMESTAMP    NOT NULL DEFAULT now(),
    motivo        VARCHAR(300) NOT NULL CHECK (length(btrim(motivo)) > 0),
    PRIMARY KEY (cliente_id, tipo_codigo)
);
CREATE INDEX idx_cliente_tipo_precio_tipo ON cliente_tipo_precio (tipo_codigo);

-- Los clientes mayoristas actuales conservan su permiso, con la misma auditoria que ya tenian.
INSERT INTO cliente_tipo_precio (cliente_id, tipo_codigo, habilitado_por, habilitado_en, motivo)
SELECT id, 'MAYORISTA', mayorista_cambiado_por, COALESCE(mayorista_cambiado_en, now()),
       COALESCE(NULLIF(btrim(mayorista_motivo), ''), 'Habilitado como mayorista antes de los tipos de precio dinamicos')
FROM cliente WHERE mayorista;
