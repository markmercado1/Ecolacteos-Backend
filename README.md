# acopio-backend — CORE Spring Boot (Fase 2)

Implementación del CORE del backend para el Sistema de Gestión y Trazabilidad
de Acopio Lechero, siguiendo el diseño aprobado en `CORE_DESIGN.md` (Fase 1)
y las decisiones aprobadas para v1.

## Requisitos

- Java 17+
- Maven 3.9+
- PostgreSQL 15+ (para correr la app) / Docker (para los tests de integración con Testcontainers)

> Este proyecto se generó y revisó en un entorno sin acceso a Maven Central ni
> a un daemon de Docker, por lo que **no se pudo ejecutar `mvn compile` /
> `mvn test` para verificar la compilación real**. El código fue revisado
> manualmente con cuidado (firmas de métodos, nombres de columnas vs. entidades
> JPA, imports, tipos) pero se recomienda correr `mvn clean verify` como primer
> paso al recibir el proyecto, antes de continuar con más funcionalidad.

## Configuración

Variables de entorno (con defaults de desarrollo en `application.yml`):

| Variable | Default | Descripción |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/acopio_lechero` | URL de PostgreSQL |
| `DB_USER` | `acopio` | Usuario de BD |
| `DB_PASSWORD` | `acopio` | Password de BD |
| `JWT_SECRET` | (valor de desarrollo, **cambiar en producción**) | Secreto HMAC para firmar JWT (mínimo 256 bits) |
| `SERVER_PORT` | `8080` | Puerto HTTP |

El esquema de base de datos (`src/main/resources/db/schema.sql`) se aplica
automáticamente al arrancar (`spring.sql.init.mode=always`). Hibernate corre
en modo `validate`: **no crea ni modifica tablas**, solo valida que las
entidades JPA coincidan con el esquema ya aplicado por `schema.sql` (que es
la fuente de verdad, según la regla fundamental de Fase 1).

## Documentación de la API (Swagger / OpenAPI)

Con la aplicación corriendo:

| Recurso | URL |
|---|---|
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| Spec OpenAPI (JSON) | `http://localhost:8080/v3/api-docs` |

Documenta automáticamente los 73 endpoints del API a partir de los
`@RestController` y los DTOs reales (incluidas las validaciones Bean
Validation ya presentes en cada uno). Ver [`API_DOCUMENTATION.md`](API_DOCUMENTATION.md)
para cómo autenticarse desde Swagger UI, la organización por módulo y las
reglas de integración que la spec por sí sola no puede expresar.

## Levantar la base de datos localmente

```bash
docker run --name acopio-postgres -e POSTGRES_DB=acopio_lechero \
  -e POSTGRES_USER=acopio -e POSTGRES_PASSWORD=acopio \
  -p 5432:5432 -d postgres:15-alpine
```

## Compilar y correr

```bash
mvn clean package
java -jar target/acopio-backend-0.1.0.jar
```

## Correr los tests

```bash
mvn test
```

- **Tests unitarios** (`src/test/java/.../service/*Test.java`): JUnit 5 +
  Mockito, sin base de datos. Cubren las reglas de negocio priorizadas en
  `CORE_DESIGN.md §16`: idempotencia por `uuid_cliente`, el caso borde
  `litros_campo = 0` en conciliación, la fórmula del score de confianza,
  detección de anomalía por z-score, generación de pago (sin doble
  liquidación + trazabilidad exacta), regla de "un registro, un lote", batch
  de sincronización que no aborta ante un ítem fallido, y degradación con
  histórico corto en el forecasting.
- **Test de integración** (`src/test/java/.../integration/SchemaIntegrationTest.java`):
  levanta un PostgreSQL 15 real vía Testcontainers, aplica `schema.sql` tal
  cual se desplegaría, y confirma que la restricción `UNIQUE(uuid_cliente)`
  en `registro_acopio` existe realmente a nivel de base de datos (no solo en
  la anotación JPA) — requiere Docker disponible en la máquina donde se corra.

