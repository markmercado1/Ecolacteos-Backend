-- Recetas de produccion + ciclo de vida planificada/real del lote.
--
-- Una receta describe como fabricar UN producto (salida en UNA de sus presentaciones) a partir de una
-- cantidad BASE de leche (la materia prima principal de planta, que vive en su propio libro mayor
-- movimiento_inventario_leche, V8 -- NO se duplica como fila de insumo). Los insumos referencian el
-- catalogo existente (insumo, V6/V17): no se crea un segundo inventario. Todas las cantidades de la receta
-- estan expresadas "por litros_base"; el calculo proporcional (factor = litros / litros_base) lo hace
-- RecetaService, nunca la base de datos.
--
-- producto_id/presentacion_id NO se editan despues de crear la receta (ver RecetaService): un lote
-- planificado conserva su receta_id y siempre resuelve la misma presentacion de salida.
CREATE TABLE receta (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    nombre VARCHAR(100) NOT NULL,
    producto_id UUID NOT NULL REFERENCES producto(id),
    presentacion_id UUID NOT NULL REFERENCES presentacion(id),
    litros_base NUMERIC(9,2) NOT NULL CHECK (litros_base > 0),
    rendimiento_base NUMERIC(12,3) NOT NULL CHECK (rendimiento_base > 0),
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    actualizado_en TIMESTAMP,
    UNIQUE (producto_id, nombre)
);
CREATE INDEX idx_receta_producto ON receta(producto_id);

-- Cantidad de cada insumo POR litros_base de la receta, en la unidad_medida del propio insumo (no se
-- convierten unidades). Mismo shape que informe_produccion_insumo/lote_produccion_insumo: id propio,
-- cantidad positiva, un insumo no se repite dentro de la misma receta.
CREATE TABLE receta_insumo (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    receta_id UUID NOT NULL REFERENCES receta(id),
    insumo_id UUID NOT NULL REFERENCES insumo(id),
    cantidad NUMERIC(12,3) NOT NULL CHECK (cantidad > 0),
    UNIQUE (receta_id, insumo_id)
);
CREATE INDEX idx_receta_insumo_receta ON receta_insumo(receta_id);

-- Ciclo de vida del lote: PLANIFICADA -> EN_PRODUCCION -> FINALIZADA, o PLANIFICADA -> CANCELADA.
-- Todos los lotes existentes (y los que sigue creando la app movil por POST /api/lotes-produccion, que
-- consume y registra el resultado en un solo paso) son FINALIZADA: el DEFAULT los clasifica sin
-- reinterpretar su historial.
--
-- cantidad_esperada: snapshot de la salida esperada por la receta al planificar (rendimiento_base x factor),
-- en unidades de la presentacion de la receta. NULL para lotes sin receta. Nunca se recalcula si la
-- receta cambia despues. La cantidad REAL obtenida sigue siendo SUM(lote_produccion_detalle), registrada
-- solo al finalizar -- esperado y real nunca comparten columna.
ALTER TABLE lote_produccion
    ADD COLUMN estado VARCHAR(15) NOT NULL DEFAULT 'FINALIZADA',
    ADD COLUMN receta_id UUID REFERENCES receta(id),
    ADD COLUMN cantidad_esperada NUMERIC(12,3),
    ADD COLUMN iniciado_en TIMESTAMP,
    ADD COLUMN finalizado_en TIMESTAMP,
    ADD COLUMN cancelado_en TIMESTAMP,
    ADD CONSTRAINT chk_lote_produccion_estado
        CHECK (estado IN ('PLANIFICADA', 'EN_PRODUCCION', 'FINALIZADA', 'CANCELADA')),
    ADD CONSTRAINT chk_lote_produccion_cantidad_esperada
        CHECK (cantidad_esperada IS NULL OR (cantidad_esperada >= 0 AND receta_id IS NOT NULL));

CREATE INDEX idx_lote_produccion_estado ON lote_produccion(estado);
CREATE INDEX idx_lote_produccion_receta ON lote_produccion(receta_id);
