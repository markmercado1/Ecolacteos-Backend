-- Pagos efectivos (dinero realmente entregado al proveedor). Migracion SOLO ADITIVA: dos tablas nuevas; no toca pago, pago_linea, ajuste_pago,
-- ajuste_precio_pago ni ajuste_calidad_entrega. `pago` sigue siendo la LIQUIDACION (la deuda); estas tablas registran el desembolso.
--
--   * pago_proveedor: un desembolso (efectivo, transferencia o deposito) a un proveedor. Los importes NO se editan; anular cambia el estado
--     (con motivo, usuario y fecha) y conserva la fila. `uuid_cliente` hace idempotente el reenvio; la operacion bancaria (banco + numero)
--     no puede repetirse entre pagos vigentes. Un pago puede quedar sin aplicar (adelanto) y aplicarse despues.
--   * pago_proveedor_aplicacion: parte de un desembolso aplicada a UNA liquidacion. Inmutable; solo cuentan las de pagos no anulados.
--
-- El saldo de una liquidacion NO se guarda: = total a pagar + ajustes de litros - aplicaciones vigentes (no hay estado "pagado" duplicado).

CREATE TABLE pago_proveedor (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    uuid_cliente VARCHAR(36) NOT NULL UNIQUE,
    proveedor_id UUID NOT NULL REFERENCES proveedor(id),
    fecha_hora TIMESTAMP NOT NULL DEFAULT now(),
    modalidad VARCHAR(20) NOT NULL,
    monto NUMERIC(12,2) NOT NULL,
    concepto VARCHAR(200),
    banco VARCHAR(80),
    nro_operacion VARCHAR(80),
    nro_recibo VARCHAR(80),
    observacion VARCHAR(500),
    usuario_id UUID NOT NULL REFERENCES usuario(id),
    estado VARCHAR(12) NOT NULL DEFAULT 'REGISTRADO',
    anulado_motivo TEXT,
    anulado_por UUID REFERENCES usuario(id),
    anulado_en TIMESTAMP,
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_pago_proveedor_modalidad CHECK (modalidad IN ('EFECTIVO', 'TRANSFERENCIA', 'DEPOSITO')),
    CONSTRAINT ck_pago_proveedor_monto CHECK (monto > 0),
    CONSTRAINT ck_pago_proveedor_estado CHECK (estado IN ('REGISTRADO', 'ANULADO')),
    -- una transferencia o un deposito siempre deja rastro bancario: banco y numero de operacion
    CONSTRAINT ck_pago_proveedor_operacion CHECK (modalidad = 'EFECTIVO'
        OR (btrim(coalesce(banco, '')) <> '' AND btrim(coalesce(nro_operacion, '')) <> '')),
    -- anulado => con motivo, usuario y fecha de anulacion; vigente => sin ninguno de los tres
    CONSTRAINT ck_pago_proveedor_anulacion CHECK (
        (estado = 'ANULADO' AND anulado_motivo IS NOT NULL AND length(btrim(anulado_motivo)) > 0 AND anulado_por IS NOT NULL AND anulado_en IS NOT NULL)
        OR (estado = 'REGISTRADO' AND anulado_motivo IS NULL AND anulado_por IS NULL AND anulado_en IS NULL))
);

-- La misma operacion bancaria no se registra dos veces entre pagos vigentes (un pago anulado libera su numero para volver a registrarlo bien).
CREATE UNIQUE INDEX uq_pago_proveedor_operacion
    ON pago_proveedor (lower(btrim(banco)), lower(btrim(nro_operacion)))
    WHERE estado = 'REGISTRADO' AND banco IS NOT NULL AND nro_operacion IS NOT NULL;
CREATE INDEX idx_pago_proveedor_proveedor ON pago_proveedor (proveedor_id, fecha_hora DESC);

CREATE TABLE pago_proveedor_aplicacion (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    pago_proveedor_id UUID NOT NULL REFERENCES pago_proveedor(id),
    pago_id UUID NOT NULL REFERENCES pago(id),
    monto NUMERIC(12,2) NOT NULL CHECK (monto > 0),
    usuario_id UUID NOT NULL REFERENCES usuario(id),
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_pago_proveedor_aplicacion_pago ON pago_proveedor_aplicacion (pago_id);
CREATE INDEX idx_pago_proveedor_aplicacion_desembolso ON pago_proveedor_aplicacion (pago_proveedor_id);
