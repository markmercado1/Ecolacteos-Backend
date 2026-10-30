-- Comprobante de la venta (nota de venta o boleta, con serie y numero correlativo) y codigo de la presentacion.
--
-- 1) venta: tipo_comprobante + serie + numero. Dos series independientes, cada una con su propia secuencia:
--      NOTA_VENTA -> serie NV01 (venta_nv01_seq)      BOLETA -> serie B001 (venta_b001_seq)
--    El numero lo asigna VentaService al registrar (nextval). Las ventas que ya existian se numeran UNA vez como nota de venta
--    en orden cronologico (fecha, creado_en, id), para que el historial quede uniforme; no se inventa ninguna boleta.
--    Los valores por defecto (NOTA_VENTA / NV01 / siguiente numero) cubren cualquier INSERT que no los mencione, como el de la
--    app movil o el de las pruebas. Una secuencia puede dejar huecos si una transaccion se revierte: aceptable en un comprobante
--    interno (no hay correlativo fiscal que garantizar mientras no exista la integracion con SUNAT).
--
-- 2) presentacion: codigo corto y unico (PRD-001...), asignado por la BD con una secuencia. Las existentes se codifican una vez
--    en orden alfabetico (familia, producto, presentacion) para que el resultado sea estable y verificable.
--
-- Migracion SOLO ADITIVA: ninguna columna ni fila existente cambia de significado.

-- ------------------------------------------------------------------ venta
CREATE SEQUENCE venta_nv01_seq;
CREATE SEQUENCE venta_b001_seq;

ALTER TABLE venta ADD COLUMN tipo_comprobante VARCHAR(15) NOT NULL DEFAULT 'NOTA_VENTA';
ALTER TABLE venta ADD COLUMN serie VARCHAR(4);
ALTER TABLE venta ADD COLUMN numero BIGINT;

UPDATE venta v
   SET serie = 'NV01', numero = o.n
  FROM (SELECT id, row_number() OVER (ORDER BY fecha, creado_en, id) AS n FROM venta) o
 WHERE o.id = v.id;

SELECT setval('venta_nv01_seq', COALESCE((SELECT max(numero) FROM venta), 0) + 1, false);

ALTER TABLE venta ALTER COLUMN serie SET DEFAULT 'NV01';
ALTER TABLE venta ALTER COLUMN numero SET DEFAULT nextval('venta_nv01_seq');
ALTER TABLE venta ALTER COLUMN serie SET NOT NULL;
ALTER TABLE venta ALTER COLUMN numero SET NOT NULL;

ALTER TABLE venta ADD CONSTRAINT chk_venta_comprobante CHECK (
    (tipo_comprobante = 'NOTA_VENTA' AND serie = 'NV01')
    OR (tipo_comprobante = 'BOLETA' AND serie = 'B001')
);
CREATE UNIQUE INDEX uk_venta_serie_numero ON venta (serie, numero);

-- ------------------------------------------------------------------ presentacion
CREATE SEQUENCE presentacion_codigo_seq;

ALTER TABLE presentacion ADD COLUMN codigo VARCHAR(12);

UPDATE presentacion pr
   SET codigo = 'PRD-' || lpad(o.n::text, 3, '0')
  FROM (SELECT x.id, row_number() OVER (ORDER BY f.nombre NULLS LAST, p.nombre, x.nombre, x.id) AS n
          FROM presentacion x
          JOIN producto p ON p.id = x.producto_id
          LEFT JOIN familia f ON f.id = p.familia_id) o
 WHERE o.id = pr.id;

SELECT setval('presentacion_codigo_seq', COALESCE((SELECT count(*) FROM presentacion), 0) + 1, false);

ALTER TABLE presentacion ALTER COLUMN codigo SET DEFAULT ('PRD-' || lpad(nextval('presentacion_codigo_seq')::text, 3, '0'));
ALTER TABLE presentacion ALTER COLUMN codigo SET NOT NULL;
CREATE UNIQUE INDEX uk_presentacion_codigo ON presentacion (codigo);
