-- Insumos usados por lote de produccion (cuajo, sal, cultivos, etc.): el producto terminado queda trazado
-- con la leche Y los insumos con que se elaboro. Opcional: un lote sin filas aqui es valido (no se uso
-- ningun insumo del catalogo, o el catalogo no tiene el insumo usado) -- mismo criterio que
-- informe_produccion_insumo (V6): cantidad positiva, un insumo no se repite dentro del mismo lote.
CREATE TABLE lote_produccion_insumo (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    lote_id UUID NOT NULL REFERENCES lote_produccion(id),
    insumo_id UUID NOT NULL REFERENCES insumo(id),
    cantidad NUMERIC(9,2) NOT NULL CHECK (cantidad > 0),
    UNIQUE (lote_id, insumo_id)
);
CREATE INDEX idx_lote_insumo_lote ON lote_produccion_insumo(lote_id);
