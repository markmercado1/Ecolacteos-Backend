-- Fase H (padrón de clientes y comprador de cada venta). Migración ADITIVA: no crea fichas a partir de ventas ni reservas
-- existentes (decisión del usuario), no cambia precios, importes ni estados.
--
--   * cliente: ficha VOLUNTARIA de un comprador (persona natural o empresa). DNI/RUC opcionales (identificador fuerte cuando
--     existen, únicos entre fichas vigentes). Un proveedor que compra NO tiene ficha de cliente: se usa su ficha de proveedor.
--     `mayorista` solo lo habilita ADMIN (con quién y cuándo). Una ficha fusionada queda inactiva y apunta a su destino.
--   * cliente_telefono: varios teléfonos por ficha, normalizados (+519XXXXXXXX). NO son únicos entre fichas: compartir un
--     teléfono no prueba que dos fichas sean la misma persona; solo sirven para sugerir coincidencias.
--   * cliente_consentimiento: evidencia append-only (carga de la prueba del responsable, DS 016-2024-JUS art. 9) de la
--     autorización del registro y, POR SEPARADO, de la de comunicaciones comerciales (art. 26), con su revocación.
--   * cliente_fusion: constancia de cada fusión manual (quién, cuándo, motivo y qué se movió).
--   * venta / reserva: comprador opcional (CLIENTE o PROVEEDOR) y, en la venta, la reserva de origen y una copia histórica
--     del nombre (y del RUC si lo tenía) usada en esa operación.

CREATE TABLE cliente (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tipo_persona VARCHAR(10) NOT NULL DEFAULT 'NATURAL',
    nombre VARCHAR(150) NOT NULL,
    dni VARCHAR(8),
    ruc VARCHAR(11),
    razon_social VARCHAR(150),
    correo VARCHAR(120),
    mayorista BOOLEAN NOT NULL DEFAULT FALSE,
    mayorista_cambiado_por UUID REFERENCES usuario(id),
    mayorista_cambiado_en TIMESTAMP,
    mayorista_motivo VARCHAR(300),
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    fusionado_en_id UUID REFERENCES cliente(id),
    creado_por UUID REFERENCES usuario(id),
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_cliente_tipo_persona CHECK (tipo_persona IN ('NATURAL', 'EMPRESA')),
    CONSTRAINT ck_cliente_nombre CHECK (btrim(nombre) <> ''),
    CONSTRAINT ck_cliente_dni_forma CHECK (dni IS NULL OR dni ~ '^[0-9]{8}$'),
    CONSTRAINT ck_cliente_ruc_forma CHECK (ruc IS NULL OR ruc ~ '^[0-9]{11}$'),
    -- natural: sin razon social; empresa: RUC y razon social, sin DNI
    CONSTRAINT ck_cliente_documentos CHECK (
        (tipo_persona = 'NATURAL' AND razon_social IS NULL)
        OR (tipo_persona = 'EMPRESA' AND dni IS NULL AND ruc IS NOT NULL AND razon_social IS NOT NULL AND btrim(razon_social) <> '')),
    CONSTRAINT ck_cliente_fusion CHECK (fusionado_en_id IS NULL OR (activo = FALSE AND fusionado_en_id <> id))
);
CREATE UNIQUE INDEX uq_cliente_dni ON cliente (dni) WHERE dni IS NOT NULL AND fusionado_en_id IS NULL;
CREATE UNIQUE INDEX uq_cliente_ruc ON cliente (ruc) WHERE ruc IS NOT NULL AND fusionado_en_id IS NULL;
CREATE INDEX idx_cliente_creado ON cliente (creado_en DESC);

CREATE TABLE cliente_telefono (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    cliente_id UUID NOT NULL REFERENCES cliente(id),
    telefono VARCHAR(13) NOT NULL,
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_cliente_telefono_forma CHECK (telefono ~ '^\+519[0-9]{8}$'),
    CONSTRAINT uq_cliente_telefono UNIQUE (cliente_id, telefono)
);
CREATE INDEX idx_cliente_telefono_numero ON cliente_telefono (telefono);

CREATE TABLE cliente_consentimiento (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    cliente_id UUID NOT NULL REFERENCES cliente(id),
    finalidad VARCHAR(30) NOT NULL,
    estado VARCHAR(10) NOT NULL,
    -- medios autorizados para comunicaciones comerciales (WHATSAPP, SMS, LLAMADA, CORREO), separados por coma
    medios VARCHAR(60),
    forma VARCHAR(20) NOT NULL,
    aviso_version VARCHAR(20) NOT NULL,
    aviso_completo BOOLEAN NOT NULL,
    registrado_por UUID NOT NULL REFERENCES usuario(id),
    registrado_en TIMESTAMP NOT NULL DEFAULT now(),
    nota VARCHAR(300),
    CONSTRAINT ck_consentimiento_finalidad CHECK (finalidad IN ('REGISTRO_CLIENTE', 'COMUNICACIONES_COMERCIALES')),
    CONSTRAINT ck_consentimiento_estado CHECK (estado IN ('OTORGADO', 'REVOCADO')),
    CONSTRAINT ck_consentimiento_forma CHECK (forma IN ('VERBAL_PRESENCIAL', 'ESCRITO', 'TELEFONICA', 'DIGITAL')),
    -- una autorización comercial otorgada siempre dice para qué medios
    CONSTRAINT ck_consentimiento_medios CHECK (
        finalidad <> 'COMUNICACIONES_COMERCIALES' OR estado <> 'OTORGADO' OR (medios IS NOT NULL AND btrim(medios) <> ''))
);
CREATE INDEX idx_consentimiento_cliente ON cliente_consentimiento (cliente_id, finalidad, registrado_en DESC);

