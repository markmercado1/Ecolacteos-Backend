-- Documento de retiro e idempotencia. No modifica ni elimina movimientos históricos.
CREATE TABLE salida_inventario (
 id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
 uuid_cliente UUID NOT NULL UNIQUE,
 presentacion_id UUID REFERENCES presentacion(id),
 insumo_id UUID REFERENCES insumo(id),
 cantidad NUMERIC(12,2) NOT NULL CHECK (cantidad > 0),
 tipo VARCHAR(20) NOT NULL CHECK (tipo IN ('VENCIMIENTO','DETERIORO','OTRA')),
 motivo VARCHAR(500) NOT NULL CHECK (length(trim(motivo)) > 0),
 origen_lote VARCHAR(20), lote_id UUID, fecha_vencimiento DATE,
 usuario_id UUID NOT NULL REFERENCES usuario(id), creado_en TIMESTAMP NOT NULL,
 CHECK ((presentacion_id IS NOT NULL) <> (insumo_id IS NOT NULL)),
 CHECK ((origen_lote IS NULL AND lote_id IS NULL) OR (origen_lote IS NOT NULL AND origen_lote IN ('LOTE','REGULARIZACION') AND lote_id IS NOT NULL))
);
ALTER TABLE movimiento_inventario ADD COLUMN salida_id UUID REFERENCES salida_inventario(id);
ALTER TABLE movimiento_insumo ADD COLUMN salida_id UUID REFERENCES salida_inventario(id);
CREATE UNIQUE INDEX uk_movimiento_salida ON movimiento_inventario(salida_id) WHERE salida_id IS NOT NULL;
CREATE UNIQUE INDEX uk_movimiento_insumo_retiro ON movimiento_insumo(salida_id) WHERE salida_id IS NOT NULL;
ALTER TABLE movimiento_inventario DROP CONSTRAINT chk_movimiento_inventario_tipo;
ALTER TABLE movimiento_inventario ADD CONSTRAINT chk_movimiento_inventario_tipo CHECK
 (tipo_movimiento IN ('ENTRADA_PRODUCCION','SALIDA_VENTA','AJUSTE','SALIDA_VENCIMIENTO','SALIDA_DETERIORO','SALIDA_OTRA'));
ALTER TABLE movimiento_insumo DROP CONSTRAINT chk_mov_insumo_tipo;
ALTER TABLE movimiento_insumo ADD CONSTRAINT chk_mov_insumo_tipo CHECK
 (tipo_movimiento IN ('SALDO_INICIAL','INGRESO','SALIDA_PRODUCCION','AJUSTE','SALIDA_VENCIMIENTO','SALIDA_DETERIORO','SALIDA_OTRA'));
ALTER TABLE movimiento_inventario ADD CONSTRAINT chk_movimiento_retiro CHECK
 ((tipo_movimiento IN ('SALIDA_VENCIMIENTO','SALIDA_DETERIORO','SALIDA_OTRA')) = (salida_id IS NOT NULL));
ALTER TABLE movimiento_inventario ADD CONSTRAINT chk_movimiento_retiro_origen CHECK
 (salida_id IS NULL OR (cantidad < 0 AND venta_detalle_id IS NULL AND lote_produccion_detalle_id IS NULL AND ajuste_inventario_id IS NULL));
ALTER TABLE movimiento_insumo ADD CONSTRAINT chk_mov_insumo_retiro CHECK
 ((tipo_movimiento IN ('SALIDA_VENCIMIENTO','SALIDA_DETERIORO','SALIDA_OTRA')) = (salida_id IS NOT NULL));
ALTER TABLE movimiento_insumo ADD CONSTRAINT chk_mov_insumo_retiro_origen CHECK
 (salida_id IS NULL OR (cantidad < 0 AND lote_produccion_id IS NULL AND usuario_id IS NOT NULL AND motivo IS NOT NULL));
