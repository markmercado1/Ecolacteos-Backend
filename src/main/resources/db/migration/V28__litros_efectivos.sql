-- Litros efectivos de una liquidacion (Fase B de la auditoria de pagos/historial/IGV). Migracion SOLO ADITIVA: tabla nueva,
-- columnas nuevas y un indice unico; no se modifica ni se borra ningun pago, registro ni correccion existente.
--
-- Principios: los litros reales recibidos (registro_acopio.litros) NO se sobrescriben; la cantidad solo cambia con una
-- correccion justificada y registrada (correccion_registro); la calidad ajusta el PRECIO, no los litros.
--
-- 1) pago_linea: una fila por entrega liquidada con los litros EFECTIVAMENTE liquidados (original + correcciones validas al
--    generar el pago), el original como copia de auditoria, el precio base y la ultima correccion aplicada.
-- 2) ajuste_pago: litros contabilizados antes del ajuste (base para el siguiente) y precio congelado al liquidarlo. Mientras
--    esta PENDIENTE se valora al precio efectivo del pago; al liquidarlo se congela.
-- 3) Una entrega pertenece a lo sumo a UN pago (antes solo lo comprobaba una consulta).

CREATE TABLE pago_linea (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    pago_id UUID NOT NULL REFERENCES pago(id),
    registro_acopio_id UUID NOT NULL REFERENCES registro_acopio(id),
    litros_originales NUMERIC(8,2) NOT NULL CHECK (litros_originales >= 0),
    litros_liquidados NUMERIC(8,2) NOT NULL CHECK (litros_liquidados >= 0),
    precio_base NUMERIC(6,3) NOT NULL,
    correccion_aplicada_id UUID REFERENCES correccion_registro(id),
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_pago_linea_pago_registro UNIQUE (pago_id, registro_acopio_id),
    CONSTRAINT uq_pago_linea_registro UNIQUE (registro_acopio_id)
);
CREATE INDEX idx_pago_linea_pago ON pago_linea(pago_id);

CREATE UNIQUE INDEX uq_pago_registro_acopio_registro ON pago_registro_acopio(registro_acopio_id);

-- Lineas de los pagos ya existentes. litros_liquidados = registro.litros porque pago.litros_totales es la suma de esos litros
-- (se verifica mas abajo); litros_originales = la primera "litros_anterior" de la entrega si tiene correcciones (siempre guarda el
-- original), si no, registro.litros; correccion aplicada = la ultima hecha antes de generar el pago.
INSERT INTO pago_linea (pago_id, registro_acopio_id, litros_originales, litros_liquidados, precio_base, correccion_aplicada_id, creado_en)
SELECT pr.pago_id, pr.registro_acopio_id,
       COALESCE((SELECT c.litros_anterior FROM correccion_registro c WHERE c.registro_acopio_id = r.id ORDER BY c.creado_en, c.id LIMIT 1), r.litros),
       r.litros,
       p.precio_litro,
       (SELECT c.id FROM correccion_registro c WHERE c.registro_acopio_id = r.id AND c.creado_en <= p.creado_en ORDER BY c.creado_en DESC, c.id DESC LIMIT 1),
       p.creado_en
FROM pago_registro_acopio pr
JOIN pago p ON p.id = pr.pago_id
JOIN registro_acopio r ON r.id = pr.registro_acopio_id;

-- No se inventa nada: si algun pago con lineas no suma exactamente sus litros_totales, la migracion se detiene (Flyway la revierte).
DO $$
DECLARE descuadrados INT;
BEGIN
    SELECT COUNT(*) INTO descuadrados
    FROM pago p
    WHERE EXISTS (SELECT 1 FROM pago_linea l WHERE l.pago_id = p.id)
      AND p.litros_totales <> (SELECT SUM(l.litros_liquidados) FROM pago_linea l WHERE l.pago_id = p.id);
    IF descuadrados > 0 THEN
        RAISE EXCEPTION 'V28 detenida: % pago(s) no suman sus litros_totales con las entregas que liquidan; revisar esos pagos antes de migrar', descuadrados;
    END IF;
END $$;

ALTER TABLE ajuste_pago
    ADD COLUMN litros_contabilizados_antes NUMERIC(8,2),
    ADD COLUMN precio_liquidado NUMERIC(6,3);

-- Ajustes existentes: diferencia = corregido - base, luego base = corregido - diferencia (exacto). Los ya liquidados congelan el
-- precio con el que se crearon (el unico que tuvieron).
UPDATE ajuste_pago a
SET litros_contabilizados_antes = c.litros_corregido - a.litros_diferencia
FROM correccion_registro c
WHERE c.id = a.correccion_registro_id;

UPDATE ajuste_pago SET precio_liquidado = precio_litro WHERE estado = 'LIQUIDADO';
