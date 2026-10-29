-- Salidas y conteos por lote: motivos de salida de insumos ampliados y tipos de movimiento nuevos.
--
--   * Motivos de salida (salida_inventario.tipo): a VENCIMIENTO, DETERIORO y OTRA se suman MERMA, DERRAME, DEVOLUCION_PROVEEDOR
--     y CONSUMO_MANUAL (solo insumos; las filas historicas siguen validas).
--   * salida_inventario.origen_lote acepta 'INSUMO': lote_id apunta entonces a un lote de insumo (V43) elegido por quien registra
--     la salida (sin lote elegido, la salida se reparte en FEFO).
--   * movimiento_insumo.tipo_movimiento pasa a VARCHAR(40) (SALIDA_DEVOLUCION_PROVEEDOR mide 28) y admite:
--       ANULACION_INGRESO  reversa de un ingreso registrado por error (negativa, con usuario y motivo);
--       TRANSFERENCIA_LOTE traslado entre lotes de un mismo insumo, de suma cero (la usa la regularizacion del saldo inicial).
--     Los movimientos siguen siendo inmutables: toda correccion es un movimiento nuevo.
--
-- Migracion ADITIVA: no modifica ni borra ninguna fila existente.
-- Reversa: restaurar los CHECK anteriores (tipo IN ('VENCIMIENTO','DETERIORO','OTRA'); origen_lote IN ('LOTE','REGULARIZACION');
--          los 7 tipos de V36 en movimiento_insumo) y ALTER COLUMN tipo_movimiento TYPE VARCHAR(20), siempre que no existan
--          filas con los valores nuevos. (Y borrar la fila 45 de flyway_schema_history.)

-- salida_inventario: sus CHECK de tipo y de origen_lote no tienen nombre propio; se localizan por su definicion.
DO $$
DECLARE c RECORD;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'salida_inventario'::regclass AND contype = 'c'
               AND (pg_get_constraintdef(oid) ILIKE '%VENCIMIENTO%' OR pg_get_constraintdef(oid) ILIKE '%origen_lote%')
    LOOP
        EXECUTE format('ALTER TABLE salida_inventario DROP CONSTRAINT %I', c.conname);
    END LOOP;
END
$$;
ALTER TABLE salida_inventario ADD CONSTRAINT ck_salida_inventario_tipo CHECK
    (tipo IN ('VENCIMIENTO', 'DETERIORO', 'OTRA', 'MERMA', 'DERRAME', 'DEVOLUCION_PROVEEDOR', 'CONSUMO_MANUAL'));
ALTER TABLE salida_inventario ADD CONSTRAINT ck_salida_inventario_origen_lote CHECK
    ((origen_lote IS NULL AND lote_id IS NULL) OR (origen_lote IN ('LOTE', 'REGULARIZACION', 'INSUMO') AND lote_id IS NOT NULL));
-- Los motivos nuevos son solo de insumos.
ALTER TABLE salida_inventario ADD CONSTRAINT ck_salida_inventario_motivo_insumo CHECK
    (insumo_id IS NOT NULL OR tipo IN ('VENCIMIENTO', 'DETERIORO', 'OTRA'));
ALTER TABLE salida_inventario ADD CONSTRAINT ck_salida_inventario_origen_insumo CHECK
    (origen_lote IS DISTINCT FROM 'INSUMO' OR insumo_id IS NOT NULL);

-- movimiento_insumo: tipos nuevos.
ALTER TABLE movimiento_insumo ALTER COLUMN tipo_movimiento TYPE VARCHAR(40);
ALTER TABLE movimiento_insumo DROP CONSTRAINT chk_mov_insumo_tipo;
ALTER TABLE movimiento_insumo ADD CONSTRAINT chk_mov_insumo_tipo CHECK (tipo_movimiento IN (
    'SALDO_INICIAL', 'INGRESO', 'SALIDA_PRODUCCION', 'AJUSTE', 'SALIDA_VENCIMIENTO', 'SALIDA_DETERIORO', 'SALIDA_OTRA',
    'SALIDA_MERMA', 'SALIDA_DERRAME', 'SALIDA_DEVOLUCION_PROVEEDOR', 'SALIDA_CONSUMO_MANUAL',
    'ANULACION_INGRESO', 'TRANSFERENCIA_LOTE'));
ALTER TABLE movimiento_insumo DROP CONSTRAINT chk_mov_insumo_retiro;
ALTER TABLE movimiento_insumo ADD CONSTRAINT chk_mov_insumo_retiro CHECK (
    (tipo_movimiento IN ('SALIDA_VENCIMIENTO', 'SALIDA_DETERIORO', 'SALIDA_OTRA', 'SALIDA_MERMA', 'SALIDA_DERRAME',
                         'SALIDA_DEVOLUCION_PROVEEDOR', 'SALIDA_CONSUMO_MANUAL')) = (salida_id IS NOT NULL));
ALTER TABLE movimiento_insumo ADD CONSTRAINT chk_mov_insumo_anulacion CHECK (
    tipo_movimiento <> 'ANULACION_INGRESO'
    OR (cantidad < 0 AND lote_produccion_id IS NULL AND salida_id IS NULL AND usuario_id IS NOT NULL AND motivo IS NOT NULL));
ALTER TABLE movimiento_insumo ADD CONSTRAINT chk_mov_insumo_transferencia CHECK (
    tipo_movimiento <> 'TRANSFERENCIA_LOTE'
    OR (cantidad <> 0 AND lote_produccion_id IS NULL AND salida_id IS NULL AND usuario_id IS NOT NULL AND motivo IS NOT NULL));
