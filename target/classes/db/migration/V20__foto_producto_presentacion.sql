-- Foto opcional por producto y por presentacion (catalogo publico futuro). Solo se guarda el NOMBRE del
-- archivo generado por el servidor (UUID + extension): la URL publica se arma al responder, de modo que
-- cambiar el almacenamiento (disco, CDN) no obligue a migrar datos. Columnas NULL: ningun registro existente
-- cambia ni se le inventa una foto. Si una presentacion no tiene foto, el catalogo usa la del producto.
ALTER TABLE producto ADD COLUMN foto_archivo VARCHAR(80);
ALTER TABLE presentacion ADD COLUMN foto_archivo VARCHAR(80);
