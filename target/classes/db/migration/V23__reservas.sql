-- Fase 4 (reservas). Migracion SOLO ADITIVA: tablas nuevas, una columna de auditoria en configuracion_sistema y
-- los parametros iniciales de las reservas. NO toca movimiento_inventario, el kardex ni el reparto por lote.
--
-- Una reserva NO es una venta ni un movimiento: compromete unidades de una presentacion (con la fecha en que se
-- retirarian) sin reducir el stock fisico. Compromete mientras esta PENDIENTE (hasta expira_en) o CONFIRMADA (hasta
-- el fin de fecha_retiro); VENCIDA se calcula por consulta (sin proceso programado), de modo que una reserva vencida
-- deja de comprometer stock aunque nadie haya consultado su detalle. El estado `VENCIDA` del CHECK existe por
-- completitud pero el sistema no necesita escribirlo.

ALTER TABLE configuracion_sistema
    ADD COLUMN actualizado_por UUID REFERENCES usuario(id);

INSERT INTO configuracion_sistema (clave, valor, descripcion) VALUES
    ('RESERVA_PLAZO_DIAS', '3', 'Dias calendario (desde hoy, hora de Lima) dentro de los que el cliente puede elegir la fecha de retiro de una reserva'),
    ('RESERVA_MAX_UNIDADES', '20', 'Maximo de unidades por reserva (suma de todas las lineas; presentaciones contadas en kg, g, L o ml se limitan por separado)'),
    ('RESERVA_MAX_PENDIENTES_TELEFONO', '2', 'Maximo de reservas pendientes por telefono'),
    ('RESERVA_HORAS_PENDIENTE', '24', 'Horas que una reserva pendiente retiene unidades antes de vencer si nadie la atiende'),
    ('RESERVA_DIAS_ATENCION', '1,2,3,4,5,6', 'Dias de atencion para retirar (1=lunes ... 7=domingo, separados por coma)'),
    ('RESERVA_HORA_CIERRE', '18:00', 'Hora (HH:mm, Lima) despues de la cual ya no se acepta retiro el mismo dia'),
    ('RESERVA_DIAS_NO_LABORABLES', 'NINGUNO', 'Feriados / dias sin atencion: fechas yyyy-MM-dd separadas por coma, o NINGUNO')
ON CONFLICT (clave) DO NOTHING;

CREATE TABLE reserva (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    -- Identificador corto que el cliente conserva para consultar su reserva (con su telefono).
    codigo VARCHAR(12) NOT NULL UNIQUE,
    -- Idempotencia (mismo criterio que venta/lote): reenviar la misma solicitud nunca crea dos reservas.
    uuid_cliente VARCHAR(36) NOT NULL UNIQUE,
    estado VARCHAR(15) NOT NULL
        CHECK (estado IN ('PENDIENTE', 'CONFIRMADA', 'RECHAZADA', 'CANCELADA', 'VENCIDA', 'RETIRADA')),
    nombre_cliente VARCHAR(80) NOT NULL,
    -- Siempre normalizado: +519XXXXXXXX.
    telefono VARCHAR(13) NOT NULL CHECK (telefono ~ '^\+519[0-9]{8}$'),
    correo VARCHAR(120),
    nota_cliente VARCHAR(300),
    fecha_retiro DATE NOT NULL,
    -- Fecha maxima de retiro calculada al crear (hoy + plazo vigente entonces): cambiar la configuracion despues
    -- no mueve las reservas ya creadas.
    fecha_maxima_retiro DATE NOT NULL,
    -- Hora de Lima (America/Lima), igual que el resto de reglas de vencimiento.
    creado_en TIMESTAMP NOT NULL,
    expira_en TIMESTAMP NOT NULL,
    -- NULL mientras alguna linea no tenga precio: nunca se inventa un total.
    total NUMERIC(10, 2),
    -- true si alguna linea se cotizo sin precio publico o se recotizo: el cliente debe aceptar el importe antes de confirmar.
    requiere_aceptacion BOOLEAN NOT NULL DEFAULT FALSE,
    total_aceptado NUMERIC(10, 2),
    aceptado_en TIMESTAMP,
    aceptado_por UUID REFERENCES usuario(id),
    aceptado_medio VARCHAR(20),
    aceptado_nota VARCHAR(300),
    -- La venta real del retiro (solo en RETIRADA).
    venta_id UUID REFERENCES venta(id),
    CONSTRAINT chk_reserva_fechas CHECK (fecha_retiro <= fecha_maxima_retiro),
    CONSTRAINT chk_reserva_venta CHECK (venta_id IS NULL OR estado = 'RETIRADA')
);

CREATE INDEX idx_reserva_estado_expira ON reserva(estado, expira_en);
CREATE INDEX idx_reserva_estado_retiro ON reserva(estado, fecha_retiro);
CREATE INDEX idx_reserva_telefono_estado ON reserva(telefono, estado);
CREATE UNIQUE INDEX uk_reserva_venta ON reserva(venta_id) WHERE venta_id IS NOT NULL;

CREATE TABLE reserva_detalle (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    reserva_id UUID NOT NULL REFERENCES reserva(id),
    presentacion_id UUID NOT NULL REFERENCES presentacion(id),
    cantidad INT NOT NULL CHECK (cantidad > 0),
    -- Precio publico cotizado por unidad; NULL = "Precio a confirmar".
    precio_unitario NUMERIC(8, 2) CHECK (precio_unitario IS NULL OR precio_unitario >= 0),
    UNIQUE (reserva_id, presentacion_id)
);

CREATE INDEX idx_reserva_detalle_presentacion ON reserva_detalle(presentacion_id);

-- Historial append-only de cada cambio (quien, cuando y por que). Nunca se edita ni se borra.
CREATE TABLE reserva_evento (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    reserva_id UUID NOT NULL REFERENCES reserva(id),
    tipo VARCHAR(25) NOT NULL,
    usuario_id UUID REFERENCES usuario(id),
    motivo VARCHAR(300),
    creado_en TIMESTAMP NOT NULL
);

CREATE INDEX idx_reserva_evento_reserva ON reserva_evento(reserva_id);

-- Un ajuste por perdida fisica real nunca se bloquea; si deja reservas sin cobertura se registra aqui para que se
-- resuelva por el flujo administrativo (rechazar/cancelar las reservas que ya no se pueden cubrir).
CREATE TABLE incidencia_cobertura (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    presentacion_id UUID NOT NULL REFERENCES presentacion(id),
    ajuste_inventario_id UUID REFERENCES ajuste_inventario(id),
    unidades_faltantes INT NOT NULL CHECK (unidades_faltantes > 0),
    creado_en TIMESTAMP NOT NULL
);

CREATE INDEX idx_incidencia_cobertura_presentacion ON incidencia_cobertura(presentacion_id);
