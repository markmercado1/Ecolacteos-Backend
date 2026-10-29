-- Orden estable del kardex de leche y de producto terminado (mismo criterio que V40 para insumos).
--
-- El kardex ordenaba por (creado_en, id) y el id es un UUID aleatorio: movimientos con el mismo instante quedaban en un orden arbitrario y el
-- saldo acumulado podia mostrarse en una secuencia que nunca ocurrio. `orden` es una secuencia propia de cada libro: unica y creciente en el
-- orden en que se escriben los movimientos.
--
-- Migracion ADITIVA: solo agrega la columna. Las filas existentes reciben su numero segun el orden que el kardex YA mostraba (creado_en, id),
-- de modo que ningun saldo acumulado cambia. No modifica ninguna otra columna ni borra datos.
--
-- Reversa: DROP INDEX uk_movimiento_inventario_leche_orden, uk_movimiento_inventario_orden;
--          ALTER TABLE movimiento_inventario_leche DROP COLUMN orden; ALTER TABLE movimiento_inventario DROP COLUMN orden;
--          DROP SEQUENCE movimiento_inventario_leche_orden_seq, movimiento_inventario_orden_seq;  (y borrar la fila 47 de flyway_schema_history).

CREATE SEQUENCE movimiento_inventario_leche_orden_seq;
ALTER TABLE movimiento_inventario_leche ADD COLUMN orden BIGINT;
UPDATE movimiento_inventario_leche m SET orden = r.n
FROM (SELECT id, row_number() OVER (ORDER BY creado_en, id) AS n FROM movimiento_inventario_leche) r WHERE r.id = m.id;
SELECT setval('movimiento_inventario_leche_orden_seq', COALESCE((SELECT max(orden) FROM movimiento_inventario_leche), 0) + 1, false);
ALTER TABLE movimiento_inventario_leche ALTER COLUMN orden SET DEFAULT nextval('movimiento_inventario_leche_orden_seq');
ALTER TABLE movimiento_inventario_leche ALTER COLUMN orden SET NOT NULL;
ALTER SEQUENCE movimiento_inventario_leche_orden_seq OWNED BY movimiento_inventario_leche.orden;
CREATE UNIQUE INDEX uk_movimiento_inventario_leche_orden ON movimiento_inventario_leche(orden);

CREATE SEQUENCE movimiento_inventario_orden_seq;
ALTER TABLE movimiento_inventario ADD COLUMN orden BIGINT;
UPDATE movimiento_inventario m SET orden = r.n
FROM (SELECT id, row_number() OVER (ORDER BY creado_en, id) AS n FROM movimiento_inventario) r WHERE r.id = m.id;
SELECT setval('movimiento_inventario_orden_seq', COALESCE((SELECT max(orden) FROM movimiento_inventario), 0) + 1, false);
ALTER TABLE movimiento_inventario ALTER COLUMN orden SET DEFAULT nextval('movimiento_inventario_orden_seq');
ALTER TABLE movimiento_inventario ALTER COLUMN orden SET NOT NULL;
ALTER SEQUENCE movimiento_inventario_orden_seq OWNED BY movimiento_inventario.orden;
CREATE UNIQUE INDEX uk_movimiento_inventario_orden ON movimiento_inventario(orden);
