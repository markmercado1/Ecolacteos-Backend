-- Fase 2 del refactor de Produccion (ACOPIO -> RECEPCION -> EXISTENCIA DE LECHE -> PRODUCCION): consumo de
-- leche por lote (trazabilidad FIFO automatica contra recepcion_planta, sin checkboxes de registro_acopio) +
-- snapshot del rendimiento esperado al momento de producir.
--
-- lote_registro_acopio (V1) NO se toca ni se reinterpreta: sigue siendo la trazabilidad real de los lotes
-- creados antes de esta migracion. Esta tabla nueva es la trazabilidad de los lotes creados EN ADELANTE.
--
-- Entidad de asociacion con atributo propio (litros_consumidos), mismo criterio que
-- informe_produccion_insumo (V6): id propio generado, no PK compuesta. Deliberadamente SIN restriccion que
-- impida que una misma recepcion aparezca en varias filas de lotes distintos, ni que un mismo lote consuma
-- de varias recepciones -- el unico UNIQUE es (lote_id, recepcion_planta_id), para que un lote nunca
-- acumule dos filas de consumo contra la misma recepcion (se consolida en una sola por diseno del service).
CREATE TABLE lote_produccion_recepcion_consumo (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    lote_id UUID NOT NULL REFERENCES lote_produccion(id),
    recepcion_planta_id UUID NOT NULL REFERENCES recepcion_planta(id),
    litros_consumidos NUMERIC(9,2) NOT NULL CHECK (litros_consumidos > 0),
    UNIQUE (lote_id, recepcion_planta_id)
);

CREATE INDEX idx_lote_recepcion_consumo_lote ON lote_produccion_recepcion_consumo(lote_id);
CREATE INDEX idx_lote_recepcion_consumo_recepcion ON lote_produccion_recepcion_consumo(recepcion_planta_id);

-- Snapshot del rendimiento esperado y de las unidades esperadas al momento de producir (nuevo modelo
-- funcional): NULL para lotes creados antes de esta migracion -- siguen mostrando solo el rendimiento real
-- ya calculado, igual que hoy, sin reinterpretar el historial. Un cambio futuro en
-- tipo_queso.rendimiento_esperado_pct nunca reescribe silenciosamente el "esperado" de un lote ya producido.
ALTER TABLE lote_produccion
    ADD COLUMN unidades_esperadas INT,
    ADD COLUMN rendimiento_esperado_pct_snapshot NUMERIC(5,2);
