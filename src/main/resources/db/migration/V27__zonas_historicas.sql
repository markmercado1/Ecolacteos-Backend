-- Zonas historicas (Fase A de la auditoria de pagos/historial/IGV). Migracion SOLO ADITIVA: columnas nuevas y un indice;
-- ni se borra ni se reescribe ningun dato existente (desde/hasta de historial_zona se conservan tal cual).
--
-- 1) historial_zona pasa de periodos por DIA (desde/hasta) a periodos por INSTANTE [inicio_en, fin_en): dos cambios el
--    mismo dia ya no se pisan. Los periodos legados se trasladan a medianoche (desde 00:00, hasta+1 00:00).
-- 2) Un periodo legado con hasta < desde (reasignaciones el mismo dia con el codigo anterior) NO se puede ordenar: se
--    marca requiere_revision, queda con duracion cero (nunca coincide con una entrega) y NO se borra.
-- 3) registro_acopio.zona_id guarda la zona que tenia el proveedor EN EL MOMENTO de la entrega. Se rellena solo cuando
--    hay UN periodo valido y la entrega no cae en un dia de cambio de zona de ese proveedor (la hora del cambio no se
--    registraba, asi que no se puede saber de que lado cae); el resto queda en NULL y lo lista el informe de revision.

ALTER TABLE historial_zona
    ADD COLUMN inicio_en TIMESTAMP,
    ADD COLUMN fin_en TIMESTAMP,
    ADD COLUMN zona_origen_id UUID REFERENCES zona(id),
    ADD COLUMN motivo VARCHAR(200),
    ADD COLUMN usuario_id UUID REFERENCES usuario(id),
    ADD COLUMN requiere_revision BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN nota_revision VARCHAR(300);

UPDATE historial_zona
SET requiere_revision = TRUE,
    nota_revision = 'Periodo invertido (hasta anterior a desde): reasignaciones el mismo dia; el orden real no se puede recuperar',
    inicio_en = desde::timestamp,
    fin_en = desde::timestamp
WHERE hasta IS NOT NULL AND hasta < desde;

UPDATE historial_zona
SET inicio_en = desde::timestamp,
    fin_en = CASE WHEN hasta IS NULL THEN NULL ELSE (hasta + 1)::timestamp END
WHERE NOT requiere_revision;

ALTER TABLE historial_zona ALTER COLUMN inicio_en SET NOT NULL;
ALTER TABLE historial_zona ADD CONSTRAINT ck_historial_zona_periodo CHECK (fin_en IS NULL OR fin_en >= inicio_en);

-- Zona de la que venia el proveedor: el periodo valido anterior (los marcados para revision se dejan sin origen).
UPDATE historial_zona h
SET zona_origen_id = previo.zona_anterior
FROM (
    SELECT id, LAG(zona_id) OVER (PARTITION BY proveedor_id ORDER BY inicio_en, desde) AS zona_anterior
    FROM historial_zona
    WHERE NOT requiere_revision
) previo
WHERE previo.id = h.id;

UPDATE historial_zona SET motivo = 'Periodo anterior a la historia detallada' WHERE motivo IS NULL AND NOT requiere_revision;

-- A lo sumo UN periodo abierto por proveedor (antes solo lo cuidaba el codigo; dos reasignaciones simultaneas podian dejar dos).
CREATE UNIQUE INDEX uq_historial_zona_abierto ON historial_zona(proveedor_id) WHERE fin_en IS NULL;
CREATE INDEX idx_historial_zona_proveedor_inicio ON historial_zona(proveedor_id, inicio_en);

ALTER TABLE registro_acopio ADD COLUMN zona_id UUID REFERENCES zona(id);
CREATE INDEX idx_registro_acopio_zona_fecha ON registro_acopio(zona_id, fecha_hora);

UPDATE registro_acopio r
SET zona_id = c.zona_id
FROM (
    SELECT r2.id AS registro_id, MIN(h.zona_id::text)::uuid AS zona_id, COUNT(*) AS periodos
    FROM registro_acopio r2
    JOIN historial_zona h ON h.proveedor_id = r2.proveedor_id
        AND NOT h.requiere_revision
        AND h.inicio_en <= r2.fecha_hora
        AND (h.fin_en IS NULL OR r2.fecha_hora < h.fin_en)
    GROUP BY r2.id
) c
WHERE c.registro_id = r.id
  AND c.periodos = 1
  AND NOT EXISTS (
        SELECT 1
        FROM historial_zona cambio
        WHERE cambio.proveedor_id = r.proveedor_id
          AND cambio.desde = r.fecha_hora::date
          AND EXISTS (SELECT 1 FROM historial_zona antes
                      WHERE antes.proveedor_id = cambio.proveedor_id AND antes.desde < cambio.desde)
  );
