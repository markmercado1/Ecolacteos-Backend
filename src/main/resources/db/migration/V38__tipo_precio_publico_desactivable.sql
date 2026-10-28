-- PUBLICO deja de estar protegido: el administrador decide. Lo que depende de el se resuelve en el servidor sin romper nada: un tipo
-- desactivado no se acepta en ventas NUEVAS ni en reservas NUEVAS, pero los retiros de reservas ya confirmadas se honran y el historico no
-- cambia. La proteccion pasa a ser general: no se puede desactivar el ultimo tipo activo. No toca precios, ventas ni clientes.
ALTER TABLE tipo_precio DROP CONSTRAINT ck_tipo_precio_protegido_activo;
UPDATE tipo_precio SET protegido = FALSE WHERE codigo = 'PUBLICO';
UPDATE tipo_precio SET descripcion = 'Precio publico: catalogo de la web, reservas y retiros.' WHERE codigo = 'PUBLICO';
