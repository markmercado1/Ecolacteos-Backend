-- Calidad por entrega (Fase C de la auditoria de pagos/historial/IGV). Migracion SOLO ADITIVA: tabla nueva; no toca pago, pago_linea,
-- ajuste_pago, ajuste_precio_pago ni analisis_calidad.
--
-- Precio por litro de UNA entrega dentro de un pago ya generado (p. ej. 100 L de calidad deficiente a 0.90 en un pago de 600 L a 1.00),
-- aplicado a mano por ADMIN con motivo y el analisis de calidad que lo sustenta. Es append-only (igual que ajuste_precio_pago): el
-- precio aplicado de una entrega es el del ajuste mas reciente; sin ajustes, el de su pago (precio normal o ajuste del pago completo).
-- Una entrega con precio propio ignora el ajuste de precio del pago completo ("la entrega manda"). La regla automatica
-- calidad -> precio NO existe aqui (Fase F).

CREATE TABLE ajuste_calidad_entrega (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    pago_linea_id UUID NOT NULL REFERENCES pago_linea(id),
    precio_anterior NUMERIC(6,3) NOT NULL CHECK (precio_anterior > 0),
    precio_nuevo NUMERIC(6,3) NOT NULL CHECK (precio_nuevo > 0),
    analisis_calidad_id UUID NOT NULL REFERENCES analisis_calidad(id),
    motivo TEXT NOT NULL CHECK (length(btrim(motivo)) > 0),
    usuario_id UUID NOT NULL REFERENCES usuario(id),
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_ajuste_calidad_entrega_cambia CHECK (precio_nuevo <> precio_anterior)
);
CREATE INDEX idx_ajuste_calidad_entrega_linea ON ajuste_calidad_entrega(pago_linea_id, creado_en);
CREATE INDEX idx_ajuste_calidad_entrega_analisis ON ajuste_calidad_entrega(analisis_calidad_id);
