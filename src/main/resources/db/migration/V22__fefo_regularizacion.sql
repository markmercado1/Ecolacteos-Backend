-- Fase 2 (FEFO, bloqueo de vencidos y regularizacion de existencias sin trazabilidad). Cambios SOLO sobre las
-- tablas que creo la Fase 1 (que aun no tienen datos reales: la activacion del control esta deshabilitada); NO
-- toca movimiento_inventario ni su kardex.
--
-- Regularizacion: reclasifica N unidades del saldo "sin trazabilidad" de una presentacion a un lote con una
-- fecha de vencimiento (la de la etiqueta fisica). NO escribe en el libro: el stock total de la presentacion no
-- cambia, solo pasa de un "cajon" a otro. Por eso es una tabla propia, no un movimiento. Los movimientos que
-- despues consuman ese lote se reparten en movimiento_inventario_lote con regularizacion_id.
--
-- Saldos por presentacion controlada (todos derivados, nunca almacenados):
--   sin trazabilidad  = saldo_sin_trazabilidad_inicial
--                       + SUM(reparto con lote NULL y regularizacion NULL)
--                       - SUM(regularizacion_trazabilidad.cantidad)
--   lote de produccion = SUM(reparto con ese lote_detalle_id)
--   lote regularizado  = regularizacion.cantidad + SUM(reparto con esa regularizacion_id)
-- y el stock del libro sigue siendo  saldo_sin_trazabilidad_inicial + SUM(reparto).

CREATE TABLE regularizacion_trazabilidad (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    presentacion_id UUID NOT NULL REFERENCES presentacion(id),
    cantidad INT NOT NULL CHECK (cantidad > 0),
    fecha_vencimiento DATE NOT NULL,
    motivo VARCHAR(300) NOT NULL CHECK (length(btrim(motivo)) > 0),
    usuario_id UUID NOT NULL REFERENCES usuario(id),
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_regularizacion_trazabilidad_presentacion ON regularizacion_trazabilidad(presentacion_id);

ALTER TABLE movimiento_inventario_lote
    ADD COLUMN regularizacion_id UUID REFERENCES regularizacion_trazabilidad(id);

-- Un reparto pertenece a UN solo origen: un lote de produccion, una regularizacion, o ninguno (sin trazabilidad).
ALTER TABLE movimiento_inventario_lote
    ADD CONSTRAINT chk_movimiento_inventario_lote_un_origen
    CHECK (lote_detalle_id IS NULL OR regularizacion_id IS NULL);

CREATE INDEX idx_movimiento_inventario_lote_regularizacion ON movimiento_inventario_lote(regularizacion_id);

-- "Sin trazabilidad" ahora significa: ni lote de produccion ni regularizacion.
DROP INDEX uk_movimiento_inventario_lote_sin_lote;
CREATE UNIQUE INDEX uk_movimiento_inventario_lote_sin_lote
    ON movimiento_inventario_lote(movimiento_id) WHERE lote_detalle_id IS NULL AND regularizacion_id IS NULL;
CREATE UNIQUE INDEX uk_movimiento_inventario_lote_por_regularizacion
    ON movimiento_inventario_lote(movimiento_id, regularizacion_id) WHERE regularizacion_id IS NOT NULL;
