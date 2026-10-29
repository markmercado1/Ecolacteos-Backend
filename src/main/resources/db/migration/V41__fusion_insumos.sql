-- Fusion de insumos duplicados y unicidad normalizada del nombre.
--
-- "azucar" y "Azúcar" son el mismo insumo: partirlo en dos separa su stock y su kardex. Esta migracion:
--   * norm_texto(): la MISMA clave que BusquedaTexto.clave() en Java (sin mayusculas, tildes ni espacios sobrantes);
--   * insumo.nombre_normalizado: columna generada con esa clave;
--   * insumo.fusionado_en_id: un insumo fusionado queda INACTIVO, con stock 0 y apuntando al insumo que lo absorbio;
--   * fusion_insumo: auditoria de cada fusion (quien, cuando, que se reasigno, con los ids para poder reconstruirla);
--   * asegurar_indice_unico_insumo(): crea el indice unico del nombre normalizado SOLO si no hay duplicados. Hoy puede
--     haberlos (azucar / Azúcar), asi que la migracion no falla: avisa y el indice se crea solo cuando, tras fusionar, ya
--     no queda ninguno (la herramienta de fusion vuelve a llamar a esta funcion).
--
-- Migracion ADITIVA: no modifica ni borra ninguna fila existente.
--
-- Reversa: DROP FUNCTION asegurar_indice_unico_insumo(); DROP INDEX IF EXISTS uq_insumo_nombre_normalizado;
--          DROP TABLE fusion_insumo; ALTER TABLE insumo DROP COLUMN fusionado_en_id, DROP COLUMN nombre_normalizado;
--          DROP FUNCTION norm_texto(text);  (y borrar la fila 41 de flyway_schema_history).
--          Una fusion YA realizada no se deshace con esto: sus filas reasignadas quedan en el insumo destino
--          (los ids estan en fusion_insumo.detalle_json).

CREATE FUNCTION norm_texto(t text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT lower(translate(btrim(regexp_replace(t, '\s+', ' ', 'g')),
                           'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunaeiouun'))
$$;

ALTER TABLE insumo ADD COLUMN nombre_normalizado TEXT GENERATED ALWAYS AS (norm_texto(nombre)) STORED;
ALTER TABLE insumo ADD COLUMN fusionado_en_id UUID REFERENCES insumo(id);

ALTER TABLE insumo ADD CONSTRAINT ck_insumo_fusionado CHECK (
    fusionado_en_id IS NULL OR (fusionado_en_id <> id AND activo = FALSE AND stock = 0));

CREATE TABLE fusion_insumo (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    insumo_origen_id UUID NOT NULL REFERENCES insumo(id),
    insumo_destino_id UUID NOT NULL REFERENCES insumo(id),
    nombre_origen VARCHAR(60) NOT NULL,
    nombre_destino VARCHAR(60) NOT NULL,
    unidad_medida VARCHAR(20) NOT NULL,
    stock_origen NUMERIC(12,2) NOT NULL,
    stock_destino_antes NUMERIC(12,2) NOT NULL,
    stock_destino_despues NUMERIC(12,2) NOT NULL,
    movimientos INT NOT NULL,
    retiros INT NOT NULL,
    recetas INT NOT NULL,
    lotes_produccion INT NOT NULL,
    informes INT NOT NULL,
    -- ids de todo lo que se reasigno (movimientos, retiros, filas de receta, de lote de produccion y de informe)
    detalle_json TEXT NOT NULL,
    usuario_id UUID NOT NULL REFERENCES usuario(id),
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_fusion_insumo_distintos CHECK (insumo_origen_id <> insumo_destino_id)
);
CREATE INDEX idx_fusion_insumo_destino ON fusion_insumo(insumo_destino_id);
CREATE INDEX idx_fusion_insumo_origen ON fusion_insumo(insumo_origen_id);

-- Devuelve TRUE si el indice unico existe al terminar; FALSE (con un aviso) si todavia hay nombres repetidos.
CREATE FUNCTION asegurar_indice_unico_insumo() RETURNS boolean
    LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = current_schema() AND indexname = 'uq_insumo_nombre_normalizado') THEN
        RETURN TRUE;
    END IF;
    IF EXISTS (SELECT 1 FROM insumo WHERE fusionado_en_id IS NULL GROUP BY nombre_normalizado HAVING count(*) > 1) THEN
        RAISE NOTICE 'Hay insumos con el mismo nombre normalizado: fusionalos para activar la unicidad en la base de datos.';
        RETURN FALSE;
    END IF;
    CREATE UNIQUE INDEX uq_insumo_nombre_normalizado ON insumo (nombre_normalizado) WHERE fusionado_en_id IS NULL;
    RETURN TRUE;
END
$$;

SELECT asegurar_indice_unico_insumo();
