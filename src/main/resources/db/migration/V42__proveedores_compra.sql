-- Proveedores de compra: empresas o personas a quienes se compran insumos (y leche, si es el caso). Son DISTINTOS de los
-- proveedores de leche del acopio (tabla `proveedor`: ganaderos con DNI, zona, QR, rutas y pagos): mezclarlos
-- arrastraria rutas, QR y sincronizacion movil a quien solo vende azucar.
--
--   * proveedor_compra: nombre/razon social, RUC unico (11 digitos; el digito verificador se valida en el servidor),
--     telefono, contacto y estado activo/inactivo.
--   * categoria_proveedor + proveedor_compra_categoria: clasificacion flexible muchos-a-muchos (un proveedor puede estar
--     en "Leche" y en "Otros insumos"). Sembrada con LECHE y OTROS_INSUMOS; admite mas categorias sin cambiar codigo.
--   * proveedor_compra_insumo: insumos que el proveedor suele proveer (solo para sugerirlos al registrar ingresos).
--
-- Migracion ADITIVA: tablas nuevas, sin tocar datos existentes.
-- Reversa: DROP TABLE proveedor_compra_insumo, proveedor_compra_categoria, proveedor_compra, categoria_proveedor;
--          (y borrar la fila 42 de flyway_schema_history).

CREATE TABLE categoria_proveedor (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    codigo VARCHAR(30) NOT NULL UNIQUE,
    nombre VARCHAR(60) NOT NULL,
    activo BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO categoria_proveedor (codigo, nombre) VALUES ('LECHE', 'Leche'), ('OTROS_INSUMOS', 'Otros insumos');

CREATE TABLE proveedor_compra (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    nombre VARCHAR(150) NOT NULL CHECK (btrim(nombre) <> ''),
    ruc VARCHAR(11) NOT NULL CONSTRAINT uq_proveedor_compra_ruc UNIQUE CHECK (ruc ~ '^[0-9]{11}$'),
    telefono VARCHAR(20),
    contacto VARCHAR(100),
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE proveedor_compra_categoria (
    proveedor_id UUID NOT NULL REFERENCES proveedor_compra(id),
    categoria_id UUID NOT NULL REFERENCES categoria_proveedor(id),
    PRIMARY KEY (proveedor_id, categoria_id)
);

CREATE TABLE proveedor_compra_insumo (
    proveedor_id UUID NOT NULL REFERENCES proveedor_compra(id),
    insumo_id UUID NOT NULL REFERENCES insumo(id),
    PRIMARY KEY (proveedor_id, insumo_id)
);
CREATE INDEX idx_proveedor_compra_insumo_insumo ON proveedor_compra_insumo(insumo_id);