No hay usuario `ADMIN` sembrado por `schema.sql` (deliberado: crear el primer
usuario administrador es una decisión operativa del despliegue, fuera del
alcance del CORE). Para crear el primero, insértalo manualmente con un hash
BCrypt válido, o expón temporalmente el registro sin `@PreAuthorize` en un
perfil de arranque inicial.

## Alcance implementado (Fase 2)

Sigue estrictamente `CORE_DESIGN.md` y las decisiones aprobadas:

- 27 entidades JPA (25 tablas originales + `comunicado_zona` y
  `pago_registro_acopio`, ambas modeladas como `@ManyToMany`).
- Las 7 mejoras de SQL aprobadas ya aplicadas en `schema.sql`.
- `uuid_cliente` (idempotencia offline-first) en `registro_acopio`,
  `analisis_calidad`, `lote_produccion` y `venta`.
- Un `RegistroAcopio` pertenece a un único `LoteProduccion` en v1, validado en
  `LoteProduccionService` (el esquema N:M se mantiene para no cerrar la puerta
  a un reparto futuro, pero no se usa en v1).
- Trazabilidad exacta `Pago` ↔ `RegistroAcopio` vía `pago_registro_acopio`.
- `Venta` sin control de stock de queso (fuera de alcance v1, decisión aprobada).
- Autorización por rol según la matriz de `CORE_DESIGN.md §15` (incluye
  `pagos`: lectura `ADMIN`+`RECEPCION`, escritura solo `ADMIN`).
- Tolerancia de `fechaHora` en `RegistroAcopio`: -30 días / +5 minutos.
- Módulo de innovación (RF-INN-01 a 05) completo: `ScoreConfianzaService`,
  `AnomaliaService` (detección + predicción), `ForecastingService`,
  `PanelEconomicoService`, con jobs semanales en `scheduler/InnovacionScheduler`.
- `SyncService` con procesamiento batch tolerante a fallos parciales y
  descarga incremental de catálogos (`/api/sync/cambios`).

## Inferencias y limitaciones documentadas en el código

Estas decisiones no estaban completamente especificadas en el documento
fuente ni en `CORE_DESIGN.md`, y se resolvieron con una inferencia explícita
(comentada en el código, buscar `Inferencia` / `LIMITACION`):

- **`RIESGO_ADULTERACION`** (`AnomaliaService`): el documento fuente
  condiciona la alerta a "temporada seca", concepto no modelado en el SQL
  aprobado. Se implementó una heurística verificable con los datos
  disponibles (`agua_anadida = true` reciente para el proveedor) en vez de
  inventar un calendario de meses secos.
- **Resultado automático de `AnalisisCalidad`** (`AnalisisCalidadService`):
  regla simple (agua añadida → `RECHAZADO`, si no → `APROBADO`); un flujo de
  revisión manual más fino no está descrito con detalle suficiente en el
  documento fuente.
- **Margen por canal** (`PanelEconomicoService` / `VentaRepository`): es un
  dato global de planta, no filtrado por zona, porque `venta` no tiene
  relación con zona en el SQL aprobado.
- **Simulador "qué pasa si"** (`PanelEconomicoService`): la regresión lineal
  entre score y % de rechazo devuelve `null` (no un número inventado) cuando
  no hay suficientes puntos históricos alineados por periodo.
- **Forecasting con histórico insuficiente**: factor estacional neutro (1.0)
  en vez de fallar por división por cero.

## Próximos pasos sugeridos

1. Ejecutar `mvn clean verify` para confirmar compilación y tests en un
   entorno con acceso a Maven Central y Docker.
2. Sembrar los roles base (`ACOPIADOR, CALIDAD, RECEPCION, PRODUCCION, VENTAS, ADMIN`)
   y un primer usuario `ADMIN`.
3. Revisar y ajustar `JWT_SECRET` para el entorno real antes de cualquier
   despliegue.
4. Definir la estrategia de despliegue/infraestructura (pendiente por
   decisión explícita, `CORE_DESIGN.md §18` punto 9).
