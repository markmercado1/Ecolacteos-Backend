-- Configuración opcional: no atribuir límites nuevos a los productos históricos.
ALTER TABLE producto
    ADD COLUMN rendimiento_minimo NUMERIC(16,4),
    ADD COLUMN rendimiento_maximo NUMERIC(16,4),
    ADD COLUMN rendimiento_unidad VARCHAR(6),
    ADD CONSTRAINT ck_producto_rango_rendimiento CHECK (
        (rendimiento_minimo IS NULL AND rendimiento_maximo IS NULL AND rendimiento_unidad IS NULL)
        OR (rendimiento_minimo IS NOT NULL AND rendimiento_maximo IS NOT NULL
            AND rendimiento_unidad IS NOT NULL AND rendimiento_minimo >= 0
            AND rendimiento_maximo > 0 AND rendimiento_maximo >= rendimiento_minimo
            AND rendimiento_unidad IN ('KG', 'L', 'UNIDAD'))
    );

ALTER TABLE lote_produccion
    ADD COLUMN motivo_rendimiento VARCHAR(500),
    ADD COLUMN revision_rendimiento VARCHAR(500);
