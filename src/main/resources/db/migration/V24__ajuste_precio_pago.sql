-- Ajuste del precio por litro de UN pago concreto. Migracion SOLO ADITIVA: tabla nueva, no toca pago, ajuste_pago
-- ni precio_litro.
--
-- El pago (litros_totales, precio_litro, total) sigue inmutable: cambiar el precio de un pago NO reescribe nada, se
-- agrega una fila aqui (append-only, mismo criterio que correccion_registro). El precio efectivo de un pago es el
-- precio_litro_nuevo de su ajuste mas reciente (o pago.precio_litro si no tiene ajustes). Afecta SOLO a ese pago:
-- ni el precio general de la leche ni los demas pagos cambian.
CREATE TABLE ajuste_precio_pago (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    pago_id UUID NOT NULL REFERENCES pago(id),
    -- Precio efectivo justo antes de este ajuste (el del pago original o el del ajuste previo): auditoria del cambio.
    precio_litro_anterior NUMERIC(6,3) NOT NULL,
    precio_litro_nuevo NUMERIC(6,3) NOT NULL CHECK (precio_litro_nuevo > 0),
    motivo TEXT NOT NULL CHECK (length(btrim(motivo)) > 0),
    usuario_id UUID NOT NULL REFERENCES usuario(id),
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_ajuste_precio_pago_pago ON ajuste_precio_pago(pago_id, creado_en);
