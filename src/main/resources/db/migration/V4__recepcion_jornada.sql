-- Fase 2 (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend): vincula RecepcionPlanta con Jornada.
-- Migracion aditiva: ninguna fila historica pierde datos, jornada_id/uuid_cliente/hora_llegada/observacion
-- son NULLable -- las conciliaciones anteriores (sin jornada) siguen siendo validas y consultables tal cual.
ALTER TABLE recepcion_planta ADD COLUMN jornada_id UUID REFERENCES jornada(id);
ALTER TABLE recepcion_planta ADD COLUMN uuid_cliente VARCHAR(36);
ALTER TABLE recepcion_planta ADD COLUMN hora_llegada TIMESTAMP;
ALTER TABLE recepcion_planta ADD COLUMN observacion TEXT;

-- Idempotencia offline-first para el flujo nuevo (unico consumidor de uuid_cliente en esta tabla): las
-- conciliaciones historicas nunca lo tuvieron y siguen NULL -- Postgres permite multiples NULL bajo UNIQUE.
CREATE UNIQUE INDEX idx_recepcion_planta_uuid_cliente ON recepcion_planta(uuid_cliente) WHERE uuid_cliente IS NOT NULL;

-- Cerrojo real (condicion de carrera entre dos dispositivos recibiendo la misma jornada), mismo patron que
-- idx_jornada_activa_por_acopiador/unidad de V3__jornada.sql: indice parcial unico, no una columna UNIQUE
-- simple, porque jornada_id es NULL para todo el historico sin jornada.
CREATE UNIQUE INDEX idx_recepcion_planta_jornada_unica ON recepcion_planta(jornada_id) WHERE jornada_id IS NOT NULL;

CREATE INDEX idx_recepcion_planta_jornada ON recepcion_planta(jornada_id);
