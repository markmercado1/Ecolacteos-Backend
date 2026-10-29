-- Documento de ingreso de insumos: cabecera (proveedor, tipo, numero, fecha) cuyas lineas son los LOTES que crea cada insumo
-- recibido (cada linea = un lote con su cantidad, codigo y vencimiento). Un ingreso puede tener varios insumos (una factura con
-- varios items) y los lotes saben de que documento y proveedor vinieron.
--
--   * documento_ingreso.uuid_cliente: idempotencia (reenviar el mismo ingreso no lo duplica).
--   * Unico (proveedor, tipo, numero): evita cargar dos veces la misma factura o guia del mismo proveedor.
--   * lote_insumo.proveedor_id / documento_ingreso_id: trazabilidad hacia atras de cada lote.
--
-- Migracion ADITIVA: los lotes existentes (INICIAL e ingresos anteriores) quedan sin proveedor ni documento.
-- Reversa: ALTER TABLE lote_insumo DROP COLUMN documento_ingreso_id, DROP COLUMN proveedor_id; DROP TABLE documento_ingreso;
--          (y borrar la fila 44 de flyway_schema_history).

CREATE TABLE documento_ingreso (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    proveedor_id UUID REFERENCES proveedor_compra(id),
    tipo VARCHAR(15) NOT NULL CHECK (tipo IN ('GUIA', 'FACTURA', 'BOLETA', 'OTRO', 'SIN_DOCUMENTO')),
    numero VARCHAR(40),
    fecha_documento DATE,
    observacion VARCHAR(500),
    usuario_id UUID NOT NULL REFERENCES usuario(id),
    uuid_cliente UUID UNIQUE,
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    -- Toda constancia documental lleva numero; sin documento no.
    CONSTRAINT ck_documento_ingreso_numero CHECK (tipo = 'SIN_DOCUMENTO' OR (numero IS NOT NULL AND btrim(numero) <> ''))
);
CREATE UNIQUE INDEX uq_documento_ingreso_numero ON documento_ingreso (proveedor_id, tipo, upper(numero))
    WHERE proveedor_id IS NOT NULL AND numero IS NOT NULL;
CREATE INDEX idx_documento_ingreso_proveedor ON documento_ingreso (proveedor_id);

ALTER TABLE lote_insumo ADD COLUMN proveedor_id UUID REFERENCES proveedor_compra(id);
ALTER TABLE lote_insumo ADD COLUMN documento_ingreso_id UUID REFERENCES documento_ingreso(id);
CREATE INDEX idx_lote_insumo_proveedor ON lote_insumo (proveedor_id);
CREATE INDEX idx_lote_insumo_documento ON lote_insumo (documento_ingreso_id);
