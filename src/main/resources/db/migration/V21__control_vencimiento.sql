-- Fase 1 (control de vencimiento y trazabilidad por lote): migracion SOLO ADITIVA. No modifica, borra ni
-- reinterpreta ningun dato existente, y NO toca la tabla movimiento_inventario (ni columnas, ni indices, ni
-- restricciones, ni tipos de movimiento): el libro mayor y el kardex quedan identicos.
--
-- Modelo: un producto puede "controlar vencimiento" (apagado por defecto: ningun producto actual cambia).
-- Al activarlo, el stock que ya existe en cada presentacion se declara "sin trazabilidad"
-- (saldo_sin_trazabilidad_inicial = stock del libro en ese instante): NO se le atribuye ningun lote ni
-- fecha. Desde entonces cada movimiento se reparte en movimiento_inventario_lote (un lote concreto, o NULL
-- = sin trazabilidad). Invariante por presentacion controlada:
--     SUM(movimiento_inventario.cantidad) = saldo_sin_trazabilidad_inicial + SUM(movimiento_inventario_lote.cantidad)

ALTER TABLE producto
    ADD COLUMN controla_vencimiento BOOLEAN NOT NULL DEFAULT FALSE;

-- NULL = la presentacion no esta bajo control de vencimiento.
ALTER TABLE presentacion
    ADD COLUMN saldo_sin_trazabilidad_inicial INT;

-- Fecha de vencimiento registrada por una persona para cada salida de un lote (nunca calculada). La original
-- se conserva la primera vez que se corrige; de la ultima correccion se guarda quien, cuando y por que.
ALTER TABLE lote_produccion_detalle
    ADD COLUMN fecha_vencimiento DATE,
    ADD COLUMN fecha_vencimiento_original DATE,
    ADD COLUMN vencimiento_corregido_por UUID REFERENCES usuario(id),
    ADD COLUMN vencimiento_corregido_en TIMESTAMP,
    ADD COLUMN vencimiento_motivo_correccion VARCHAR(300);

-- Reparto de cada movimiento del libro entre lotes. cantidad lleva el MISMO signo que el movimiento (entrada
-- positiva, salida negativa). lote_detalle_id NULL = existencias sin trazabilidad. El libro no se modifica:
-- esta tabla solo lo anota.
CREATE TABLE movimiento_inventario_lote (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    movimiento_id UUID NOT NULL REFERENCES movimiento_inventario(id),
    lote_detalle_id UUID REFERENCES lote_produccion_detalle(id),
    cantidad INT NOT NULL CHECK (cantidad <> 0),
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_movimiento_inventario_lote_movimiento ON movimiento_inventario_lote(movimiento_id);
CREATE INDEX idx_movimiento_inventario_lote_lote ON movimiento_inventario_lote(lote_detalle_id);
-- Un movimiento no puede repartirse dos veces en el mismo lote, ni dos veces en "sin trazabilidad".
CREATE UNIQUE INDEX uk_movimiento_inventario_lote_por_lote
    ON movimiento_inventario_lote(movimiento_id, lote_detalle_id) WHERE lote_detalle_id IS NOT NULL;
CREATE UNIQUE INDEX uk_movimiento_inventario_lote_sin_lote
    ON movimiento_inventario_lote(movimiento_id) WHERE lote_detalle_id IS NULL;
