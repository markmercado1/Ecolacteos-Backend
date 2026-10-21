-- Fase 3: Produccion con varias presentaciones de salida e Inventario por presentacion.
--
-- Un lote de produccion corresponde a UN producto elaborado pero puede generar VARIAS presentaciones de
-- salida (ej. 20 botellas de 2 L + 40 de 1 L + 30 de 500 ml del mismo lote de Yogurt de fresa, un solo
-- consumo de leche). El stock de producto terminado pasa a calcularse por PRESENTACION, no por producto.
--
-- Mapeo historico verificable (mismo criterio ya usado por V11 para precio_queso->precio_presentacion):
-- cada lote/venta/movimiento anterior a esta migracion se asocia a la presentacion 'Unidad' de su producto
-- -- la MISMA fila especifica que V10 creo para ese producto exacto, identificada por nombre dentro de ese
-- producto (nunca "la unica presentacion activa actual", que podria ya ser ambigua si se crearon mas desde
-- la web). Si algun producto no tiene EXACTAMENTE una presentacion 'Unidad', la migracion se detiene con
-- un error explicito en vez de adivinar.

-- 1. Detalle de salidas de produccion: una fila por presentacion obtenida en un lote.
CREATE TABLE lote_produccion_detalle (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    lote_id UUID NOT NULL REFERENCES lote_produccion(id),
    presentacion_id UUID NOT NULL REFERENCES presentacion(id),
    cantidad_obtenida INT NOT NULL CHECK (cantidad_obtenida > 0),
    UNIQUE (lote_id, presentacion_id)
);
CREATE INDEX idx_lote_detalle_lote ON lote_produccion_detalle(lote_id);
CREATE INDEX idx_lote_detalle_presentacion ON lote_produccion_detalle(presentacion_id);

-- 2. Preparar movimiento_inventario para el nuevo origen (presentacion en vez de producto) ANTES de migrar
-- datos: las columnas deben existir en la tabla real antes de que cualquier UPDATE/DO $$ las referencie
-- (a diferencia de un bloque PL/pgSQL, el DDL de una migracion no se "adelanta" solo por compartir
-- transaccion -- cada sentencia se ejecuta en el orden fisico del archivo).
ALTER TABLE movimiento_inventario ADD COLUMN presentacion_id UUID REFERENCES presentacion(id);
ALTER TABLE movimiento_inventario ADD COLUMN lote_produccion_detalle_id UUID REFERENCES lote_produccion_detalle(id);

-- 3. Migrar cada lote historico con unidades_obtenidas > 0 a una fila de detalle equivalente hacia la
-- presentacion 'Unidad' de su producto. Los lotes con unidades_obtenidas = 0 (produccion real sin ningun
-- rendimiento, caso ya existente) NO generan fila de detalle -- se preservan como "cero filas", nunca como
-- una fila en cero (mismo criterio que rige hacia adelante: nunca se persiste una linea de cantidad cero).
DO $$
DECLARE
    r RECORD;
    v_presentacion_id UUID;
    v_count INT;
    v_detalle_id UUID;
BEGIN
    FOR r IN SELECT id, producto_id, unidades_obtenidas FROM lote_produccion WHERE unidades_obtenidas > 0 LOOP
        SELECT COUNT(*) INTO v_count FROM presentacion WHERE producto_id = r.producto_id AND nombre = 'Unidad';
        IF v_count <> 1 THEN
            RAISE EXCEPTION 'Migracion V12 ambigua: el producto % no tiene exactamente una presentacion "Unidad" (encontradas %) requerida para migrar el lote %',
                r.producto_id, v_count, r.id;
        END IF;
        SELECT id INTO v_presentacion_id FROM presentacion WHERE producto_id = r.producto_id AND nombre = 'Unidad';

        INSERT INTO lote_produccion_detalle (id, lote_id, presentacion_id, cantidad_obtenida)
        VALUES (uuid_generate_v4(), r.id, v_presentacion_id, r.unidades_obtenidas)
        RETURNING id INTO v_detalle_id;

        UPDATE movimiento_inventario
            SET presentacion_id = v_presentacion_id, lote_produccion_detalle_id = v_detalle_id
            WHERE lote_produccion_id = r.id AND tipo_movimiento = 'ENTRADA_PRODUCCION';
    END LOOP;
END $$;

-- 4. Cada movimiento SALIDA_VENTA hereda la presentacion 'Unidad' del producto de su venta (mismo mapeo
-- verificable; nunca elegido por posicion ni "porque originalmente era la unica").
DO $$
DECLARE
    r RECORD;
    v_presentacion_id UUID;
    v_count INT;
