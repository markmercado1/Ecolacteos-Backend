## Actualización 2026-10-27 — salidas y retirada de módulos (V36)

Esta actualización prevalece sobre las referencias históricas de este documento.

- Nuevos `POST /api/inventario/presentaciones/{id}/salidas` y `POST /api/inventario/insumos/{id}/salidas`, solo ADMIN, HTTP 200.
- JSON: `uuidCliente` (UUID obligatorio), `cantidad` positiva (producto entero, insumo hasta 2 decimales), `tipo` (`VENCIMIENTO`, `DETERIORO`, `OTRA`), `motivo` no vacío hasta 500 caracteres; `origenLote` (`LOTE` o `REGULARIZACION`) y `loteId` juntos cuando la presentación controla vencimiento. Insumos no aceptan lotes. Vencimiento de producto exige lote efectivamente vencido según ReglaVencimiento.
- Respuesta: `id`, `movimientoId`, `cantidad`, `tipo`, `motivo`, `origenLote`, `loteId`, `fechaVencimiento`, `usuarioNombre`, `creadoEn`. Reintento con la misma clave/datos devuelve lo registrado sin descontar otra vez; clave con otros datos: 409. Validación/saldo: 400; recurso inexistente: 404; rol insuficiente: 403.
- V36 añade `salida_inventario` (documento e idempotencia), `salida_id` a los libros existentes y restricciones/índices únicos. Tipos Kardex `SALIDA_VENCIMIENTO`, `SALIDA_DETERIORO`, `SALIDA_OTRA`; documento SALIDA con referencia, actor y motivo. Producto reparte el retiro al lote existente; insumo mantiene stock = suma del libro. No hay segundo Kardex.
- Eliminados `/api/informes-produccion` y subrutas, `/api/exportaciones/informes-produccion`, `/api/admin/proveedores/revision-documentos`, `/api/admin/zonas/revision`. La última ruta cae en el CRUD de zona por id y devuelve 400 por UUID inválido. Se conserva el historial SQL de informes, documentos, zonas y acopios.
- Sin cambios en contratos/reglas de `/api/pagos` (liquidaciones de leche) y `/api/pagos-proveedor` (desembolsos y aplicaciones), ni CRUD de insumos y recetas.
- No existe fecha de vencimiento/lote de ingreso para insumos: solo retiro manual justificado, sin detección automática ni historia inventada. Productos sin control admiten deterioro/otra, no vencimiento sin evidencia.
- Migración comprobada en PostgreSQL temporal; no aplicada a una base habitual ni a producción. Sin integración ni despliegue automático.

## Actualización 2026-10-27 — tipos de precio administrables, etapa 1 (V37)

- `GET /api/tipos-precio?soloActivos=` (VENTAS, ADMIN) → `[{codigo, nombre, descripcion, activo, protegido, ultimoCambio: {activo, motivo, usuarioNombre, creadoEn}|null}]`. Con `soloActivos=true` solo los que se pueden usar en operaciones nuevas.
- `PUT /api/tipos-precio/{codigo}/estado` (ADMIN) `{activo, motivo}` → el tipo. Motivo obligatorio; PUBLICO es `protegido` (400 al desactivarlo: catálogo público y reservas dependen de él); repetir el estado: 409; código desconocido: 404.
- Un tipo desactivado se **rechaza (400)** en precios nuevos (`POST` de precio por presentación) y ventas nuevas (`POST /api/ventas...`); el reintento idempotente de una venta ya registrada y toda consulta histórica no cambian. No se modifica ningún precio, cliente ni venta existente.
- V37 (aditiva): `tipo_precio` (MAYORISTA, PROVEEDOR, PUBLICO activos) y `cambio_tipo_precio` (quién, cuándo y por qué). `precio_presentacion`, `venta` y `cliente` no se tocan; sus CHECK siguen igual.
- **Pendiente (etapa 2):** crear tipos nuevos exige pasar `TipoClienteVenta` de enum a texto con clave foránea a `tipo_precio` en `precio_presentacion.modalidad`, `precio_queso.modalidad` y `venta.tipo_cliente`, definir cómo se comporta un tipo nuevo en `CompradorService.validarParaCanal`, y hacer dinámicas las columnas de la tabla de precios y de su Excel.

# API_DOCUMENTATION.md — Backend EcoLácteos v1

Guía rápida para el equipo de frontend (Angular/Flutter). El detalle exacto de cada
endpoint (request/response, campos, validaciones, roles) vive en **Swagger UI**,
generado automáticamente desde el código — este documento explica lo que Swagger
no puede explicar por sí solo: cómo entrar, cómo está organizado, y las reglas de
integración que solo tienen sentido leyendo varios endpoints juntos.

## 1. URL base de la API

| Entorno | Base path |
|---|---|
| Local (`application.yml` por defecto) | `http://localhost:8080` |
| Cualquier entorno | Configurable con la variable de entorno `SERVER_PORT` (default `8080`) |

Todos los endpoints cuelgan de `/api/...` (ej. `http://localhost:8080/api/proveedores`).
No hay versión en la ruta (`/api/v1/...`): la API entera es v1.

## 2. Cómo autenticarse

```
POST /api/auth/login
Content-Type: application/json

{
  "email": "admin@ecolacteos.pe",
  "password": "********"
}
```

