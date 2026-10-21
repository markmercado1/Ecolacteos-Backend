-- Inventario de queso (stock en unidades) + precios de queso por modalidad de venta.
--
-- Libro mayor de movimientos (append-only), mismo criterio que registro_acopio/precio_litro: el stock
-- nunca se guarda como valor mutable, se calcula como SUM(cantidad) agrupado por tipo_queso_id (ver
-- InventarioService). Solo dos tipos de movimiento -- los unicos que existen en el flujo aprobado.
--
-- Alcance explicito (decision aprobada): el inventario controla produccion y ventas UNICAMENTE desde esta
-- migracion en adelante. NO se generan movimientos retroactivos para lote_produccion existentes -- puede
-- haber produccion historica ya vendida antes de que existiera control de stock, y reinterpretar todo el
-- historial como "disponible" seria incorrecto. Si en el futuro hace falta un stock inicial real, se
-- establece con un mecanismo explicito de ajuste de inventario, no reinterpretando el historial de
-- produccion (fuera de este alcance).

CREATE TABLE movimiento_inventario (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tipo_queso_id UUID NOT NULL REFERENCES tipo_queso(id),
    tipo_movimiento VARCHAR(20) NOT NULL,
    cantidad INT NOT NULL,
    lote_produccion_id UUID REFERENCES lote_produccion(id),
    venta_id UUID REFERENCES venta(id),
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_movimiento_inventario_tipo CHECK (tipo_movimiento IN ('ENTRADA_PRODUCCION', 'SALIDA_VENTA')),
    -- Una entrada de produccion: cantidad positiva, siempre ligada a un lote, nunca a una venta.
    CONSTRAINT chk_movimiento_entrada_produccion CHECK (
        tipo_movimiento <> 'ENTRADA_PRODUCCION'
        OR (cantidad > 0 AND lote_produccion_id IS NOT NULL AND venta_id IS NULL)
    ),
    -- Una salida de venta: cantidad negativa, siempre ligada a una venta, nunca a un lote.
    CONSTRAINT chk_movimiento_salida_venta CHECK (
        tipo_movimiento <> 'SALIDA_VENTA'
        OR (cantidad < 0 AND venta_id IS NOT NULL AND lote_produccion_id IS NULL)
    )
);

CREATE INDEX idx_movimiento_inventario_tipo_queso ON movimiento_inventario(tipo_queso_id);

-- Un lote o una venta generan como maximo un movimiento (defensivo: evita duplicar entrada/salida si el
-- codigo de aplicacion llegara a invocarse dos veces para el mismo lote/venta).
CREATE UNIQUE INDEX uk_movimiento_inventario_lote ON movimiento_inventario(lote_produccion_id) WHERE lote_produccion_id IS NOT NULL;
CREATE UNIQUE INDEX uk_movimiento_inventario_venta ON movimiento_inventario(venta_id) WHERE venta_id IS NOT NULL;

-- Precio de queso por tipo de queso + modalidad de venta (reutiliza TipoClienteVenta: MAYORISTA,
-- PROVEEDOR, PUBLICO -- no se crea un enum nuevo). Serie historica append-only, mismo criterio que
-- precio_litro: un precio nuevo no reemplaza al anterior, abre una vigencia distinta. Sin editar/eliminar.
CREATE TABLE precio_queso (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tipo_queso_id UUID NOT NULL REFERENCES tipo_queso(id),
    modalidad VARCHAR(15) NOT NULL,
    precio NUMERIC(8,2) NOT NULL,
    vigente_desde DATE NOT NULL,
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_precio_queso_modalidad CHECK (modalidad IN ('MAYORISTA', 'PROVEEDOR', 'PUBLICO')),
    UNIQUE (tipo_queso_id, modalidad, vigente_desde)
);

CREATE INDEX idx_precio_queso_tipo_modalidad ON precio_queso(tipo_queso_id, modalidad);
