-- Importacion de datos maestros desde Excel (proveedores, insumos...). Migracion SOLO ADITIVA: tabla nueva; no toca ninguna
-- tabla existente.
--
-- Una importacion tiene DOS pasos: la vista previa (el archivo se valida y se guarda aqui, SIN crear ningun dato de
-- negocio) y la confirmacion (recien ahi se crean los registros, usando los servicios de negocio). Esta tabla guarda las
-- filas YA validadas del servidor: la confirmacion no vuelve a leer el archivo ni confia en nada que mande el cliente.
-- Tambien es la bitacora de quien importo que y cuando (las CONFIRMADA no se borran).
CREATE TABLE importacion_excel (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tipo VARCHAR(40) NOT NULL,
    version INT NOT NULL,
    usuario_id UUID NOT NULL REFERENCES usuario(id),
    nombre_archivo VARCHAR(200) NOT NULL,
    hash_archivo VARCHAR(64) NOT NULL,
    estado VARCHAR(20) NOT NULL CHECK (estado IN ('PREVISUALIZADA', 'CONFIRMADA', 'DESCARTADA')),
    total_filas INT NOT NULL,
    validas INT NOT NULL,
    invalidas INT NOT NULL,
    duplicadas INT NOT NULL,
    omitidas INT NOT NULL,
    creadas INT NOT NULL DEFAULT 0,
    filas_json TEXT NOT NULL,
    creado_en TIMESTAMP NOT NULL DEFAULT now(),
    expira_en TIMESTAMP NOT NULL,
    confirmado_en TIMESTAMP
);
CREATE INDEX idx_importacion_excel_usuario ON importacion_excel(usuario_id, creado_en DESC);
CREATE INDEX idx_importacion_excel_estado ON importacion_excel(estado, expira_en);
