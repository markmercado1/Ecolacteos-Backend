-- =========================================================
-- Sistema de Acopio Lechero — Script de creación de BD
-- PostgreSQL 15+
-- Versión: Fase 2 (incorpora las 7 mejoras aprobadas sobre
-- el script 2.3.2 original de CORE_DESIGN.md §6)
--
-- Mejoras aplicadas en esta versión:
--   1. uuid_cliente en analisis_calidad, lote_produccion, venta
--   2. unidad.responsable_id -> NOT NULL
--   3. comunicado_zona reemplaza comunicado.zonas_destino
--   4. tabla puente pago_registro_acopio
--   5. UNIQUE(zona_id, orden) en ruta_proveedor_orden
--   6. columna activo en proveedor y tipo_queso
--   (la 7ma mejora, litros_campo=0 -> ALERTA, es regla de
--    negocio en ConciliacionService, no cambia el esquema)
-- =========================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE rol (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    nombre VARCHAR(30) NOT NULL UNIQUE
);

INSERT INTO rol (nombre) VALUES
                             ('ADMIN'),
                             ('ACOPIADOR'),
                             ('CALIDAD'),
                             ('RECEPCION'),
                             ('PRODUCCION'),
                             ('VENTAS')
    ON CONFLICT (nombre) DO NOTHING;

