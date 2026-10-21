-- Fase 1 (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend): entidad Jornada -- agrupa los
-- RegistroAcopio de un mismo recorrido de recoleccion de un acopiador con un vehiculo. Migracion aditiva:
-- no toca filas existentes de registro_acopio (jornada_id nullable, compatibilidad con el historico sin
-- jornada) ni ninguna otra tabla.
CREATE TABLE jornada (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    uuid_cliente VARCHAR(36) NOT NULL UNIQUE,
    acopiador_id UUID NOT NULL REFERENCES usuario(id),
    unidad_id UUID NOT NULL REFERENCES unidad(id),
    estado VARCHAR(20) NOT NULL DEFAULT 'EN_RECOLECCION'
        CHECK (estado IN ('EN_RECOLECCION','REGRESANDO_A_PLANTA','RECIBIDA','CANCELADA')),
    hora_inicio TIMESTAMP NOT NULL,
    gps_lat_inicio NUMERIC(9,6),
    gps_lng_inicio NUMERIC(9,6),
    hora_fin TIMESTAMP,
    gps_lat_fin NUMERIC(9,6),
    gps_lng_fin NUMERIC(9,6),
    motivo_cancelacion TEXT,
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_jornada_acopiador ON jornada(acopiador_id);
CREATE INDEX idx_jornada_unidad ON jornada(unidad_id);

-- Una sola jornada ACTIVA (EN_RECOLECCION o REGRESANDO_A_PLANTA) por acopiador y por unidad (ESPEC §5).
-- Indice parcial unico: es el cerrojo real contra condiciones de carrera (dos dispositivos creando a la
-- vez); JornadaService hace ademas una verificacion previa para devolver un mensaje 409 legible en el
-- caso normal (sin carrera).
CREATE UNIQUE INDEX idx_jornada_activa_por_acopiador ON jornada(acopiador_id)
    WHERE estado IN ('EN_RECOLECCION','REGRESANDO_A_PLANTA');
CREATE UNIQUE INDEX idx_jornada_activa_por_unidad ON jornada(unidad_id)
    WHERE estado IN ('EN_RECOLECCION','REGRESANDO_A_PLANTA');

-- Asociacion opcional de cada RegistroAcopio con la jornada durante la cual se capturo. NULL para todo el
-- historico existente y para cualquier captura futura sin jornada activa (ESPEC §23: nunca se obliga a
-- migrar el historico).
ALTER TABLE registro_acopio ADD COLUMN jornada_id UUID REFERENCES jornada(id);
CREATE INDEX idx_registro_acopio_jornada ON registro_acopio(jornada_id);
