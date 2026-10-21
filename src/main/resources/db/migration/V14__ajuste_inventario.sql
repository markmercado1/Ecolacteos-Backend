-- Inventario: accion "Ajustar stock" -- correccion manual de inventario de producto terminado por conteo
-- fisico (perdidas, roturas, errores de conteo). Nunca toca leche ni reinterpreta como se produjo: es un
-- movimiento NUEVO en el libro mayor (append-only), igual que produccion/venta -- nunca una sobreescritura
-- del saldo. Mismo patron ya usado por correccion_registro (V1): se conserva motivo, usuario y fecha, y la
-- cantidad "anterior" es siempre la que tenia el sistema al momento del ajuste (nunca un valor recalculado
-- despues). No hay ni UPDATE ni DELETE sobre esta tabla (ver AjusteInventarioService).

CREATE TABLE ajuste_inventario (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    presentacion_id UUID NOT NULL REFERENCES presentacion(id),
    usuario_id UUID NOT NULL REFERENCES usuario(id),
    cantidad_anterior INT NOT NULL,
    cantidad_corregida INT NOT NULL CHECK (cantidad_corregida >= 0),
    motivo TEXT NOT NULL,
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_ajuste_inventario_presentacion ON ajuste_inventario(presentacion_id);

-- El efecto real sobre el stock es un movimiento mas del mismo libro mayor (nunca una tabla de ajustes
-- separada del calculo de stock): SUM(cantidad) sigue siendo la unica fuente de verdad del stock.
ALTER TABLE movimiento_inventario ADD COLUMN ajuste_inventario_id UUID REFERENCES ajuste_inventario(id);

ALTER TABLE movimiento_inventario DROP CONSTRAINT chk_movimiento_inventario_tipo;
ALTER TABLE movimiento_inventario ADD CONSTRAINT chk_movimiento_inventario_tipo
    CHECK (tipo_movimiento IN ('ENTRADA_PRODUCCION', 'SALIDA_VENTA', 'AJUSTE'));

-- Un AJUSTE nunca va ligado a un lote ni a una venta (es una correccion de conteo, no un efecto de
-- produccion/venta); cantidad puede ser positiva o negativa (delta = cantidadCorregida - cantidadAnterior),
-- pero nunca cero (si el conteo coincide con el sistema no hay nada que ajustar, ver AjusteInventarioService).
ALTER TABLE movimiento_inventario ADD CONSTRAINT chk_movimiento_ajuste CHECK (
    tipo_movimiento <> 'AJUSTE'
    OR (ajuste_inventario_id IS NOT NULL AND lote_produccion_detalle_id IS NULL AND venta_id IS NULL AND cantidad <> 0)
);

-- Un ajuste genera como maximo un movimiento (defensivo, mismo criterio que
-- uk_movimiento_inventario_detalle/uk_movimiento_inventario_venta).
CREATE UNIQUE INDEX uk_movimiento_inventario_ajuste ON movimiento_inventario(ajuste_inventario_id) WHERE ajuste_inventario_id IS NOT NULL;
