-- Fase G (documentos del proveedor). Migracion ADITIVA: no modifica ningun valor existente.
--
--   * tipo_persona: NATURAL (persona natural, todos los proveedores actuales) o EMPRESA.
--   * Persona natural: DNI obligatorio, RUC opcional (la misma ficha puede tener ambos). Empresa: RUC y razon social
--     obligatorios, sin DNI. Por eso dni deja de ser NOT NULL (sigue siendo UNICO; varios NULL no chocan).
--   * ruc: 11 digitos, unico. El digito verificador y el prefijo (10 persona natural, 20 empresa) se validan en el
--     servidor al crear o editar; aqui solo se exige la forma.
--   * El formato del DNI (8 digitos) NO se exige en la base: hay registros anteriores que podrian no cumplirlo. Se
--     validan al crear o al cambiar el DNI, y los existentes se informan en GET /api/admin/proveedores/revision-documentos
--     sin corregirlos.

ALTER TABLE proveedor ADD COLUMN tipo_persona VARCHAR(10) NOT NULL DEFAULT 'NATURAL';
ALTER TABLE proveedor ADD COLUMN ruc VARCHAR(11);
ALTER TABLE proveedor ADD COLUMN razon_social VARCHAR(150);
ALTER TABLE proveedor ALTER COLUMN dni DROP NOT NULL;

ALTER TABLE proveedor ADD CONSTRAINT ck_proveedor_tipo_persona CHECK (tipo_persona IN ('NATURAL', 'EMPRESA'));
ALTER TABLE proveedor ADD CONSTRAINT ck_proveedor_ruc_forma CHECK (ruc IS NULL OR ruc ~ '^[0-9]{11}$');
-- natural => con DNI y sin razon social; empresa => con RUC y razon social, sin DNI
ALTER TABLE proveedor ADD CONSTRAINT ck_proveedor_documentos CHECK (
    (tipo_persona = 'NATURAL' AND dni IS NOT NULL AND btrim(dni) <> '' AND razon_social IS NULL)
    OR (tipo_persona = 'EMPRESA' AND dni IS NULL AND ruc IS NOT NULL
        AND razon_social IS NOT NULL AND btrim(razon_social) <> ''));

CREATE UNIQUE INDEX uq_proveedor_ruc ON proveedor (ruc) WHERE ruc IS NOT NULL;
