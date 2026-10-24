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

---

*Generado a partir del código real del backend (14 Controllers, 73 endpoints, 28 tests unitarios en verde). Cualquier cambio a los Controllers/DTOs se refleja automáticamente en Swagger UI la próxima vez que la aplicación arranca — este archivo es el que hay que actualizar a mano si cambian las reglas de integración de las secciones 6 y 7.*
