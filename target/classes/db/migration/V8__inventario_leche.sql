-- Fase 1 del refactor de Produccion (ACOPIO -> RECEPCION -> EXISTENCIA DE LECHE -> PRODUCCION):
-- libro mayor de leche, mismo patron exacto que movimiento_inventario (V7, queso) -- append-only, el saldo
-- nunca es una columna mutable, siempre SUM(cantidad). Unico pool de planta (no se agrupa por unidad ni por
-- tipo de queso): litros_planta de CUALQUIER recepcion conciliada alimenta la misma existencia de leche.
--
-- Alcance explicito (mismo criterio que V7 tuvo para produccion/ventas historicas): el saldo de leche
-- controla produccion UNICAMENTE desde esta migracion en adelante. NO se generan movimientos retroactivos
-- para recepcion_planta existentes -- reinterpretar todo el historial como "leche disponible" hoy seria
-- incorrecto. Si hace falta un saldo inicial real, se establece con un ajuste explicito, no reinterpretando
-- el historial de recepcion.
--
-- lote_produccion_id se agrega desde ya (nullable, sin filas todavia) para no partir esta tabla en dos
-- migraciones: SALIDA_LECHE_PRODUCCION la va a poblar la fase siguiente del refactor (LoteProduccionService),
-- cuando exista el consumidor real de este saldo.
CREATE TABLE movimiento_inventario_leche (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tipo_movimiento VARCHAR(25) NOT NULL,
    cantidad NUMERIC(10,2) NOT NULL,
    recepcion_planta_id UUID REFERENCES recepcion_planta(id),
    lote_produccion_id UUID REFERENCES lote_produccion(id),
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_mov_leche_tipo CHECK (tipo_movimiento IN ('ENTRADA_LECHE_RECEPCION', 'SALIDA_LECHE_PRODUCCION')),
    -- Una entrada de leche: cantidad positiva, siempre ligada a una recepcion, nunca a un lote.
    CONSTRAINT chk_mov_leche_entrada CHECK (
        tipo_movimiento <> 'ENTRADA_LECHE_RECEPCION'
        OR (cantidad > 0 AND recepcion_planta_id IS NOT NULL AND lote_produccion_id IS NULL)
    ),
    -- Una salida de leche: cantidad negativa, siempre ligada a un lote, nunca a una recepcion.
    CONSTRAINT chk_mov_leche_salida CHECK (
        tipo_movimiento <> 'SALIDA_LECHE_PRODUCCION'
        OR (cantidad < 0 AND lote_produccion_id IS NOT NULL AND recepcion_planta_id IS NULL)
    )
);

CREATE INDEX idx_movimiento_inventario_leche_recepcion ON movimiento_inventario_leche(recepcion_planta_id);
CREATE INDEX idx_movimiento_inventario_leche_lote ON movimiento_inventario_leche(lote_produccion_id);

-- Una recepcion o un lote generan como maximo un movimiento de leche (defensivo, mismo criterio que
-- uk_movimiento_inventario_lote/uk_movimiento_inventario_venta de V7): evita duplicar la entrada/salida si
-- el codigo de aplicacion llegara a invocarse dos veces para la misma recepcion/lote.
CREATE UNIQUE INDEX uk_movimiento_inventario_leche_recepcion ON movimiento_inventario_leche(recepcion_planta_id) WHERE recepcion_planta_id IS NOT NULL;
CREATE UNIQUE INDEX uk_movimiento_inventario_leche_lote ON movimiento_inventario_leche(lote_produccion_id) WHERE lote_produccion_id IS NOT NULL;
