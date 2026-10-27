-- IGV activable desde el panel (Fase G, seguimiento). Migracion SOLO ADITIVA: una tabla nueva append-only.
--
-- Hasta ahora el calculo del IGV solo se encendia con la variable de entorno TRIBUTARIO_IGV_CALCULO_HABILITADO (y reiniciando el servidor).
-- Ahora lo decide un ADMIN desde el panel, cuando quiera y las veces que quiera: cada cambio queda registrado con su motivo, quien lo hizo y
-- cuando. El estado vigente es el del cambio MAS RECIENTE; mientras no haya ninguno rige el valor de la variable de entorno (apagado por
-- defecto). Activar o desactivar NO recalcula ventas ya registradas: solo cambia como se registran las nuevas.
CREATE TABLE cambio_calculo_igv (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    habilitado BOOLEAN NOT NULL,
    motivo TEXT NOT NULL CHECK (length(btrim(motivo)) > 0),
    usuario_id UUID REFERENCES usuario(id),
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_cambio_calculo_igv_fecha ON cambio_calculo_igv (creado_en DESC);
