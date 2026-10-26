-- Refactor de clientes: canal comercial HABITUAL de la ficha (aditivo, no destructivo).
--   * Es el canal de precio que el panel PROPONE al elegir a este cliente en una venta; la venta sigue eligiendo su canal y el
--     servidor sigue validando cada venta con las reglas de Fase H (MAYORISTA exige ficha habilitada por ADMIN).
--   * Solo PUBLICO o MAYORISTA: el canal PROVEEDOR es de la ficha de proveedor, nunca de un cliente.
--   * MAYORISTA solo si la ficha esta habilitada como mayorista (tipo de cliente): no se confunde el tipo con el canal.
--   * Las fichas existentes quedan en PUBLICO (lo que el panel proponia hasta ahora); no cambia ninguna venta ni reserva.
ALTER TABLE cliente ADD COLUMN canal_habitual VARCHAR(20) NOT NULL DEFAULT 'PUBLICO';
ALTER TABLE cliente ADD CONSTRAINT ck_cliente_canal_habitual CHECK (canal_habitual IN ('PUBLICO', 'MAYORISTA'));
ALTER TABLE cliente ADD CONSTRAINT ck_cliente_canal_mayorista CHECK (canal_habitual <> 'MAYORISTA' OR mayorista);
