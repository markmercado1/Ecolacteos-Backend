-- Lotes de insumo: trazabilidad por lote.
--
-- Cada ingreso crea un LOTE (codigo, vencimiento opcional, cantidad inicial y disponible). El stock del insumo es la suma de
-- sus lotes disponibles y cada movimiento del kardex referencia el lote que afecta. Invariante (verificada aqui y por las
-- pruebas):
--     insumo.stock = SUM(lote_insumo.cantidad_disponible) = SUM(movimiento_insumo.cantidad)   (por insumo)
-- y, por lote:  lote_insumo.cantidad_disponible = SUM(movimiento_insumo.cantidad de ese lote).
--
-- Migracion de los datos existentes: cada insumo con movimientos recibe un lote "INICIAL" (sin proveedor ni vencimiento) con
-- su stock vigente, y TODOS sus movimientos historicos quedan enlazados a el. Los saldos y el kardex no cambian. El lote
-- INICIAL es de arrastre: su cantidad inicial es el stock de hoy, no la suma de sus ingresos historicos.
--
-- Cambios en indices unicos (necesarios para repartir un consumo o una salida entre varios lotes):
--   uk_movimiento_insumo_salida_lote: (lote_produccion_id, insumo_id)        -> + lote_insumo_id
--   uk_movimiento_insumo_retiro:      (salida_id)                            -> + lote_insumo_id
--
-- Reversa: ALTER TABLE movimiento_insumo DROP COLUMN lote_insumo_id (antes, recrear los dos indices con su forma anterior);
--          DROP TABLE lote_insumo; ALTER TABLE fusion_insumo DROP COLUMN lotes; (y borrar la fila 43 de flyway_schema_history).
--          Es seguro solo mientras ningun insumo tenga mas de un lote o un movimiento por lote repetido.

CREATE TABLE lote_insumo (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    insumo_id UUID NOT NULL REFERENCES insumo(id),
    codigo VARCHAR(40) NOT NULL CHECK (btrim(codigo) <> ''),
    codigo_proveedor VARCHAR(60),
    fecha_ingreso DATE NOT NULL,
    fecha_vencimiento DATE,
    cantidad_inicial NUMERIC(12,2) NOT NULL,
    cantidad_disponible NUMERIC(12,2) NOT NULL CHECK (cantidad_disponible >= 0),
    origen VARCHAR(10) NOT NULL CHECK (origen IN ('INGRESO', 'INICIAL')),
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_lote_insumo_codigo UNIQUE (insumo_id, codigo),
    -- Un lote de arrastre puede nacer en 0 (insumo con historial y sin stock); un ingreso real siempre es mayor que 0.
    CONSTRAINT ck_lote_insumo_inicial CHECK ((origen = 'INICIAL' AND cantidad_inicial >= 0) OR cantidad_inicial > 0)
);
CREATE INDEX idx_lote_insumo_fefo ON lote_insumo (insumo_id, fecha_vencimiento, fecha_ingreso) WHERE cantidad_disponible > 0;
CREATE INDEX idx_lote_insumo_vencimiento ON lote_insumo (fecha_vencimiento) WHERE cantidad_disponible > 0 AND fecha_vencimiento IS NOT NULL;

-- Lote INICIAL de cada insumo con historial.
INSERT INTO lote_insumo (insumo_id, codigo, fecha_ingreso, cantidad_inicial, cantidad_disponible, origen)
SELECT i.id, 'INICIAL', min(m.creado_en)::date, i.stock, i.stock, 'INICIAL'
FROM insumo i JOIN movimiento_insumo m ON m.insumo_id = i.id
GROUP BY i.id, i.stock;

ALTER TABLE movimiento_insumo ADD COLUMN lote_insumo_id UUID REFERENCES lote_insumo(id);

UPDATE movimiento_insumo m SET lote_insumo_id = l.id
FROM lote_insumo l WHERE l.insumo_id = m.insumo_id AND l.codigo = 'INICIAL';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM movimiento_insumo WHERE lote_insumo_id IS NULL) THEN
        RAISE EXCEPTION 'V43: hay movimientos de insumo sin lote tras el backfill';
    END IF;
    IF EXISTS (SELECT 1 FROM lote_insumo l
               WHERE l.cantidad_disponible <> (SELECT COALESCE(SUM(m.cantidad), 0) FROM movimiento_insumo m WHERE m.lote_insumo_id = l.id)) THEN
        RAISE EXCEPTION 'V43: el stock de un lote no coincide con la suma de sus movimientos';
    END IF;
    IF EXISTS (SELECT 1 FROM insumo i
               WHERE i.stock <> (SELECT COALESCE(SUM(l.cantidad_disponible), 0) FROM lote_insumo l WHERE l.insumo_id = i.id)) THEN
        RAISE EXCEPTION 'V43: el stock de un insumo no coincide con la suma de sus lotes';
    END IF;
END
$$;

ALTER TABLE movimiento_insumo ALTER COLUMN lote_insumo_id SET NOT NULL;
CREATE INDEX idx_movimiento_insumo_lote_insumo ON movimiento_insumo (lote_insumo_id, orden);

DROP INDEX uk_movimiento_insumo_salida_lote;
CREATE UNIQUE INDEX uk_movimiento_insumo_salida_lote
    ON movimiento_insumo (lote_produccion_id, insumo_id, lote_insumo_id) WHERE tipo_movimiento = 'SALIDA_PRODUCCION';

DROP INDEX uk_movimiento_insumo_retiro;
CREATE UNIQUE INDEX uk_movimiento_insumo_retiro ON movimiento_insumo (salida_id, lote_insumo_id) WHERE salida_id IS NOT NULL;

-- La fusion de insumos tambien traslada los lotes del insumo absorbido.
ALTER TABLE fusion_insumo ADD COLUMN lotes INT NOT NULL DEFAULT 0;
