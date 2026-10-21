-- Kardex (solo cantidades): libro mayor de movimientos de INSUMOS.
--
-- Leche (movimiento_inventario_leche, V8) y producto terminado (movimiento_inventario, V7/V12/V14/V15) ya
-- tienen su libro mayor append-only: su kardex es una lectura de esas tablas, sin estructura nueva. Los
-- insumos solo tenian insumo.stock (V17), un valor mutable sin historial. Esta tabla agrega ese historial con
-- el mismo criterio que los otros dos libros: append-only (sin UPDATE/DELETE desde la aplicacion), cantidad
-- con signo (positiva entra, negativa sale) y un origen trazable por tipo.
--
-- insumo.stock se CONSERVA como saldo actual (lo usan el descuento atomico de produccion, el catalogo y el
-- movil via /sync/cambios). Invariante: insumo.stock = SUM(movimiento_insumo.cantidad) por insumo; todo cambio
-- de stock pasa por InventarioInsumoService, que escribe el movimiento en la misma transaccion.
--
-- Solo cantidades por decision funcional: no hay costo unitario ni valorizacion. Si en el futuro se valoriza,
-- basta agregar columnas nullable (ej. costo_unitario) a este libro y a los otros dos sin rehacer el kardex.
CREATE TABLE movimiento_insumo (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    insumo_id UUID NOT NULL REFERENCES insumo(id),
    tipo_movimiento VARCHAR(20) NOT NULL,
    cantidad NUMERIC(12,2) NOT NULL,
    lote_produccion_id UUID REFERENCES lote_produccion(id),
    usuario_id UUID REFERENCES usuario(id),
    -- Ingreso: documento de respaldo opcional (guia, factura, proveedor). Ajuste: no aplica.
    referencia VARCHAR(100),
    -- Ingreso: observacion opcional. Ajuste: motivo obligatorio.
    motivo VARCHAR(500),
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_mov_insumo_tipo CHECK (tipo_movimiento IN ('SALDO_INICIAL', 'INGRESO', 'SALIDA_PRODUCCION', 'AJUSTE')),
    -- Saldo inicial: el stock que existia al crear este libro (o al crear el insumo); nunca negativo ni cero.
    CONSTRAINT chk_mov_insumo_saldo_inicial CHECK (
        tipo_movimiento <> 'SALDO_INICIAL' OR (cantidad > 0 AND lote_produccion_id IS NULL)
    ),
    -- Ingreso (reposicion): siempre entra, nunca ligado a un lote, siempre con usuario.
    CONSTRAINT chk_mov_insumo_ingreso CHECK (
        tipo_movimiento <> 'INGRESO' OR (cantidad > 0 AND lote_produccion_id IS NULL AND usuario_id IS NOT NULL)
    ),
    -- Salida por produccion: siempre sale y siempre ligada al lote que la consumio.
    CONSTRAINT chk_mov_insumo_salida CHECK (
        tipo_movimiento <> 'SALIDA_PRODUCCION' OR (cantidad < 0 AND lote_produccion_id IS NOT NULL)
    ),
    -- Ajuste por conteo fisico: variacion distinta de cero, con usuario y motivo (trazabilidad completa:
    -- quien, cuando, por que y cuanto; el saldo anterior es el saldo acumulado previo del kardex).
    CONSTRAINT chk_mov_insumo_ajuste CHECK (
        tipo_movimiento <> 'AJUSTE'
        OR (cantidad <> 0 AND lote_produccion_id IS NULL AND usuario_id IS NOT NULL AND motivo IS NOT NULL)
    )
);

CREATE INDEX idx_movimiento_insumo_insumo_fecha ON movimiento_insumo(insumo_id, creado_en, id);
CREATE INDEX idx_movimiento_insumo_lote ON movimiento_insumo(lote_produccion_id);

-- Un lote descuenta cada insumo como maximo una vez (defensivo, mismo criterio que
-- uk_movimiento_inventario_leche_lote / uk_movimiento_inventario_detalle): un reintento o una doble
-- invocacion nunca duplica el consumo.
CREATE UNIQUE INDEX uk_movimiento_insumo_salida_lote
    ON movimiento_insumo(lote_produccion_id, insumo_id) WHERE tipo_movimiento = 'SALIDA_PRODUCCION';

-- Saldo inicial: el stock existente al aplicar esta migracion pasa a ser el primer movimiento de cada
-- insumo (no se inventa historia anterior; el kardex cuadra desde hoy con el stock real).
INSERT INTO movimiento_insumo (insumo_id, tipo_movimiento, cantidad, motivo)
SELECT id, 'SALDO_INICIAL', stock, 'Saldo existente al iniciar el kardex (V19)'
FROM insumo
WHERE stock > 0;
