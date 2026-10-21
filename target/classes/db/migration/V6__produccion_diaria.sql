-- Feature "Producción diaria" (área de producción de quesos): virtualiza el cuadro en papel
-- "CUADRO DE INFORME DE PRODUCCION DE QUESOS DIARIO" -- quesos diarios/reparados/de recorte, recorte en
-- kilos e insumos usados (cuajo, sacos de sal, etc.) por día calendario.
--
-- Insumos como catálogo + detalle relacional (no columnas fijas cuajo/sal): agregar un insumo nuevo a
-- futuro (ej. cloruro de calcio) no requiere otra migración, mismo criterio que tipo_queso/zona/unidad.
--
-- Módulo independiente de lote_produccion (decisión explícita): el cuadro en papel es un resumen
-- operativo del día, no la trazabilidad de un lote puntual contra registro_acopio.

CREATE TABLE insumo (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    nombre VARCHAR(60) NOT NULL UNIQUE,
    unidad_medida VARCHAR(20) NOT NULL,
    activo BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO insumo (nombre, unidad_medida) VALUES
    ('Cuajo', 'unidades'),
    ('Sal', 'sacos');

-- Un informe por día calendario (UNIQUE(fecha)); editable (ver informe_produccion_diario.actualizado_en) a
-- diferencia de registro_acopio, que es inmutable -- el papel se corrige a mano el mismo día (ej. "4 +2" en
-- recorte en kilos), así que el equivalente digital permite editar el mismo registro en vez de anexar
-- correcciones.
CREATE TABLE informe_produccion_diario (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    uuid_cliente VARCHAR(36) NOT NULL UNIQUE,
    fecha DATE NOT NULL UNIQUE,
    quesos_diarios INT NOT NULL,
    quesos_reparados INT NOT NULL DEFAULT 0,
    quesos_de_recorte INT NOT NULL DEFAULT 0,
    recorte_en_kilos NUMERIC(7,2),
    responsable_id UUID NOT NULL REFERENCES usuario(id),
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    actualizado_por_id UUID REFERENCES usuario(id),
    actualizado_en TIMESTAMP
);

-- Detalle de insumos usados por informe (cantidad por insumo). Entidad de asociación con atributo propio
-- (cantidad): no es un simple N:M como lote_registro_acopio, necesita su propia fila con id generado
-- (mismo criterio que el resto del esquema, ver punto_gps_jornada) en vez de PK compuesta.
CREATE TABLE informe_produccion_insumo (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    informe_id UUID NOT NULL REFERENCES informe_produccion_diario(id),
    insumo_id UUID NOT NULL REFERENCES insumo(id),
    cantidad NUMERIC(9,2) NOT NULL,
    UNIQUE (informe_id, insumo_id)
);
