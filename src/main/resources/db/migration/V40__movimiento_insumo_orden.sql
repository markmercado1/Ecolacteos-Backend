-- Orden estable del kardex de insumos.
--
-- El kardex ordenaba por (creado_en, id) y el id es un UUID aleatorio: dos movimientos con el mismo instante (p. ej.
-- varios consumos de una misma transaccion de produccion) quedaban en un orden arbitrario y el saldo acumulado podia
-- mostrarse en una secuencia que nunca ocurrio. `orden` es una secuencia propia del libro: unica y creciente en el
-- orden en que se escriben los movimientos.
--
-- Migracion ADITIVA: solo agrega la columna. Las filas existentes reciben su numero segun el orden que el kardex ya
-- mostraba (creado_en, id), de modo que ningun saldo acumulado cambia. No modifica ninguna otra columna ni borra datos.
--
-- Reversa: DROP INDEX uk_movimiento_insumo_orden; ALTER TABLE movimiento_insumo DROP COLUMN orden;
--          DROP SEQUENCE movimiento_insumo_orden_seq;  (y borrar la fila 40 de flyway_schema_history).

CREATE SEQUENCE movimiento_insumo_orden_seq;

ALTER TABLE movimiento_insumo ADD COLUMN orden BIGINT;

UPDATE movimiento_insumo m
SET orden = r.n
FROM (SELECT id, row_number() OVER (ORDER BY creado_en, id) AS n FROM movimiento_insumo) r
WHERE r.id = m.id;

-- La secuencia continua despues del ultimo numero asignado (con el libro vacio, desde 1).
SELECT setval('movimiento_insumo_orden_seq', COALESCE((SELECT max(orden) FROM movimiento_insumo), 0) + 1, false);

ALTER TABLE movimiento_insumo ALTER COLUMN orden SET DEFAULT nextval('movimiento_insumo_orden_seq');
ALTER TABLE movimiento_insumo ALTER COLUMN orden SET NOT NULL;
ALTER SEQUENCE movimiento_insumo_orden_seq OWNED BY movimiento_insumo.orden;

CREATE UNIQUE INDEX uk_movimiento_insumo_orden ON movimiento_insumo(orden);
