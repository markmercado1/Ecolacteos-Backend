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

---

*Generado a partir del código real del backend (14 Controllers, 73 endpoints, 28 tests unitarios en verde). Cualquier cambio a los Controllers/DTOs se refleja automáticamente en Swagger UI la próxima vez que la aplicación arranca — este archivo es el que hay que actualizar a mano si cambian las reglas de integración de las secciones 6 y 7.*