CREATE TABLE usuario (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    nombre VARCHAR(120) NOT NULL,
    email VARCHAR(150) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    rol_id UUID NOT NULL REFERENCES rol(id),
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE zona (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    nombre VARCHAR(80) NOT NULL,
    activa BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE unidad (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    placa VARCHAR(20) NOT NULL UNIQUE,
    capacidad_ton NUMERIC(5,2),
    zona_id UUID REFERENCES zona(id),
    responsable_id UUID NOT NULL REFERENCES usuario(id)  -- mejora #2 (antes: nullable)
);

CREATE TABLE proveedor (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    nombre VARCHAR(150) NOT NULL,
    dni VARCHAR(15) NOT NULL UNIQUE,
    telefono VARCHAR(20),
    zona_actual_id UUID REFERENCES zona(id),
    codigo_qr VARCHAR(64) UNIQUE,
    activo BOOLEAN NOT NULL DEFAULT TRUE,               -- mejora #6
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE historial_zona (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    proveedor_id UUID NOT NULL REFERENCES proveedor(id),
    zona_id UUID NOT NULL REFERENCES zona(id),
    desde DATE NOT NULL,
    hasta DATE
);
CREATE INDEX idx_historial_zona_proveedor ON historial_zona(proveedor_id);

CREATE TABLE ruta_proveedor_orden (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    zona_id UUID NOT NULL REFERENCES zona(id),
    proveedor_id UUID NOT NULL REFERENCES proveedor(id),
    orden INT NOT NULL,
    hora_estimada TIME,
    UNIQUE(zona_id, proveedor_id),
    UNIQUE(zona_id, orden)                               -- mejora #5
);

CREATE TABLE motivo_observacion (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    descripcion VARCHAR(120) NOT NULL UNIQUE,
    activo BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO motivo_observacion (descripcion) VALUES
    ('Leche rechazada'), ('Proveedor no entregó'), ('Recipiente mal medido (disputa)'),
    ('Entrega parcial'), ('Otro');

CREATE TABLE tipo_queso (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    nombre VARCHAR(60) NOT NULL UNIQUE,
    rendimiento_esperado_pct NUMERIC(5,2) NOT NULL,
    ciclo_capital VARCHAR(12) NOT NULL DEFAULT 'RAPIDO'
        CHECK (ciclo_capital IN ('RAPIDO','MADURACION')),
    activo BOOLEAN NOT NULL DEFAULT TRUE                -- mejora #6
);

CREATE TABLE configuracion_sistema (
    clave VARCHAR(60) PRIMARY KEY,
    valor VARCHAR(255) NOT NULL,
    descripcion VARCHAR(255),
    actualizado_en TIMESTAMP NOT NULL DEFAULT now()
);
INSERT INTO configuracion_sistema (clave, valor, descripcion) VALUES
    ('UMBRAL_CONCILIACION_PCT', '2.5', 'Diferencia % campo-planta que dispara ALERTA (RF-REC-03)'),
    ('CAPACIDAD_PROCESAMIENTO_DIARIA_LITROS', '5000', 'Capacidad diaria de planta, usada por el forecasting (RF-INN-04)'),
    ('UMBRAL_ANOMALIA_Z_SCORE', '2.0', 'Z-score a partir del cual se alerta un registro atípico (RF-INN-03)');

CREATE TABLE registro_acopio (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    uuid_cliente VARCHAR(36) NOT NULL UNIQUE,
    proveedor_id UUID NOT NULL REFERENCES proveedor(id),
    acopiador_id UUID NOT NULL REFERENCES usuario(id),
    unidad_id UUID NOT NULL REFERENCES unidad(id),
    fecha_hora TIMESTAMP NOT NULL,
    litros NUMERIC(8,2) NOT NULL CHECK (litros >= 0),
    gps_lat NUMERIC(9,6),
    gps_lng NUMERIC(9,6),
    motivo_observacion_id UUID REFERENCES motivo_observacion(id),
    litros_por_voz BOOLEAN NOT NULL DEFAULT FALSE,
    foto_url VARCHAR(255),
    sincronizado_en TIMESTAMP NOT NULL DEFAULT now(),
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_registro_acopio_proveedor ON registro_acopio(proveedor_id);
CREATE INDEX idx_registro_acopio_fecha ON registro_acopio(fecha_hora);
CREATE INDEX idx_registro_acopio_unidad_fecha ON registro_acopio(unidad_id, fecha_hora);

CREATE TABLE correccion_registro (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    registro_acopio_id UUID NOT NULL REFERENCES registro_acopio(id),
    usuario_id UUID NOT NULL REFERENCES usuario(id),
    litros_anterior NUMERIC(8,2) NOT NULL,
    litros_corregido NUMERIC(8,2) NOT NULL,
    motivo TEXT,
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE analisis_calidad (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    uuid_cliente VARCHAR(36) NOT NULL UNIQUE,            -- mejora #1
    registro_acopio_id UUID NOT NULL REFERENCES registro_acopio(id),
    folio_muestra VARCHAR(40) NOT NULL,
    agua NUMERIC(5,2),
    proteina NUMERIC(5,2),
    lactosa NUMERIC(5,2),
    densidad NUMERIC(6,2),
    temperatura NUMERIC(5,2),
    ph NUMERIC(4,2),
    agua_anadida BOOLEAN NOT NULL DEFAULT FALSE,
    resultado VARCHAR(15) NOT NULL DEFAULT 'APROBADO'
        CHECK (resultado IN ('APROBADO','RECHAZADO','OBSERVADO')),
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_analisis_registro ON analisis_calidad(registro_acopio_id);
CREATE INDEX idx_analisis_folio ON analisis_calidad(folio_muestra);

CREATE TABLE recepcion_planta (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    fecha DATE NOT NULL,
    turno VARCHAR(10) NOT NULL DEFAULT 'UNICO',
    unidad_id UUID NOT NULL REFERENCES unidad(id),
    litros_campo NUMERIC(9,2) NOT NULL,
    litros_planta NUMERIC(9,2) NOT NULL,
    diferencia_pct NUMERIC(5,2) GENERATED ALWAYS AS (
        CASE WHEN litros_campo = 0 THEN 0
        ELSE ROUND(((litros_planta - litros_campo) / litros_campo) * 100, 2) END
    ) STORED,
    responsable_id UUID NOT NULL REFERENCES usuario(id),
    estado VARCHAR(10) NOT NULL DEFAULT 'OK' CHECK (estado IN ('OK','ALERTA')),
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE(fecha, unidad_id, turno)
);

-- Fase 3, decision de negocio 3 (opcion B, confirmada): trazabilidad exacta
-- entre una recepcion y los registro_acopio individuales que la componen --
-- mismo patron que pago_registro_acopio. Seleccion explicita: RECEPCION
-- indica registro_acopio_ids al conciliar (igual que LoteProduccion), no se
-- resuelve automaticamente por unidad+fecha (registro_acopio no distingue
-- turno).
CREATE TABLE recepcion_planta_registro_acopio (
    recepcion_planta_id UUID NOT NULL REFERENCES recepcion_planta(id),
    registro_acopio_id UUID NOT NULL REFERENCES registro_acopio(id),
    PRIMARY KEY (recepcion_planta_id, registro_acopio_id)
);

CREATE TABLE lote_produccion (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    uuid_cliente VARCHAR(36) NOT NULL UNIQUE,            -- mejora #1
    fecha DATE NOT NULL,
    tipo_queso_id UUID NOT NULL REFERENCES tipo_queso(id),
    litros_usados NUMERIC(9,2) NOT NULL,
    unidades_obtenidas INT NOT NULL,
    rendimiento_pct NUMERIC(5,2),
    responsable_id UUID NOT NULL REFERENCES usuario(id),
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);

-- N:M RegistroAcopio <-> LoteProduccion. En v1 se restringe por regla de negocio
-- (ver LoteProduccionService) a que cada registro_acopio pertenezca a un único
-- lote; el esquema N:M se mantiene para no cerrar la puerta a un reparto futuro.
CREATE TABLE lote_registro_acopio (
    lote_id UUID NOT NULL REFERENCES lote_produccion(id),
    registro_acopio_id UUID NOT NULL REFERENCES registro_acopio(id),
    PRIMARY KEY (lote_id, registro_acopio_id)
);

CREATE TABLE precio_litro (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    precio NUMERIC(6,3) NOT NULL,
    vigente_desde DATE NOT NULL
);

CREATE TABLE pago (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    proveedor_id UUID NOT NULL REFERENCES proveedor(id),
    semana_inicio DATE NOT NULL,
    semana_fin DATE NOT NULL,
    litros_totales NUMERIC(9,2) NOT NULL,
    precio_litro NUMERIC(6,3) NOT NULL,
    total NUMERIC(10,2) GENERATED ALWAYS AS (litros_totales * precio_litro) STORED,
    comprobante_generado BOOLEAN NOT NULL DEFAULT FALSE,
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE(proveedor_id, semana_inicio)
);
CREATE INDEX idx_pago_proveedor ON pago(proveedor_id);

-- mejora #4: trazabilidad exacta entre un pago y los registro_acopio que liquida
CREATE TABLE pago_registro_acopio (
    pago_id UUID NOT NULL REFERENCES pago(id),
    registro_acopio_id UUID NOT NULL REFERENCES registro_acopio(id),
    PRIMARY KEY (pago_id, registro_acopio_id)
);

-- Fase 3, decision de negocio 1 (opcion C, confirmada): una CorreccionRegistro
-- anexada sobre un registro_acopio ya liquidado NO modifica el Pago original
-- (sigue inmutable) -- genera un ajuste separado, auditable, que ADMIN salda
-- manualmente. precio_litro es siempre el del Pago original (decision
-- confirmada), nunca el vigente al momento de la correccion.
CREATE TABLE ajuste_pago (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    pago_id UUID NOT NULL REFERENCES pago(id),
    registro_acopio_id UUID NOT NULL REFERENCES registro_acopio(id),
    correccion_registro_id UUID NOT NULL UNIQUE REFERENCES correccion_registro(id),
    litros_diferencia NUMERIC(8,2) NOT NULL,
    precio_litro NUMERIC(6,3) NOT NULL,
    monto NUMERIC(10,2) GENERATED ALWAYS AS (litros_diferencia * precio_litro) STORED,
    estado VARCHAR(12) NOT NULL DEFAULT 'PENDIENTE' CHECK (estado IN ('PENDIENTE','LIQUIDADO')),
    pago_liquidacion_id UUID REFERENCES pago(id),
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_ajuste_pago_pago ON ajuste_pago(pago_id);
CREATE INDEX idx_ajuste_pago_estado ON ajuste_pago(estado);

CREATE TABLE venta (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    uuid_cliente VARCHAR(36) NOT NULL UNIQUE,            -- mejora #1
    fecha DATE NOT NULL,
    tipo_cliente VARCHAR(15) NOT NULL CHECK (tipo_cliente IN ('MAYORISTA','PROVEEDOR','PUBLICO')),
    tipo_queso_id UUID NOT NULL REFERENCES tipo_queso(id),
    cantidad INT NOT NULL,
    precio_unitario NUMERIC(8,2) NOT NULL,
    total NUMERIC(10,2) GENERATED ALWAYS AS (cantidad * precio_unitario) STORED,
    vendedor_id UUID NOT NULL REFERENCES usuario(id)
);

CREATE TABLE comunicado (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    mensaje TEXT NOT NULL,
    fecha TIMESTAMP NOT NULL DEFAULT now()
    -- mejora #3: zonas_destino (TEXT libre) eliminado, reemplazado por comunicado_zona
);

-- mejora #3
CREATE TABLE comunicado_zona (
    comunicado_id UUID NOT NULL REFERENCES comunicado(id),
    zona_id UUID NOT NULL REFERENCES zona(id),
    PRIMARY KEY (comunicado_id, zona_id)
);

CREATE TABLE comunicado_confirmacion (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    comunicado_id UUID NOT NULL REFERENCES comunicado(id),
    proveedor_id UUID NOT NULL REFERENCES proveedor(id),
    acopiador_id UUID NOT NULL REFERENCES usuario(id),
    confirmado_en TIMESTAMP NOT NULL DEFAULT now()
);

-- =========================================================
-- Módulo de innovación (RF-INN-01 a RF-INN-05)
-- =========================================================

CREATE TABLE score_confianza_proveedor (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    proveedor_id UUID NOT NULL REFERENCES proveedor(id),
    periodo DATE NOT NULL,
    score NUMERIC(5,2) NOT NULL CHECK (score BETWEEN 0 AND 100),
    componente_calidad NUMERIC(5,2) NOT NULL,
    componente_regularidad NUMERIC(5,2) NOT NULL,
    componente_anomalias NUMERIC(5,2) NOT NULL,
    calculado_en TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE(proveedor_id, periodo)
);
CREATE INDEX idx_score_proveedor ON score_confianza_proveedor(proveedor_id);

CREATE TABLE alerta_anomalia (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    registro_acopio_id UUID NOT NULL REFERENCES registro_acopio(id),
    tipo VARCHAR(25) NOT NULL CHECK (tipo IN ('VOLUMEN_ATIPICO','RIESGO_ADULTERACION')),
    z_score NUMERIC(6,3),
    severidad VARCHAR(10) NOT NULL CHECK (severidad IN ('BAJA','MEDIA','ALTA')),
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_alerta_registro ON alerta_anomalia(registro_acopio_id);

CREATE TABLE prediccion_zona (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    zona_id UUID NOT NULL REFERENCES zona(id),
    semana_inicio DATE NOT NULL,
    litros_estimados NUMERIC(10,2) NOT NULL,
    litros_reales NUMERIC(10,2),
    generado_en TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE(zona_id, semana_inicio)
);

CREATE TABLE prediccion_proveedor (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    proveedor_id UUID NOT NULL REFERENCES proveedor(id),
    fecha_prevista DATE NOT NULL,
    litros_estimados_min NUMERIC(8,2) NOT NULL,
    litros_estimados_max NUMERIC(8,2) NOT NULL,
    generado_en TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_prediccion_proveedor ON prediccion_proveedor(proveedor_id);
