-- Fase 2 (Precios por presentación, decision aprobada): relaciona el precio de queso con Presentacion en
-- vez de Producto. Cada presentacion tiene sus propias tarifas por modalidad -- nunca se deduce el precio
-- de una presentacion a partir de otra.
--
-- Alcance explicito de esta migracion: SOLO precios. movimiento_inventario y venta siguen apuntando a
-- producto (el stock/existencias todavia no estan diferenciadas por presentacion -- esa reconexion es
-- Fase 3, fuera de esta migracion). No se toca venta ni movimiento_inventario aqui.

-- 1) Tabla nueva precio_presentacion: mismo shape que precio_queso, ahora referenciando presentacion.
CREATE TABLE precio_presentacion (
    id UUID PRIMARY KEY,
    presentacion_id UUID NOT NULL REFERENCES presentacion(id),
    modalidad VARCHAR(15) NOT NULL,
    precio NUMERIC(8,2) NOT NULL,
    vigente_desde DATE NOT NULL,
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_precio_presentacion_modalidad CHECK (modalidad IN ('MAYORISTA', 'PROVEEDOR', 'PUBLICO')),
    UNIQUE (presentacion_id, modalidad, vigente_desde)
);

CREATE INDEX idx_precio_presentacion_presentacion_modalidad ON precio_presentacion(presentacion_id, modalidad);

-- 2) Migrar el historico existente: cada precio_queso (por producto) mapea a la presentacion "Unidad" de
-- ese producto -- la MISMA presentacion inicial que V10 creo para representar su stock/producto no
-- diferenciado (ver V10__producto_presentacion.sql). Mapeo inequivoco por NOMBRE EXACTO 'Unidad', nunca
-- "la primera presentacion que exista": si el producto ya tiene mas presentaciones agregadas despues de
-- V10 (vía la ficha del producto), esas nuevas presentaciones NO reciben precio automaticamente -- conservan
-- el mismo estado "sin precio configurado" que tendria cualquier presentacion nueva. Se preserva el mismo
-- id de cada fila migrada (mismos identificadores, mismos importes, misma modalidad, misma vigencia).
INSERT INTO precio_presentacion (id, presentacion_id, modalidad, precio, vigente_desde, creado_en)
SELECT pq.id, pr.id, pq.modalidad, pq.precio, pq.vigente_desde, pq.creado_en
FROM precio_queso pq
JOIN presentacion pr ON pr.producto_id = pq.producto_id AND pr.nombre = 'Unidad';

-- 3) Verificacion de integridad explicita: si el mapeo no cubriera exactamente todas las filas de
-- precio_queso (no deberia ocurrir -- V10 crea 'Unidad' para todo producto existente y nunca la elimina,
-- solo la desactiva), la migracion FALLA en vez de continuar en silencio con datos perdidos.
DO $$
DECLARE
    total_origen INT;
    total_migrado INT;
BEGIN
    SELECT COUNT(*) INTO total_origen FROM precio_queso;
    SELECT COUNT(*) INTO total_migrado FROM precio_presentacion;
    IF total_origen <> total_migrado THEN
        RAISE EXCEPTION 'V11: % filas en precio_queso pero solo % migradas a precio_presentacion -- mapeo incompleto, revisar presentaciones "Unidad" faltantes', total_origen, total_migrado;
    END IF;
END $$;

-- 4) precio_queso ya migrada por completo a precio_presentacion (mismos ids, mismos datos) -- se elimina.
-- No se conserva como tabla paralela: mantener dos fuentes de verdad para el mismo historico es justo la
-- ambiguedad que se pidio evitar (ver REGLAS.md, jerarquia de fuentes).
DROP TABLE precio_queso;