CREATE TABLE cliente_fusion (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    origen_id UUID NOT NULL REFERENCES cliente(id),
    destino_id UUID NOT NULL REFERENCES cliente(id),
    motivo TEXT NOT NULL,
    usuario_id UUID NOT NULL REFERENCES usuario(id),
    ventas_movidas INTEGER NOT NULL,
    reservas_movidas INTEGER NOT NULL,
    telefonos_agregados INTEGER NOT NULL,
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_fusion_distintas CHECK (origen_id <> destino_id),
    CONSTRAINT ck_fusion_motivo CHECK (btrim(motivo) <> '')
);

-- Comprador de la venta: CLIENTE (ficha voluntaria) o PROVEEDOR (su ficha de proveedor); NULL = cliente varios.
ALTER TABLE venta ADD COLUMN comprador_tipo VARCHAR(10);
ALTER TABLE venta ADD COLUMN cliente_id UUID REFERENCES cliente(id);
ALTER TABLE venta ADD COLUMN proveedor_id UUID REFERENCES proveedor(id);
ALTER TABLE venta ADD COLUMN reserva_id UUID REFERENCES reserva(id);
-- copia histórica de lo usado en ESTA venta (no cambia si se edita la ficha); el RUC solo si el comprador lo tenía
ALTER TABLE venta ADD COLUMN comprador_nombre VARCHAR(150);
ALTER TABLE venta ADD COLUMN comprador_ruc VARCHAR(11);
ALTER TABLE venta ADD CONSTRAINT ck_venta_comprador CHECK (
    (comprador_tipo IS NULL AND cliente_id IS NULL AND proveedor_id IS NULL)
    OR (comprador_tipo = 'CLIENTE' AND cliente_id IS NOT NULL AND proveedor_id IS NULL AND comprador_nombre IS NOT NULL)
    OR (comprador_tipo = 'PROVEEDOR' AND proveedor_id IS NOT NULL AND cliente_id IS NULL AND comprador_nombre IS NOT NULL));
CREATE INDEX idx_venta_cliente ON venta (cliente_id, fecha DESC) WHERE cliente_id IS NOT NULL;
CREATE INDEX idx_venta_proveedor ON venta (proveedor_id, fecha DESC) WHERE proveedor_id IS NOT NULL;
CREATE UNIQUE INDEX uq_venta_reserva ON venta (reserva_id) WHERE reserva_id IS NOT NULL;

-- Comprador confirmado de una reserva (opcional; una reserva NUNCA crea una ficha por sí sola).
ALTER TABLE reserva ADD COLUMN comprador_tipo VARCHAR(10);
ALTER TABLE reserva ADD COLUMN cliente_id UUID REFERENCES cliente(id);
ALTER TABLE reserva ADD COLUMN proveedor_id UUID REFERENCES proveedor(id);
ALTER TABLE reserva ADD COLUMN comprador_vinculado_por UUID REFERENCES usuario(id);
ALTER TABLE reserva ADD COLUMN comprador_vinculado_en TIMESTAMP;
-- versión del aviso de datos personales que vio quien reservó por la web (NULL en reservas anteriores)
ALTER TABLE reserva ADD COLUMN aviso_privacidad_version VARCHAR(20);
ALTER TABLE reserva ADD CONSTRAINT ck_reserva_comprador CHECK (
    (comprador_tipo IS NULL AND cliente_id IS NULL AND proveedor_id IS NULL)
    OR (comprador_tipo = 'CLIENTE' AND cliente_id IS NOT NULL AND proveedor_id IS NULL)
    OR (comprador_tipo = 'PROVEEDOR' AND proveedor_id IS NOT NULL AND cliente_id IS NULL));
CREATE INDEX idx_reserva_cliente ON reserva (cliente_id) WHERE cliente_id IS NOT NULL;
CREATE INDEX idx_reserva_proveedor ON reserva (proveedor_id) WHERE proveedor_id IS NOT NULL;

-- Coherencia del vínculo que ya existía en un solo sentido (reserva.venta_id): la venta retirada apunta a su reserva.
-- No crea fichas ni cambia importes.
UPDATE venta v SET reserva_id = r.id FROM reserva r WHERE r.venta_id = v.id;
