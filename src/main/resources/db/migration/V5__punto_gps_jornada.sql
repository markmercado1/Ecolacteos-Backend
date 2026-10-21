-- Fase 3 (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend): puntos de recorrido GPS capturados
-- periodicamente durante una jornada EN_RECOLECCION. Migracion aditiva: no toca ninguna tabla ni fila
-- existente. Un punto pertenece a exactamente una jornada (FK simple, igual criterio que
-- registro_acopio.jornada_id de V3__jornada.sql) -- el acopiador/vehiculo del punto se infieren de la
-- jornada, no se duplican acá.
CREATE TABLE punto_gps_jornada (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    uuid_cliente VARCHAR(36) NOT NULL UNIQUE,
    jornada_id UUID NOT NULL REFERENCES jornada(id),
    lat NUMERIC(9,6) NOT NULL,
    lng NUMERIC(9,6) NOT NULL,
    -- Precision del fix (metros), solo cuando la API de ubicacion del dispositivo la provee
    -- (Location.getAccuracy() en Android) -- NULL cuando no esta disponible, nunca inventada.
    precision_m NUMERIC(7,2),
    capturado_en TIMESTAMP NOT NULL,
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);

-- Orden cronologico de los puntos de una jornada: es el acceso principal (linea de tiempo, Haversine,
-- deteccion de paradas -- todo se computa localmente en el movil a partir de puntos ordenados, pero el
-- mismo indice sirve para cuando la futura web consulte esta tabla directamente, ESPEC §17).
CREATE INDEX idx_punto_gps_jornada_jornada ON punto_gps_jornada(jornada_id, capturado_en);