BEGIN
    FOR r IN
        SELECT m.id AS movimiento_id, v.producto_id AS producto_id
        FROM movimiento_inventario m JOIN venta v ON v.id = m.venta_id
        WHERE m.tipo_movimiento = 'SALIDA_VENTA'
    LOOP
        SELECT COUNT(*) INTO v_count FROM presentacion WHERE producto_id = r.producto_id AND nombre = 'Unidad';
        IF v_count <> 1 THEN
            RAISE EXCEPTION 'Migracion V12 ambigua: el producto % no tiene exactamente una presentacion "Unidad" requerida para migrar el movimiento de venta %',
                r.producto_id, r.movimiento_id;
        END IF;
        SELECT id INTO v_presentacion_id FROM presentacion WHERE producto_id = r.producto_id AND nombre = 'Unidad';
        UPDATE movimiento_inventario SET presentacion_id = v_presentacion_id WHERE id = r.movimiento_id;
    END LOOP;
END $$;

-- Verificacion de integridad: todo movimiento debe haber quedado con presentacion_id asignado (falla
-- fuerte, no en silencio, si algun caso no fue cubierto por los dos bucles anteriores).
DO $$
DECLARE v_count INT;
BEGIN
    SELECT COUNT(*) INTO v_count FROM movimiento_inventario WHERE presentacion_id IS NULL;
    IF v_count <> 0 THEN
        RAISE EXCEPTION 'Migracion V12 incompleta: % movimientos de inventario quedaron sin presentacion_id', v_count;
    END IF;
END $$;

ALTER TABLE movimiento_inventario ALTER COLUMN presentacion_id SET NOT NULL;

-- Reemplaza indices/constraints basados en producto_id/lote_produccion_id por sus equivalentes nuevos. La
-- proteccion de "como maximo una entrada por origen" pasa del LOTE (uk_movimiento_inventario_lote) al
-- DETALLE (uk_movimiento_inventario_detalle): un lote con 3 salidas ahora genera 3 movimientos legitimos,
-- pero cada detalle sigue generando como maximo uno.
DROP INDEX idx_movimiento_inventario_producto;
CREATE INDEX idx_movimiento_inventario_presentacion ON movimiento_inventario(presentacion_id);

ALTER TABLE movimiento_inventario DROP CONSTRAINT chk_movimiento_entrada_produccion;
ALTER TABLE movimiento_inventario DROP CONSTRAINT chk_movimiento_salida_venta;
ALTER TABLE movimiento_inventario ADD CONSTRAINT chk_movimiento_entrada_produccion CHECK (
    tipo_movimiento <> 'ENTRADA_PRODUCCION'
    OR (cantidad > 0 AND lote_produccion_detalle_id IS NOT NULL AND venta_id IS NULL)
);
ALTER TABLE movimiento_inventario ADD CONSTRAINT chk_movimiento_salida_venta CHECK (
    tipo_movimiento <> 'SALIDA_VENTA'
    OR (cantidad < 0 AND venta_id IS NOT NULL AND lote_produccion_detalle_id IS NULL)
);

DROP INDEX uk_movimiento_inventario_lote;
CREATE UNIQUE INDEX uk_movimiento_inventario_detalle ON movimiento_inventario(lote_produccion_detalle_id) WHERE lote_produccion_detalle_id IS NOT NULL;
-- uk_movimiento_inventario_venta (una salida por venta) no cambia: se conserva tal cual.

ALTER TABLE movimiento_inventario DROP COLUMN producto_id;
ALTER TABLE movimiento_inventario DROP COLUMN lote_produccion_id;

-- 5. Venta: producto_id -> presentacion_id (Inventario ya no controla stock por producto). Mismo mapeo
-- verificable hacia la presentacion 'Unidad' de cada venta historica.
ALTER TABLE venta ADD COLUMN presentacion_id UUID REFERENCES presentacion(id);
DO $$
DECLARE
    r RECORD;
    v_presentacion_id UUID;
    v_count INT;
BEGIN
    FOR r IN SELECT id, producto_id FROM venta LOOP
        SELECT COUNT(*) INTO v_count FROM presentacion WHERE producto_id = r.producto_id AND nombre = 'Unidad';
        IF v_count <> 1 THEN
            RAISE EXCEPTION 'Migracion V12 ambigua: el producto % no tiene exactamente una presentacion "Unidad" requerida para migrar la venta %',
                r.producto_id, r.id;
        END IF;
        SELECT id INTO v_presentacion_id FROM presentacion WHERE producto_id = r.producto_id AND nombre = 'Unidad';
        UPDATE venta SET presentacion_id = v_presentacion_id WHERE id = r.id;
    END LOOP;
END $$;

DO $$
DECLARE v_count INT;
BEGIN
    SELECT COUNT(*) INTO v_count FROM venta WHERE presentacion_id IS NULL;
    IF v_count <> 0 THEN
        RAISE EXCEPTION 'Migracion V12 incompleta: % ventas quedaron sin presentacion_id', v_count;
    END IF;
END $$;

ALTER TABLE venta ALTER COLUMN presentacion_id SET NOT NULL;
ALTER TABLE venta DROP COLUMN producto_id;

-- 6. lote_produccion.unidades_obtenidas queda completamente reemplazado por SUM(lote_produccion_detalle.cantidad_obtenida)
-- (0 filas = 0 unidades, el caso "sin ningun rendimiento" ya migrado como ausencia de filas en el paso 2).
ALTER TABLE lote_produccion DROP COLUMN unidades_obtenidas;
