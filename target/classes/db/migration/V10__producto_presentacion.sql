-- Fase 1 (Producto + Presentacion, decision aprobada): generaliza tipo_queso (antes exclusivo de queso) a
-- un catalogo de productos con familia administrable y presentaciones vendibles propias.
--
-- Alcance explicito de esta migracion (Fase 1 unicamente): SOLO catalogo. precio_queso,
-- movimiento_inventario, venta y lote_produccion siguen apuntando a producto (renombrado desde
-- tipo_queso, mismos datos, mismos ids, mismas relaciones), TODAVIA NO a presentacion -- esa reconexion es
-- una fase posterior (Fase 2 en adelante), deliberadamente fuera de esta migracion. Nada de esto genera
-- stock, precios ni movimientos nuevos: es una migracion puramente estructural.

-- 1) Familia: catalogo administrable desde la web, mismo patron que zona/unidad/motivo_observacion (tabla
-- simple id/nombre/activo + CRUD en AdminCatalogosController). El usuario autorizado crea familias nuevas
-- (Yogurt, Leche, Mantequilla, Dulce de leche...) sin tocar codigo ni SQL.
CREATE TABLE familia (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    nombre VARCHAR(60) NOT NULL UNIQUE,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);

-- Seed: los tipo_queso existentes migran a esta familia. No se inventa ninguna otra familia.
INSERT INTO familia (id, nombre) VALUES (uuid_generate_v4(), 'Queso');

-- 2) tipo_queso -> producto: generaliza el catalogo. Un RENAME conserva intactos todos los FK existentes
-- (venta, precio_queso, lote_produccion, movimiento_inventario) -- Postgres los resuelve por OID de tabla,
-- no por nombre; ningun dato ni ninguna relacion cambia.
ALTER TABLE tipo_queso RENAME TO producto;

ALTER TABLE producto ADD COLUMN familia_id UUID REFERENCES familia(id);
UPDATE producto SET familia_id = (SELECT id FROM familia WHERE nombre = 'Queso');
ALTER TABLE producto ALTER COLUMN familia_id SET NOT NULL;

-- rendimiento_esperado_pct y ciclo_capital son atributos de PRODUCCION DE QUESO especificamente (ver
-- LoteProduccionService), no comerciales -- dejan de ser obligatorios para poder crear productos de otras
-- familias (yogurt, leche, mantequilla, dulce de leche) sin inventarles un rendimiento que el negocio no ha
-- definido. Los quesos existentes conservan su valor tal cual, sin ningun cambio de dato.
ALTER TABLE producto ALTER COLUMN rendimiento_esperado_pct DROP NOT NULL;
ALTER TABLE producto ALTER COLUMN ciclo_capital DROP NOT NULL;

-- 3) Renombrar la FK en las tablas que ya referenciaban tipo_queso_id: son renames de columna, no cambian
-- ninguna relacion, ningun dato ni ningun indice/constraint existente -- Postgres los conserva bajo el
-- nuevo nombre automaticamente. Se hace explicito ahora que el esquema dejo de ser exclusivo de queso.
ALTER TABLE venta RENAME COLUMN tipo_queso_id TO producto_id;
ALTER TABLE precio_queso RENAME COLUMN tipo_queso_id TO producto_id;
ALTER TABLE lote_produccion RENAME COLUMN tipo_queso_id TO producto_id;
ALTER TABLE movimiento_inventario RENAME COLUMN tipo_queso_id TO producto_id;

-- Renombrar tambien los indices (cosmetico, mismo dato/misma columna, solo por claridad del esquema).
ALTER INDEX idx_movimiento_inventario_tipo_queso RENAME TO idx_movimiento_inventario_producto;
ALTER INDEX idx_precio_queso_tipo_modalidad RENAME TO idx_precio_queso_producto_modalidad;

-- 4) Presentacion: como se cuenta/almacena/vende cada producto (ej. "Botella 1 L", "Pieza"). En esta fase
-- existe SOLO como catalogo administrable dentro de la ficha del producto -- todavia NO es el destino de
-- precio_queso/movimiento_inventario/venta/lote_produccion (reconexion de una fase posterior). Crear una
-- presentacion no genera stock ni precio.
CREATE TABLE presentacion (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    producto_id UUID NOT NULL REFERENCES producto(id),
    nombre VARCHAR(60) NOT NULL,
    -- Contenido: descriptivo, NUNCA inventado. Una "Pieza" sin peso/volumen declarado queda con ambos NULL.
    contenido_cantidad NUMERIC(10,3),
    contenido_unidad VARCHAR(10),
    CONSTRAINT chk_presentacion_contenido_unidad CHECK (contenido_unidad IS NULL OR contenido_unidad IN ('ML','L','G','KG')),
    -- Como se cuenta la unidad discreta (botella, frasco, pieza, envase...): vocabulario abierto, decision
    -- de quien registra el producto -- a diferencia de familia, no se modela como catalogo aparte.
    unidad_conteo VARCHAR(30) NOT NULL,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (producto_id, nombre)
);

CREATE INDEX idx_presentacion_producto ON presentacion(producto_id);

-- Migracion de historico (decision aprobada, sin inventar datos): cada producto existente (todo tipo_queso
-- hoy) recibe UNA presentacion inicial "Unidad", sin contenido declarado -- no se inventa que todos los
-- quesos pesan 500 g o 1 kg. Esta presentacion no es referenciada todavia por ningun movimiento, precio o
-- venta en esta fase (eso es una ampliacion posterior); existe solo para que la ficha de cada producto
-- migrado no aparezca vacia.
INSERT INTO presentacion (producto_id, nombre, unidad_conteo)
SELECT id, 'Unidad', 'unidad' FROM producto;