Respuesta `200`:

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "rol": "ADMIN",
  "nombre": "Nombre del usuario",
  "expiraEnSegundos": 28800
}
```

- El token dura **8 horas** (`expiraEnSegundos`).
- Antes de que expire, se puede renovar con `POST /api/auth/refresh` (requiere
  enviar el token *todavía vigente* como Bearer) — devuelve la misma forma de
  respuesta con un token nuevo. Un token **ya expirado no se puede renovar**:
  hay que volver a hacer login.
- Un usuario desactivado no puede autenticarse (401), y si se desactiva mientras
  tiene una sesión activa, su próximo request falla igual — el rol se
  revalida contra la base de datos en cada llamada, no solo al hacer login.

## 3. Cómo enviar el JWT

En **todos** los endpoints salvo `POST /api/auth/login`, agregar el header:

```
Authorization: Bearer <token>
```

- Sin este header (o con un token inválido/expirado): `401 Unauthorized`.
- Con un token válido pero de un rol sin permiso sobre ese endpoint: `403 Forbidden`.
- Ambos casos devuelven el mismo formato JSON que cualquier otro error de la API
  (ver `ErrorResponse` más abajo) — el frontend puede manejar todos los errores
  con un único interceptor.

```json
{
  "timestamp": "2026-09-03T10:15:30.123Z",
  "status": 401,
  "error": "Unauthorized",
  "mensaje": "Token de autenticacion ausente, invalido o expirado"
}
```

## 4. Cómo usar Swagger UI

| Recurso | URL |
|---|---|
| Swagger UI (interfaz interactiva) | `/swagger-ui.html` |
| Spec OpenAPI en JSON | `/v3/api-docs` |

Ambas rutas son públicas (no requieren JWT para *cargar* la página — sí lo
requieren, como cualquier otro endpoint, los requests que se disparen *desde*
la página contra la API real).

**Para probar un endpoint protegido:**
1. Ejecutar `POST /api/auth/login` (el propio Swagger UI lo tiene documentado y
   se puede probar sin autenticación con el botón **Try it out**).
2. Copiar el valor de `token` de la respuesta.
3. Click en el botón **Authorize** (arriba a la derecha, ícono de candado).
4. Pegar el token **sin** el prefijo `Bearer` (Swagger lo agrega solo) y
   confirmar.
5. A partir de ahí, todos los `Try it out` de endpoints protegidos ya mandan
   el header `Authorization` automáticamente.

El candado junto a cada operación indica si requiere JWT; `POST /api/auth/login`
es el único endpoint sin candado.

## 5. Organización de los endpoints por módulo

Swagger agrupa los 73 endpoints en 21 tags, uno por recurso de negocio (no por
Controller — algunos Controllers agrupan varios recursos, ej. `AdminCatalogosController`
cubre 7 tags distintos):

| Tag en Swagger | Rol(es) |
|---|---|
| Autenticación | público (login) / cualquiera (refresh) |
| Usuarios | ADMIN |
| Proveedores | ADMIN (completo) · +ACOPIADOR/RECEPCION/CALIDAD (operativo) |
| Zonas | ADMIN |
| Unidades | ADMIN |
| Motivos de Observación | ADMIN |
| Tipos de Queso | ADMIN |
| Precio por Litro | ADMIN |
| Roles | ADMIN |
| Configuración del Sistema | ADMIN |
| Rutas de Zona | ADMIN (definir) · +ACOPIADOR (leer) |
| Registro de Acopio | ACOPIADOR, ADMIN · +CALIDAD (leer) |
| Correcciones de Registro | CALIDAD, ADMIN |
| Análisis de Calidad | CALIDAD, ADMIN |
| Recepción y Conciliación | RECEPCION, ADMIN |
| Lotes de Producción | PRODUCCION, ADMIN (incluye planificar/iniciar/finalizar/cancelar, V18) |
| Recetas | PRODUCCION, ADMIN (consultar y calcular) · ADMIN (crear/editar/desactivar/reactivar) |
| Kardex (V19, solo cantidades) | PRODUCCION, ADMIN (leche e insumos) · PRODUCCION, VENTAS, ADMIN (producto terminado) |
| Inventario de insumos (V19) | PRODUCCION, ADMIN (stock e ingresos) · ADMIN (ajustes por conteo físico) |
| Pagos | ADMIN (escritura) · +RECEPCION (lectura) |
| Ventas | VENTAS, ADMIN |
| Comunicados | ADMIN (crear) · +ACOPIADOR (confirmar) · cualquiera (leer por zona) |
| Sincronización Offline | el rol dueño de cada dato (ver más abajo) |
| Innovación y Analítica | ADMIN, CALIDAD (panel económico: solo ADMIN) |

Dentro de cada tag, los endpoints están ordenados GET → POST → PUT → PATCH → DELETE.

## 6. Flujos principales que debe conocer el frontend

**a) Login → operar.** Todo flujo empieza con `POST /api/auth/login`. Guardar
`token` y `rol`; usar `rol` para decidir qué pantallas mostrar (no hay
endpoint de "permisos"; los roles son los 6 fijos: `ADMIN, ACOPIADOR, CALIDAD,
RECEPCION, PRODUCCION, VENTAS`, consultables en `GET /api/admin/roles`).

**b) Acopio → calidad → producción/pago.** La cadena central del negocio:
```
Proveedor (id) → RegistroAcopio (proveedorId, unidadId) → id
                                                             ├─ AnalisisCalidad (registroAcopioId)
                                                             ├─ CorreccionRegistro (anexo, vía POST /registros-acopio/{id}/correcciones)
                                                             ├─ LoteProduccion (registroAcopioIds[])
                                                             └─ Pago (por proveedor + rango de fechas, no por id directo)
