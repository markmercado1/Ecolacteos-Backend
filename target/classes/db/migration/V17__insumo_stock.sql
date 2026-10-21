-- Stock por insumo + catalogo ampliado de insumos de planta lactea.
--
-- Por ahora el stock es solo un dato informativo del insumo (no se descuenta al producir todavia).
-- Los insumos que ya existan con el mismo nombre (cargados antes, a mano o por V6) no se duplican
-- (ON CONFLICT sobre UNIQUE(nombre)); solo reciben stock si seguian en 0.

ALTER TABLE insumo ADD COLUMN stock NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (stock >= 0);

-- Insumos existentes de V6
UPDATE insumo SET stock = 50  WHERE nombre = 'Cuajo' AND stock = 0;
UPDATE insumo SET stock = 20  WHERE nombre = 'Sal'   AND stock = 0;

INSERT INTO insumo (nombre, unidad_medida, stock) VALUES
    ('Cloruro de calcio',          'ml',       2000),
    ('Cultivo láctico (fermento)', 'sobres',   40),
    ('Cultivo para yogurt',        'sobres',   40),
    ('Azúcar',                     'kg',       100),
    ('Esencia de vainilla',        'ml',       1000),
    ('Esencia de fresa',           'ml',       1000),
    ('Pulpa de fresa',             'kg',       30),
    ('Pulpa de durazno',           'kg',       30),
    ('Mermelada de frutas',        'kg',       25),
    ('Estabilizante',              'g',        2000),
    ('Sorbato de potasio',         'g',        1000),
    ('Colorante (achiote)',        'ml',       500),
    ('Leche en polvo',             'kg',       25),
    ('Crema de leche',             'litros',   20),
    ('Mantequilla',                'kg',       10),
    ('Envase botella 1 L',         'unidades', 500),
    ('Envase botella 500 ml',      'unidades', 500),
    ('Envase vaso 150 g',          'unidades', 1000),
    ('Bolsa para queso',           'unidades', 1000),
    ('Etiquetas',                  'unidades', 2000)
ON CONFLICT (nombre) DO NOTHING;
