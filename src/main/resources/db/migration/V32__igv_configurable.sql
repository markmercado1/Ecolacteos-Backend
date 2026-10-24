-- Fase G (IGV configurable en ventas). Migracion ADITIVA y NEUTRA: no inserta tasas ni clasifica productos, no toca precios,
-- ventas ni reservas existentes. Mientras el contador no confirme la configuracion, el calculo esta APAGADO
-- (app.tributario.igv.calculo-habilitado=false) y las ventas se registran exactamente igual que antes.
--
--   * Los precios son FINALES: lo publicado es lo que paga el cliente. El IGV nunca se suma; cuando corresponde se DESGLOSA
--     dentro del precio (valor de venta + IGV = subtotal de la linea).
--   * tasa_igv: serie con vigencia (como precio_litro). Sin filas = sin tasa configurada.
--   * afectacion_igv_presentacion: serie con vigencia por presentacion (GRAVADO, EXONERADO, INAFECTO o SIN_DEFINIR para
--     deshacer una clasificacion). Sin filas = SIN_DEFINIR. Append-only, con motivo y usuario.
--   * venta_detalle: foto tributaria de la linea al venderse. Todo NULL = sin determinar (ventas anteriores, calculo
--     apagado, presentacion sin clasificar o gravada sin tasa vigente). Nunca se recalcula.

CREATE TABLE tasa_igv (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tasa NUMERIC(5,2) NOT NULL,
    vigente_desde DATE NOT NULL UNIQUE,
    motivo TEXT NOT NULL,
    usuario_id UUID REFERENCES usuario(id),
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_tasa_igv_rango CHECK (tasa > 0 AND tasa < 100),
    CONSTRAINT ck_tasa_igv_motivo CHECK (btrim(motivo) <> '')
);

CREATE TABLE afectacion_igv_presentacion (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    presentacion_id UUID NOT NULL REFERENCES presentacion(id),
    afectacion VARCHAR(12) NOT NULL,
    vigente_desde DATE NOT NULL,
    motivo TEXT NOT NULL,
    usuario_id UUID REFERENCES usuario(id),
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_afectacion_igv_valor CHECK (afectacion IN ('SIN_DEFINIR', 'GRAVADO', 'EXONERADO', 'INAFECTO')),
    CONSTRAINT ck_afectacion_igv_motivo CHECK (btrim(motivo) <> ''),
    CONSTRAINT uq_afectacion_igv_vigencia UNIQUE (presentacion_id, vigente_desde)
);

ALTER TABLE venta_detalle ADD COLUMN afectacion_igv VARCHAR(12);
ALTER TABLE venta_detalle ADD COLUMN tasa_igv NUMERIC(5,2);
ALTER TABLE venta_detalle ADD COLUMN valor_venta NUMERIC(10,2);
ALTER TABLE venta_detalle ADD COLUMN igv NUMERIC(10,2);
-- sin determinar: todo NULL; gravada: tasa, valor e IGV que suman el subtotal; exonerada o inafecta: sin tasa, IGV 0 y valor = subtotal
ALTER TABLE venta_detalle ADD CONSTRAINT ck_venta_detalle_igv CHECK (
    (afectacion_igv IS NULL AND tasa_igv IS NULL AND valor_venta IS NULL AND igv IS NULL)
    OR (afectacion_igv = 'GRAVADO' AND tasa_igv IS NOT NULL AND valor_venta IS NOT NULL AND igv IS NOT NULL
        AND valor_venta + igv = round(cantidad * precio_unitario, 2))
    OR (afectacion_igv IN ('EXONERADO', 'INAFECTO') AND tasa_igv IS NULL AND igv = 0
        AND valor_venta = round(cantidad * precio_unitario, 2)));