```
`RegistroAcopio` es **inmutable**: no hay `PUT`/`DELETE`. La única forma de
corregir un dato es `POST /registros-acopio/{id}/correcciones` — el original
nunca se sobreescribe.

**c) Catálogo de Unidad para offline.** `RegistroAcopioDTO.unidadId` y
`RecepcionPlantaRequest.unidadId` son obligatorios, pero `GET /api/admin/unidades`
es ADMIN-only. El frontend de campo (ACOPIADOR/RECEPCION) debe resolver ese
catálogo desde `GET /api/sync/cambios` (campo `unidades`), no desde el
endpoint admin.

**d) Sincronización offline (Flutter).** Cada entidad capturable offline
(RegistroAcopio, AnalisisCalidad, LoteProduccion, Venta) lleva un `uuidCliente`
generado en el dispositivo. El flujo es:
1. Capturar localmente con un `uuidCliente` propio (UUID v4).
2. Al recuperar señal, `POST /api/sync/{recurso}` con la lista acumulada.
3. Leer la respuesta `{ confirmados: [uuidCliente...], errores: [{uuidCliente, motivo}...] }`.
   Reenviar un `uuidCliente` ya confirmado es seguro: el backend lo reconoce y
   devuelve el existente, nunca lo duplica.
4. `GET /api/sync/cambios` para refrescar catálogos, precio vigente,
   comunicados y predicciones antes de seguir trabajando offline.

Los mismos 4 recursos también se pueden crear uno por uno, en línea, con sus
endpoints `POST` normales (`/api/registros-acopio`, `/api/analisis-calidad`,
etc.) — `/api/sync/*` es exclusivamente para lotes acumulados offline.

**e) Pago no recalcula correcciones.** `PagoService` suma
`RegistroAcopio.litros` **original** del rango de fechas. Si Calidad anexó una
corrección después de la entrega, esa corrección queda en el historial de
auditoría pero **no cambia** el monto de un pago (ya generado o por generar).
Es una decisión de negocio pendiente de definir explícitamente, no un bug —
si el frontend muestra el detalle de un pago, no asumir que refleja
correcciones posteriores.

## 7. Reglas de integración que Swagger no explica por sí solo

- **Idempotencia offline, no un código de error.** Reenviar un `POST` con el
  mismo `uuidCliente` no devuelve `409`: devuelve `201` con el registro ya
  existente. El frontend no necesita (ni debe) evitar reenviar algo que no
  está seguro si se envió.

- **Baja lógica, nunca DELETE físico.** Todo `DELETE` de la API (Zona, TipoQueso,
  MotivoObservacion, Usuario, Proveedor) es `activo=false` — el recurso sigue
  existiendo y siendo referenciable en datos históricos, solo deja de ofrecerse
  como opción nueva. No hay forma de "reactivar" desde la API en v1.

- **Proveedor tiene dos DTOs a propósito.** `ProveedorAdminResponse` (con DNI y
  teléfono) solo lo devuelven los endpoints ADMIN-only. `ProveedorPublicoResponse`
  (sin datos personales) es lo que ven ACOPIADOR/RECEPCION/CALIDAD vía
  `GET /api/proveedores/operativo`, `GET /api/proveedores/qr/{codigoQr}` y
  `GET /api/sync/cambios`. No mezclar: si una pantalla no es de ADMIN, no debe
  pedir el endpoint completo.

- **El acopiador/responsable nunca va en el body.** `RegistroAcopioDTO`,
  `CorreccionRegistroRequest`, `ConfirmarComunicadoRequest`, `RecepcionPlantaRequest`
  no llevan un campo de "quién lo hizo" — el backend lo resuelve del JWT. Enviarlo
  en el body no tendría efecto (se ignora).

- **`RutaZona` es reemplazo total, no edición incremental.**
  `PUT /api/zonas/{zonaId}/ruta` borra la ruta anterior completa y guarda la
  nueva. Para "mover un proveedor" en la ruta, el frontend debe reenviar la
  lista completa con el nuevo orden, no solo el ítem cambiado.

- **`Pago` valida solape, no solo semana exacta.** Generar un pago con un
  rango de fechas que incluya un `RegistroAcopio` ya liquidado en *otro* pago
  (aunque tenga una `semanaInicio` distinta) devuelve `409`. El frontend debe
  mostrar ese mensaje tal cual (`mensaje` del error), no asumir que solo se
  puede repetir la semana exacta.

- **Catálogos "activos" vs. completos.** `GET /api/admin/{zonas,motivos-observacion,tipos-queso}`
  devuelven solo los registros con `activo=true`. Si una pantalla admin necesita
  ver también los desactivados (ej. para reactivar manualmente vía soporte),
  no existe ese endpoint en v1.

- **Innovación puede devolver 404 legítimamente.** `GET /api/innovacion/score/{proveedorId}`
  y `.../prediccion/{proveedorId}` devuelven `404` cuando el proveedor no tiene
  histórico suficiente (mínimo 3 registros en 90 días) — no es un error de
  integración, es el estado esperado para un proveedor nuevo. El frontend debe
  mostrar "sin datos suficientes aún", no un error genérico.

- **Formato de error único.** Toda respuesta de error de la API (400, 401, 403,
  404, 409, 422, 500) tiene la misma forma `ErrorResponse` documentada en
  Swagger (`timestamp`, `status`, `error`, `mensaje`). Un solo interceptor
  HTTP en el frontend puede manejarlos todos.

## 8. Exportar e importar Excel

Detalle completo en el repositorio del panel: `docs/EXCEL.md`.

**Exportar (solo lectura).** `GET /api/exportaciones/{recurso}` devuelve un `.xlsx` con TODAS las filas del filtro y la búsqueda (no una página), con los
mismos roles y filtros que el listado de cada tabla. Recursos: `proveedores`, `acopios`, `pagos`, `ventas`, `reservas`, `recepciones`, `lotes`,
`informes-produccion`, `stock`, `inventario-insumos`, `calidad-analizados`, `alertas-anomalia`, `jornadas`, `usuarios`, `precios-litro`,
`precios-presentacion`, `zonas`, `motivos-observacion`, `familias`, `productos`, `insumos`, `recetas`, `configuracion`, `unidades`. Tope de filas:
`app.excel.exportacion.max-filas` (50 000; 400 si se excede). Las celdas de texto que parezcan fórmula (`=`, `+`, `-`, `@`) se neutralizan.

**Importar (solo ADMIN; solo datos maestros).** Flujo en dos pasos bajo `/api/admin/importaciones`: `GET /{tipo}/plantilla` (`proveedores`, `insumos`),
`POST /{tipo}/previsualizar` (multipart `archivo`; no crea nada), `POST /{id}/confirmar` (una transacción, idempotente), `GET /{id}/reporte` y
`DELETE /{id}`. Solo crea: un DNI/nombre repetido se omite y se reporta. Proveedores se crean con `ProveedorService.crear` (QR, historial de zona,
sincronización móvil) e insumos con `InsumoService.crear` (SALDO_INICIAL en el kardex). Migración `V26__importacion_excel.sql`.

## 9. Zonas históricas (Fase A de la auditoría de pagos/historial/IGV)

Migración `V27__zonas_historicas.sql` (solo aditiva). La zona de una **entrega** es la que tenía el proveedor *cuando se hizo*, no la actual.

- **Periodos por instante.** `historial_zona` guarda `inicio_en`/`fin_en` (`[inicio, fin)`, `fin_en` nulo = abierto), `zona_origen_id`, `motivo`
  y `usuario_id`. `desde`/`hasta` se conservan (`hasta` = último día completo, nunca anterior a `desde`). Un índice único parcial garantiza **un solo
  periodo abierto por proveedor**; `PUT /api/proveedores/{id}/zona` bloquea la fila del proveedor, así que dos cambios simultáneos se aplican uno tras otro.
- **`PUT /api/proveedores/{id}/zona?zonaId=…&motivo=…`** (ADMIN): `motivo` es opcional (hasta 200 caracteres; más → 400). Antes, reasignar el mismo día que se
  abrió el periodo lo dejaba con `hasta < desde`. Reasignar a la zona en la que el proveedor **ya está** se rechaza con **409** (no abre un periodo idéntico).
- **`GET /api/proveedores/{id}/historial-zona`** (ADMIN): además de los campos de siempre trae `inicioEn`, `finEn`, `zonaOrigenNombre`, `motivo`,
  `usuarioNombre`, `requiereRevision` y `notaRevision` (campos aditivos).
- **`registro_acopio.zona_id`.** Se asigna al registrar la entrega según su `fechaHora` (también si se sincroniza tarde, después de un cambio de zona). Si ningún
  periodo válido la cubre queda `NULL`: nunca se usa la zona actual como relleno. **No** se expone en `/api/registros-acopio*` (contratos de la app móvil sin
  cambios); los reportes siguen agrupando por la zona actual hasta la Fase E.
- **Datos anteriores.** Los periodos con `hasta < desde` (reasignaciones el mismo día con el código anterior) se **marcan** `requiere_revision` con duración cero
  y no se borran ni se usan para decidir una zona. Las entregas existentes reciben zona solo si un único periodo válido las cubre y el día no es un **día de
  cambio de zona** de ese proveedor (la hora del cambio no se registraba); el resto queda en `NULL`.
- **`GET /api/admin/zonas/revision`** (solo ADMIN, solo lectura): periodos marcados y entregas sin zona (primeras 500 + `totalEntregasSinZona`), con motivo
  `DIA_DE_CAMBIO`, `SIN_PERIODO` u `OTRO`. No corrige nada: la resolución de cada caso es una decisión manual pendiente.

## 10. Litros efectivos y ajustes posteriores (Fase B de la auditoría de pagos/historial/IGV)

Migración `V28__litros_efectivos.sql` (solo aditiva). Principios: los **litros reales recibidos** (`registro_acopio.litros`) **no se sobrescriben**; la cantidad solo cambia
con una **corrección justificada y registrada**; la **calidad ajusta el precio por litro, nunca los litros**.

- **Litros efectivos de una entrega** = `litrosCorregido` de la última corrección **válida** (con motivo) o, si no tiene, el original. Se derivan; el registro no se toca.
- **`POST /api/registros-acopio/{id}/correcciones`** (CALIDAD o ADMIN): `motivo` es **obligatorio** (texto libre; 400 si falta o es solo espacios) y la cantidad debe
  **cambiar** respecto de la vigente (la última corrección válida o el original; 400 si es igual tras redondear a 2 decimales). El registro se bloquea durante la
  operación: dos correcciones simultáneas se aplican una tras otra.
- **`POST /api/pagos/generar`** liquida los **litros efectivos** (antes liquidaba siempre el original, así que una corrección previa a liquidar no llegaba al pago). Las
  entregas del rango se bloquean mientras se genera; una entrega pertenece a lo sumo a un pago (`UNIQUE`). `PagoResponse` no cambia.
- **Líneas de liquidación (`pago_linea`).** Cada entrega liquidada conserva `litros_originales` (copia de auditoría), `litros_liquidados`, `precio_base` y la última
  corrección aplicada. `pago.litros_totales` es la suma de sus líneas. **`GET /api/pagos/{id}/lineas`** (ADMIN, RECEPCION; sin datos personales) devuelve por entrega:
  `litrosOriginales`, `litrosLiquidados`, `litrosContabilizados` (liquidados + ajustes posteriores), `litrosEfectivosActuales`, `precioBase`, `precioAplicado` y
  `correccionAplicadaId`.
- **Corrección posterior a la liquidación** → `AjustePago` = `litrosCorregido` − `litrosContabilizados` (lo liquidado más los ajustes previos de esa entrega), no contra el
  original. No se crea si no hay diferencia.
- **Valoración: precio efectivamente aplicado.** El ajuste se valora con el precio del pago **incluidos los ajustes de calidad** (`AjustePrecioPago`, de un pago completo, que
  se atribuye a todas las entregas de ese pago desde que se creó). Un ajuste **PENDIENTE** sigue al precio efectivo actual del pago; al **liquidarlo**
  (`PATCH /api/pagos/ajustes/{id}/liquidar`) el precio queda congelado (`precio_liquidado`). En `AjustePagoResponse`, `precioLitro` y `monto` son los **vigentes**; los campos
  aditivos `precioLitroAlCrear` y `montoAlCrear` conservan los del momento de la creación. Ejemplo: 100 L, corrección previa a 90, posterior a 85 y ajuste de calidad
  1,00 → 0,90: total del pago 90 × 0,90 = 81,00 y ajuste −5 × 0,90 = −4,50, neto 76,50 = 85 × 0,90.
- **Datos anteriores.** La migración crea una línea por entrega ya liquidada (`litros_liquidados` = el litro registrado, que coincide con `pago.litros_totales`; si algún pago
  no suma, la migración **se detiene** sin cambiar nada). `litros_originales` toma la primera `litros_anterior` de la entrega si fue corregida. **No** modifica `registro_acopio`,
  `pago`, `correccion_registro` ni los ajustes existentes (solo agrega la base y, en los ya liquidados, el precio congelado).
- Sin cambios en los contratos de la app móvil.

## 11. Calidad por entrega (Fase C de la auditoría de pagos/historial/IGV)

Migración `V29__ajuste_calidad_entrega.sql` (solo aditiva: una tabla nueva). Permite pagar **una entrega concreta** de un pago ya generado a un precio por litro distinto del
normal por razones de calidad (p. ej. 100 L de calidad deficiente a 0,90 dentro de un pago de 600 L a 1,00). **Se aplica a mano**; no existe regla automática de calidad a precio
(queda para la Fase F) ni categorías, umbrales o porcentajes: el precio lo fija quien lo registra.

- **`POST /api/pagos/{id}/lineas/{registroAcopioId}/ajuste-calidad`** (**solo ADMIN**): cuerpo `{precioLitroNuevo, analisisCalidadId, motivo}`. Exige el **análisis de calidad que lo
  sustenta** (debe ser de esa misma entrega; 400 si es de otra, 404 si no existe), un **motivo** (obligatorio, hasta 500 caracteres) y que el precio sea mayor que cero, con hasta
  3 decimales y **distinto del precio que la entrega tiene en ese momento** (400). Sin topes ni porcentajes. 404 si la entrega no está liquidada en ese pago. La línea se bloquea durante
  la operación: dos ajustes simultáneos se aplican uno tras otro y el "precio anterior" de cada uno es el que dejó el anterior. Respuesta 201 con el análisis (id, folio, resultado),
  el precio normal, el anterior, el nuevo y el `efectoEnElTotal` (= litros liquidados × (precio nuevo − precio normal); negativo = se paga menos; informativo).
- **`GET /api/pagos/{id}/ajustes-calidad`** (ADMIN, RECEPCION y **CALIDAD**; sin datos personales): historial del pago, del más reciente al más antiguo. CALIDAD **no** gana acceso a los
  pagos ni a `/lineas`: solo a este historial.
- **Es append-only** (igual que `ajuste_precio_pago`): no se edita ni se borra; corregir es registrar otro precio. El pago, sus líneas y sus ajustes de litros no se modifican.
- **Precio aplicado a una entrega** (`PrecioAplicado.deLinea`): su ajuste de calidad más reciente o, si no tiene, el precio efectivo de su pago (el último `ajuste-precio` del pago
  completo o, sin él, el precio normal). **"La entrega manda"**: una entrega con precio propio ignora el ajuste de precio del pago completo, que solo alcanza a las entregas sin precio
  propio. El **precio normal** de cada línea (`precioBase`) es el vigente el **día de la liquidación**, como hasta ahora.
- **Total reconocido.** `PagoResponse.totalAPagar` = suma de (litros liquidados × precio aplicado) de cada entrega, **redondeada una sola vez** a céntimos (half-up). Ejemplo: 600 L a 1,00
  con 100 L a 0,90 = 500 × 1,00 + 100 × 0,90 = **S/ 590,00** (con un precio único por pago habría sido 589,80). `total` y `precioLitro` siguen siendo los del pago original y
  `precioLitroEfectivo` el del pago (con su ajuste); el campo aditivo **`ajusteCalidad`** = `totalAPagar` − (litros × `precioLitroEfectivo`) (negativo = se paga menos por precios de calidad).
  La lista de pagos, el historial por proveedor, el detalle, la exportación a Excel y la **boleta PDF** usan el mismo total; la boleta añade la sección "Precios de calidad por entrega"
  (análisis, motivo y desglose).
- **`GET /api/pagos/{id}/lineas`** añade por entrega `precioPropioDeCalidad`, `analisisCalidadId` y `motivoAjusteCalidad`; `precioAplicado` es ahora el de esa entrega.
- **Ajustes de litros de una entrega** (Fase B): se crean y se valoran al **precio de esa entrega** (su precio de calidad o, si no tiene, el del pago): mientras están PENDIENTES siguen
  los cambios de ese precio y al liquidarlos se congelan.
- **`GET /api/analisis-calidad/registro/{registroAcopioId}/precios-calidad`** (**CALIDAD** y ADMIN; cierre de la Fase C): historial de precios de calidad de UNA entrega (más reciente primero),
  con el mismo contrato que `/ajustes-calidad`. Es el acceso de CALIDAD al historial desde su zona (los análisis): **no** da acceso a los pagos ni a sus líneas y no incluye datos del proveedor.
  Lista vacía si la entrega no tiene precios de calidad; 404 si el registro no existe; 403 para los demás roles.
- **Trazabilidad:** entrega → análisis → precio normal → precio aplicado → efecto en el total → liquidación (los pagos efectivos son una fase aparte).
- Sin cambios en los contratos de la app móvil (los campos nuevos de `PagoResponse` van al final y la app ignora los desconocidos).

## 12. Pagos efectivos a proveedores (V30)

Migración `V30__pagos_efectivos.sql` (solo aditiva: dos tablas nuevas; no toca `pago`, `pago_linea`, `ajuste_pago`, `ajuste_precio_pago` ni `ajuste_calidad_entrega`). `pago` sigue siendo
la **liquidación** (la deuda); los pagos efectivos son el **dinero realmente entregado**. Hasta ahora solo existía la marca `comprobante_generado`, que no es dinero pagado.

- **`POST /api/pagos-proveedor`** (**ADMIN y RECEPCION**): `{uuidCliente, proveedorId, modalidad (EFECTIVO|TRANSFERENCIA|DEPOSITO), monto, concepto?, banco?, nroOperacion?, nroRecibo?, observacion?,
  aplicaciones?: [{pagoId, monto}]}`. Transferencia y depósito **exigen banco y número de operación** (400); banco y operación se indican juntos. No se guardan cuentas completas. Sin
  `aplicaciones` el pago queda **sin aplicar (adelanto)**. 201 al crearlo; **200 si es un reenvío**.
- **Idempotencia.** Reenviar el mismo `uuidCliente` con los mismos datos devuelve el pago ya registrado (200); con datos distintos, 409. La misma operación bancaria (banco + número, sin distinguir
  mayúsculas ni espacios) entre pagos **vigentes**: con los mismos datos (proveedor, modalidad, monto…) es el mismo pago (200); con otros, 409. Un pago **anulado libera su operación**. Si dos
  solicitudes iguales llegan a la vez, la restricción única de la BD deja pasar una y la otra devuelve el resultado de la primera (nunca dos filas).
- **`POST /api/pagos-proveedor/{id}/aplicaciones`** (ADMIN y RECEPCION): `{aplicaciones: [{pagoId, monto}]}` (1–50, sin repetir liquidación). Aplica (parte de) lo que queda del pago a liquidaciones del
  **mismo proveedor** (400 si es de otro; 404 si no existe). **409** si el pago está anulado, si lo solicitado supera lo que le queda al pago o si el monto de una liquidación supera su saldo.
- **Concurrencia.** Aplicar bloquea (`FOR UPDATE`) el pago y, en orden de id, las liquidaciones involucradas; anular bloquea el pago. Dos aplicaciones simultáneas a la misma liquidación o al mismo pago se
  ejecutan una tras otra y ninguna pasa del tope (probado con hilos y con bloqueos tomados desde otra transacción). Registrar con aplicaciones es **todo o nada**.
- **`POST /api/pagos-proveedor/{id}/anular`** (**solo ADMIN**): `{motivo}` obligatorio. **No borra**: cambia el estado a ANULADO y deja motivo, usuario y fecha; sus aplicaciones se conservan pero
  **dejan de contar** en los saldos. 409 si ya estaba anulado. La BD lo exige: anulado ⇔ con motivo, usuario y fecha.
- **Lecturas** (ADMIN y RECEPCION): `GET /api/pagos-proveedor` (paginado; filtros `proveedorId`, `estado`=REGISTRADO|ANULADO, `busqueda` por proveedor, DNI, operación o recibo, sin acentos ni
  mayúsculas; más reciente primero), `GET /api/pagos-proveedor/{id}`. Ninguna respuesta trae **DNI ni teléfono**. Los importes no se editan.
- **Saldos (derivados, no se guardan; no hay un estado «pagado» duplicado).** `GET /api/pagos-proveedor/saldos?pagoIds=a,b` (hasta 100) y `GET /api/pagos-proveedor/proveedor/{id}/saldo`.
  Saldo de una liquidación = **total a pagar** (el total reconocido con precios de calidad, Fase C) + **ajustes de litros posteriores** (± , valorados a su precio vigente o congelado, Fase B) −
  aplicaciones de pagos no anulados. **Negativo = saldo a favor del proveedor** (por ejemplo, una corrección a la baja después de pagar). Estado: PENDIENTE (nada aplicado), PARCIAL, PAGADO (saldo 0),
  SALDO_A_FAVOR. El saldo del proveedor suma sus liquidaciones y resta los **adelantos sin aplicar** (`saldoNeto`).
- **Decisión de negocio (usuario):** los ajustes de litros entran dentro del saldo de **su** liquidación; «liquidar ajuste» sigue siendo solo la marca que congela su precio.
- **Fuera de alcance (pendiente):** constancia de pago (archivo adjunto: `ArchivoController` hoy no permite subir a RECEPCION) y reportes por zona (Fase E).
- Sin cambios en los contratos de la app móvil.

## 13. Historial consolidado del proveedor (Fase D)

**Solo lectura y sin migración**: reúne lo que ya existe (entregas con su zona de ese momento —Fase A—, litros efectivos —B—, calidad y precios propios —C—, liquidaciones, pagos y saldos
—pagos efectivos—, movimientos de zona). No guarda ni duplica datos, y los totales salen de las mismas fuentes que las demás pantallas, así que **cuadran con ellas**.

- **`GET /api/proveedores/{id}/historial/resumen`** (**ADMIN, RECEPCION y CALIDAD**; 404 si el proveedor no existe). Cada rol recibe **solo sus secciones**; una sección no autorizada llega **nula** y
  `secciones` dice cuáles llegaron: `ENTREGAS` (todos), `CALIDAD` (ADMIN y CALIDAD), `PAGOS` (ADMIN y RECEPCION) y `ZONAS` (solo ADMIN). Sin DNI ni teléfono.
  - `entregas`: total, litros recibidos (originales, inmutables) y efectivos (con la última corrección justificada), primera y última entrega, corregidas y **sin zona**.
  - `calidad`: resultado del **último** análisis de cada entrega (analizadas + sin análisis = entregas; aprobadas + observadas + rechazadas = analizadas), alertas de anomalía y entregas con precio de calidad propio.
  - `pagos`: liquidaciones, litros liquidados, total original, **total reconocido** (suma de los `totalAPagar`), ajuste por precios de calidad, ajustes de litros posteriores, total con ajustes, **pagado**
    (aplicado de pagos no anulados), saldo de las liquidaciones, adelantos sin aplicar y **saldo neto** (negativo = a favor del proveedor), más las entregas aún **sin liquidar** (no entran en esos totales).
    Identidades: `totalConAjustes = totalReconocido + ajustesLitros`; `saldoLiquidaciones = totalConAjustes − pagado`; `saldoNeto = saldoLiquidaciones − adelantosSinAplicar`.
  - `zonas`: los movimientos de zona (los mismos de `GET /api/proveedores/{id}/historial-zona`).
- **`GET /api/proveedores/{id}/historial/entregas?page&size`** (mismos roles): entregas del proveedor, más reciente primero. Por entrega: litros originales y efectivos, `corregida`, **`zonaNombre` de ese
  momento** (nula si el sistema no pudo determinarla: no se rellena con la zona actual), `calidad` (solo ADMIN y CALIDAD: último resultado, folio, nº de análisis, alertas, `precioPropio`) y
  `liquidacion` (solo ADMIN y RECEPCION; nula si la entrega aún no está liquidada: pago, semana, litros liquidados, precio aplicado e `importe` = litros × precio **sin redondear**; la suma de los
  importes de un pago, redondeada una vez, es su `totalAPagar`).
- Un historial muy largo se pagina en el servidor; el resumen recorre las entregas del proveedor (unos cientos al año).
- Sin cambios en los contratos de la app móvil.

## 14. Reportes por zona histórica (Fase E)

**Cambio de criterio, sin migración y sin cambios de contrato** (mismos campos y rutas). Todo reporte «por zona» pasa de agrupar por la **zona actual del proveedor** a agrupar por la **zona de cada
entrega** (`registro_acopio.zona_id`, la que tenía el proveedor cuando entregó, guardada desde la Fase A). Efectos, medidos sobre una copia de la base de desarrollo (ver `docs/fase-e-comparacion.md`
del panel):

- Un proveedor que cambió de zona **deja de arrastrar su historia** a la zona nueva (en esos datos, variación neta de entregas por zona: Zona Prueba −192, Chugur +105, Sorochuco +81, Bambamarca +3, y 3 que ya no cuentan en ninguna zona).
- Una entrega **sin zona determinada** (`zona_id` nulo; los casos de la pantalla de revisión) **no aparece en ninguna zona**. No se rellena con la zona actual y no se repara: sin filtro de zona sigue
  apareciendo.
- Sin filtro de zona la respuesta es **exactamente la misma** (3 276 entregas en la copia de prueba antes y después).
- Consultas que cambian: `GET /api/registros-acopio?zonaId=` y `/paginado?zonaId=` (y la exportación a Excel de acopios), `GET /api/registros-acopio/resumen-por-proveedor?zonaId=`,
  `GET /api/innovacion/alertas?zonaId=`, los litros en alerta y el % de rechazo por semana del panel económico, y las bases del forecasting por zona (litros por rango, promedio histórico,
  promedio por mes y suma semanal).
- **No cambian:** el score de confianza por zona (es del proveedor, no de una entrega), el filtro de proveedores por zona (estado actual) y los endpoints de zonas, rutas y comunicados.

## 15. Documentos del proveedor: DNI y RUC (Fase G, V31)

Migración `V31__documentos_proveedor.sql` (**aditiva, no modifica ningún valor**): `proveedor.tipo_persona` (`NATURAL` por defecto: todos los existentes), `ruc` (11 dígitos, único) y
`razon_social`; `dni` deja de ser NOT NULL (sigue siendo único). La base exige: natural ⇒ con DNI y sin razón social; empresa ⇒ con RUC y razón social y **sin DNI**.

- **Reglas (servidor, al crear y al editar):** persona natural = nombre y **DNI de 8 dígitos** obligatorios, **RUC opcional** en la **misma ficha** (debe ser `10` + su DNI + dígito verificador);
  empresa = **RUC** (empieza con `20`, dígito verificador módulo 11) y **razón social** obligatorios, sin DNI; su `nombre` es opcional (si falta se usa la razón social). DNI y RUC únicos (409).
  Espacios, guiones y puntos en DNI/RUC se quitan. Solo se valida la **forma** (no se consulta a SUNAT).
- **`POST /api/proveedores`** y **`PUT /api/proveedores/{id}`** (ADMIN): campos nuevos al final del cuerpo, `tipoPersona` (`NATURAL`|`EMPRESA`), `ruc`, `razonSocial`. **Compatibles**: un cuerpo
  anterior (nombre, dni, teléfono, zona) crea una persona natural como antes; en la edición, `tipoPersona` nulo conserva el tipo. Un DNI o RUC **sin cambios** se conserva aunque no cumpla el formato
  actual (registros anteriores): no bloquea editar el resto de la ficha.
- **`ProveedorAdminResponse`** agrega al final `tipoPersona`, `ruc`, `razonSocial`; `dni` es nulo en una empresa. La lista operativa/sincronización del **móvil no cambia** (sigue sin DNI ni RUC).
- **`GET /api/admin/proveedores/revision-documentos`** (ADMIN, solo lectura): `{totalProveedores, proveedoresConObservaciones, observaciones: [{proveedorId, nombre, tipoPersona, dni, ruc, activo,
  codigo, detalle}]}` con `codigo` = `DNI_FORMATO`, `DNI_PARECE_RUC`, `DNI_DUPLICADO_PROBABLE` (iguales salvo espacios o ceros), `RUC_INVALIDO`, `RUC_NO_COINCIDE_DNI` o `POSIBLE_MISMA_PERSONA`
  (el RUC 10 de una ficha contiene el DNI de otra). **No corrige nada** (decisión del usuario: conservar y reportar).
- **Búsqueda** por RUC y razón social además de nombre y DNI en `GET /api/proveedores`, `GET /api/pagos`, `GET /api/pagos-proveedor` y sus exportaciones.
- **Excel:** la exportación de proveedores agrega «Tipo de persona», «RUC» y «Razón social». La **plantilla de importación pasa a la versión 2** (tipo de persona con lista, nombre, DNI, RUC,
  razón social, teléfono, zona) y valida con las **mismas reglas** que la pantalla; una plantilla v1 se rechaza pidiendo descargar la actual. Un DNI **o RUC** existente se omite.
- **Boleta PDF** de pago a proveedor: muestra DNI y RUC cuando existen, y la razón social de una empresa. **El cálculo de los pagos no cambia** y no se presupone ninguna exoneración.

## 16. IGV configurable en ventas (Fase G, V32)

Migración `V32__igv_configurable.sql` (**aditiva y neutra**: no inserta tasas ni clasifica productos; no toca precios, ventas ni reservas). **Los precios son finales** (lo publicado es lo que paga
el cliente): el IGV **nunca se suma**; cuando corresponde se **desglosa dentro** del precio.

- **Cálculo APAGADO por defecto** y **encendible desde el panel** (V35, ver abajo): `app.tributario.igv.calculo-habilitado` (`TRIBUTARIO_IGV_CALCULO_HABILITADO`, `false`) solo es el valor inicial
  mientras nadie haya decidido. Apagado ⇒ las ventas se registran **exactamente como antes** (sin foto tributaria), el catálogo no dice «Incluye IGV» y el estado es **PENDIENTE**,
  aunque la configuración esté preparada.
- **Configuración (ADMIN, `/api/admin/tributario`, exclusiva de la web):** `GET /estado` (calculoHabilitado, `PENDIENTE`|`ACTIVO`, tasa vigente y programada, presentaciones activas por afectación,
  avisos); `GET|POST /tasas-igv` (`{tasa, vigenteDesde, motivo}`; 0 < tasa < 100, hasta 2 decimales; una fecha por tasa, 409); `GET /afectaciones?busqueda&page&size` (presentaciones activas con su
  afectación vigente hoy —`SIN_DEFINIR` si no tienen— y la programada); `GET|POST /presentaciones/{id}/afectaciones` (`{afectacion: GRAVADO|EXONERADO|INAFECTO|SIN_DEFINIR, vigenteDesde, motivo}`).
  Ambas series son **append-only con vigencia**, motivo y usuario (como los precios). **Ningún producto viene clasificado**.
- **Con el cálculo encendido**, al vender (web, carrito, retiro de reserva y móvil) cada línea guarda su **foto tributaria** a la **fecha de la venta**: gravada con tasa vigente ⇒ `valorVenta =
  round(subtotal / (1 + tasa/100), 2)` e `igv = subtotal − valorVenta` (suman exactamente lo cobrado; redondeo **por línea**, a confirmar con el contador); exonerada o inafecta ⇒ IGV 0; sin
  clasificar o gravada sin tasa ⇒ **sin determinar** (todo nulo). La base lo exige (`ck_venta_detalle_igv`). **Nunca se recalculan** ventas ya registradas ni importes de reservas.
- **Respuestas:** `DetalleVentaResponse` agrega `afectacionIgv`, `tasaIgv`, `valorVenta`, `igv` (nulos = sin determinar); `VentaResumenResponse` agrega `igv` = `{estado: SIN_DETERMINAR|
  DETERMINADO|PARCIAL, incluyeIgv, operacionesGravadas, operacionesExoneradas, operacionesInafectas, igvTotal, montoSinDeterminar}`. El catálogo público agrega `incluyeIgv` por presentación (solo
  gravada con tasa vigente, con el cálculo encendido y con precio). La exportación de ventas agrega «Afectación IGV», «Valor de venta» e «IGV incluido».
- **Contrato móvil sin cambios** (`VentaRequest`/`VentaResponse`).
- **Fuera de alcance:** clientes identificados en ventas, facturación electrónica, detracciones, retenciones y cualquier cambio en pagos a proveedores.

### 16.1 Interruptor del IGV desde el panel (V35)

Migración `V35__igv_calculo_activable.sql` (**aditiva**: una tabla nueva, `cambio_calculo_igv`, append-only; nace vacía). Quien administra decide cuándo se calcula el IGV; no hace falta tocar
variables de entorno ni reiniciar.

- `PUT /api/admin/tributario/calculo` (ADMIN) `{habilitado, motivo}` → `EstadoIgvResponse`. El **motivo es obligatorio** (también lo exige la base); pedir el estado en el que ya está ⇒ **409**.
  Cada cambio guarda quién, cuándo y por qué; el estado vigente es el **último** cambio y, si nunca hubo uno, el valor por defecto de la propiedad (apagado). El resultado se aplica de inmediato en
  este servidor (y como mucho tras 15 s en otro).
- `GET /estado` agrega `ultimoCambio` = `{habilitado, motivo, usuarioNombre, creadoEn}` o `null`. Con el cálculo apagado el aviso dice que un administrador puede activarlo desde la pantalla.
- `POST /api/admin/tributario/afectaciones/lote` (ADMIN) `{afectacion: GRAVADO|EXONERADO|INAFECTO, vigenteDesde, motivo, soloSinDefinir?}` → `{aplicadas, omitidas}`. Clasifica de una vez las
  presentaciones **activas**; por defecto (`soloSinDefinir` verdadero) solo las que no tienen afectación a esa fecha, y **nunca pisa** una decisión de la misma fecha ni repite la que ya rige. Cada
  presentación recibe su propio registro (se puede cambiar después una a una). `SIN_DEFINIR` no es una clasificación válida en lote.
- **Qué cambia al encender:** solo las **ventas nuevas** (precio final sin variar; el IGV sale por `precio × tasa / (100 + tasa)`, p. ej. 118,00 ⇒ 18,00). Encender o apagar **no recalcula** ventas
  ni reservas ya registradas. Lo que quede sin afectación (o sin tasa vigente) se vende **sin determinar**; el panel lo avisa antes de activar.

## 17. Clientes, comprador de cada venta e historial de compras (Fase H, V33)

Migración `V33__clientes_compradores.sql` (**aditiva**: no crea fichas a partir de ventas ni reservas; solo enlaza `venta.reserva_id` de los
retiros que ya existían). Endpoints **exclusivos de la web**; la app móvil no cambia (`POST /api/ventas` y la sincronización siguen aceptando
ventas sin comprador en cualquier canal).

- **Padrón voluntario** `/api/clientes` (ADMIN y VENTAS; VENTAS recibe teléfonos, DNI, RUC y correo **enmascarados**, `datosCompletos=false`):
  `GET` (paginado; `busqueda` por nombre, razón social, DNI, RUC o teléfono; `activo`, `mayorista`, `conComunicaciones`), `POST` (registro:
  `{nombre, telefono, correo?, tipoPersona?, dni?, ruc?, razonSocial?, autorizacionRegistro: {otorgada, forma}, comunicaciones?: {otorgada,
  medios, forma}, avisoVersion, confirmadoSinCoincidencia}` → 400 sin autorización; 409 si el DNI/RUC es de otra ficha o de un **proveedor**, o si
  el teléfono ya figura en otras fichas y no se confirmó que es otra persona), `GET /{id}`, `POST /{id}/telefonos`, `POST /{id}/comunicaciones`
  (`{otorgada, medios?, forma, avisoVersion?, nota?}`: otorgar o **revocar**), `GET /{id}/consentimientos` (evidencia), `GET /{id}/historial`,
  `GET /{id}/compras`. Solo ADMIN: `PUT /{id}` (datos), `DELETE /{id}/telefonos`, `PUT /{id}/mayorista` (`{mayorista, motivo}`),
  `PUT /{id}/desactivar|reactivar`, `POST /{id}/fusionar` (`{destinoId, motivo}`; manual; 409 si DNI/RUC distintos), `GET /{id}/fusiones`.
- **`POST /api/compradores/sugerencias`** (ADMIN y VENTAS): `{telefono?, dni?, ruc?, nombre?}` → fichas de cliente y de proveedor con
  `coincidencia` DOCUMENTO (misma persona) / TELEFONO / NOMBRE (solo pistas). Datos enmascarados. POST para no dejar datos en la URL.
- **Ventas** (`POST /api/ventas/carrito`): campo opcional `comprador: {tipo: CLIENTE|PROVEEDOR, id}`. Reglas: PUBLICO opcional; MAYORISTA = cliente
  habilitado por ADMIN; PROVEEDOR = proveedor del padrón; ficha desactivada → 400. En un retiro (`reservaId` o `POST /api/reservas/{id}/retirar`)
  el comprador es el confirmado en la reserva. `VentaResumenResponse` agrega `comprador {tipo, id, nombre, ruc}` (copia histórica; `tipo` null +
  `nombre` = nombre dado en la reserva, sin ficha), `reservaId` y `reservaCodigo`. La búsqueda de ventas incluye el nombre del comprador; la
  exportación agrega «Comprador».
- **Reservas**: `PUT /api/reservas/{id}/comprador` (`{tipo, id}`) y `DELETE /api/reservas/{id}/comprador` (VENTAS y ADMIN; solo PENDIENTE o
  CONFIRMADA; eventos COMPRADOR_VINCULADO/DESVINCULADO). `ReservaResponse` agrega `comprador`. `CrearReservaRequest` (público) admite
  `avisoPrivacidadVersion` opcional.
- **Proveedor**: el resumen consolidado agrega la sección `COMPRAS` (solo ADMIN) y `GET /api/proveedores/{id}/historial/compras` (ADMIN). Las
  liquidaciones de leche no cambian.
- **`GET /api/catalogo/privacidad`** (público): textos del aviso para la reserva, el registro y las promociones; `completo=false` y `faltantes`
  mientras no se configuren los datos del responsable (`app.privacidad.*`, variables `PRIVACIDAD_*`). Ver `docs/CLIENTES.md` del panel.

### 17.1 Refactor de clientes (V34): tipo, canal habitual, reservas en el padrón y Excel

Migración `V34__cliente_canal_habitual.sql` (**aditiva**): `cliente.canal_habitual` (`PUBLICO` por defecto, o `MAYORISTA` solo si la ficha es
mayorista; CHECK en la base). No cambia ventas ni reservas.

- **Tipo y canal no son lo mismo:** el *tipo* (`mayorista`) habilita el precio MAYORISTA; el *canal habitual* es el que el panel **propone** al
  vender. `POST /api/clientes` admite `mayorista?` (solo ADMIN; exige `motivoMayorista`; VENTAS → 400), `motivoMayorista?` y `canalHabitual?`
  (`PUBLICO`|`MAYORISTA`; `PROVEEDOR` o un valor desconocido → 400; `MAYORISTA` sin ser mayorista → 400). `PUT /{id}` admite `canalHabitual?`
  (null = no cambia). Quitar la condición de mayorista vuelve el canal a `PUBLICO`. Cada venta sigue validándose como antes.
- `ClienteResponse` agrega `canalHabitual` y `reservas {pendientes, confirmadas, activas, total, proximoRetiro}`: solo reservas **vinculadas** a la
  ficha, por **estado efectivo** (una vencida no cuenta como activa); calculado por página en una consulta. `CompradorSugerencia` agrega
  `canalHabitual` (null para proveedores).
- **`GET /api/exportaciones/clientes`** (ADMIN; datos completos): mismos filtros que `GET /api/clientes` (`activo`, `mayorista`,
  `conComunicaciones`, `busqueda`). Columnas: cliente, tipo de persona, razón social, DNI, RUC, teléfonos, correo, tipo de cliente, canal habitual,
  promociones, reservas activas, próximo retiro, registrado, activo.
- **Importación `clientes`** (`/api/admin/importaciones/clientes/...`, ADMIN, infraestructura común): cada fila se crea con el mismo
  `ClienteService.registrar` (mismas reglas y constancia). Teléfono y «Cómo autorizó el registro» obligatorios; promociones solo si la fila trae
  medios; DNI/RUC de otra ficha o de un proveedor, teléfono ya usado (salvo «Mismo teléfono, otra persona» = Sí) o repetidos en el archivo →
  DUPLICADA (se omite, **nunca se fusiona**); se revalida al confirmar.

---

*Generado a partir del código real del backend (14 Controllers, 73 endpoints, 28 tests unitarios en verde). Cualquier cambio a los Controllers/DTOs se refleja automáticamente en Swagger UI la próxima vez que la aplicación arranca — este archivo es el que hay que actualizar a mano si cambian las reglas de integración de las secciones 6 y 7.*
