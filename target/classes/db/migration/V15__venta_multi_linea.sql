-- Fase 4: Venta multi-linea (carrito). Una venta pasa de una sola presentacion+cantidad a una o mas lineas
-- (venta_detalle), cada una con su propia presentacion, cantidad y precio vigente al momento de la venta.
-- El total de la venta pasa de ser una columna GENERADA (cantidad * precio_unitario, una sola linea) a ser
-- la suma explicita de los subtotales de cada linea, calculada por la aplicacion al confirmar la venta (ver
-- VentaService) -- Postgres no puede expresar una columna generada que sume filas de OTRA tabla.
--
-- Contrato movil sin cambios: POST /api/ventas (VentaRequest/VentaResponse, una sola linea) sigue existiendo
-- tal cual para el sync de la app movil (ver SyncService.procesarLoteVentas) -- internamente ahora crea una
-- venta con EXACTAMENTE una fila de venta_detalle, nunca mas de una. El carrito multi-linea es un endpoint
-- nuevo, exclusivo de la web.
--
-- Migracion de historico (mismo criterio que V12 para produccion): cada venta anterior a esta migracion
-- (siempre de una sola linea hasta ahora) migra a UNA fila de venta_detalle con los mismos
-- presentacion_id/cantidad/precio_unitario que ya tenia. El total historico, ya calculado por Postgres
-- (GENERATED STORED), se preserva intacto al convertir la columna a una columna normal (ALTER COLUMN ...
-- DROP EXPRESSION, Postgres 12+) -- no se recalcula nada.

-- 1. Detalle de lineas de venta: una fila por presentacion vendida en una venta.
CREATE TABLE venta_detalle (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    venta_id UUID NOT NULL REFERENCES venta(id),
    presentacion_id UUID NOT NULL REFERENCES presentacion(id),
    cantidad INT NOT NULL CHECK (cantidad > 0),
    precio_unitario NUMERIC(8,2) NOT NULL,
    subtotal NUMERIC(10,2) GENERATED ALWAYS AS (cantidad * precio_unitario) STORED,
    UNIQUE (venta_id, presentacion_id)
);
CREATE INDEX idx_venta_detalle_venta ON venta_detalle(venta_id);
CREATE INDEX idx_venta_detalle_presentacion ON venta_detalle(presentacion_id);

-- 2. Preparar movimiento_inventario para el nuevo origen (venta_detalle en vez de venta) ANTES de migrar
-- datos: el DDL de una migracion no se "adelanta" solo por compartir transaccion -- cada sentencia se
-- ejecuta en el orden fisico del archivo (mismo motivo que V12).
ALTER TABLE movimiento_inventario ADD COLUMN venta_detalle_id UUID REFERENCES venta_detalle(id);

-- 3. Migrar cada venta historica (una sola linea) a su fila de venta_detalle equivalente, y reapuntar su
-- movimiento SALIDA_VENTA (si existe) a esa fila.
DO $$
DECLARE
    r RECORD;
    v_detalle_id UUID;
BEGIN
    FOR r IN SELECT id, presentacion_id, cantidad, precio_unitario FROM venta LOOP
        INSERT INTO venta_detalle (id, venta_id, presentacion_id, cantidad, precio_unitario)
        VALUES (uuid_generate_v4(), r.id, r.presentacion_id, r.cantidad, r.precio_unitario)
        RETURNING id INTO v_detalle_id;

        UPDATE movimiento_inventario
            SET venta_detalle_id = v_detalle_id
            WHERE venta_id = r.id AND tipo_movimiento = 'SALIDA_VENTA';
    END LOOP;
END $$;

-- Verificacion de integridad: todo movimiento SALIDA_VENTA debe haber quedado con venta_detalle_id asignado
-- (falla fuerte, no en silencio, si algun caso no fue cubierto por el bucle anterior).
DO $$
DECLARE v_count INT;
BEGIN
    SELECT COUNT(*) INTO v_count FROM movimiento_inventario WHERE tipo_movimiento = 'SALIDA_VENTA' AND venta_detalle_id IS NULL;
    IF v_count <> 0 THEN
        RAISE EXCEPTION 'Migracion V15 incompleta: % movimientos de venta quedaron sin venta_detalle_id', v_count;
    END IF;
END $$;

-- 4. Reemplaza la proteccion "una salida por venta" (uk_movimiento_inventario_venta) por "una salida por
-- LINEA de venta" (uk_movimiento_inventario_venta_detalle): una venta con 3 lineas ahora genera 3
-- movimientos legitimos, pero cada linea sigue generando como maximo uno.
ALTER TABLE movimiento_inventario DROP CONSTRAINT chk_movimiento_salida_venta;
ALTER TABLE movimiento_inventario ADD CONSTRAINT chk_movimiento_salida_venta CHECK (
    tipo_movimiento <> 'SALIDA_VENTA'
    OR (cantidad < 0 AND venta_detalle_id IS NOT NULL AND lote_produccion_detalle_id IS NULL AND ajuste_inventario_id IS NULL)
);

DROP INDEX uk_movimiento_inventario_venta;
CREATE UNIQUE INDEX uk_movimiento_inventario_venta_detalle ON movimiento_inventario(venta_detalle_id) WHERE venta_detalle_id IS NOT NULL;

ALTER TABLE movimiento_inventario DROP COLUMN venta_id;

-- 5. venta.total deja de ser una columna generada por Postgres (ya no puede expresar la suma de las lineas
-- de OTRA tabla): se convierte en una columna normal preservando el valor historico ya calculado (Postgres
-- 12+, DROP EXPRESSION no recalcula nada, solo deja de regenerar el valor a futuro -- a partir de ahora lo
-- fija VentaService, como suma de los subtotales de cada linea).
ALTER TABLE venta ALTER COLUMN total DROP EXPRESSION;
ALTER TABLE venta ALTER COLUMN total SET NOT NULL;

-- 6. Las columnas de la (antigua) unica linea quedan completamente reemplazadas por venta_detalle.
ALTER TABLE venta DROP COLUMN presentacion_id;
ALTER TABLE venta DROP COLUMN cantidad;
ALTER TABLE venta DROP COLUMN precio_unitario;
