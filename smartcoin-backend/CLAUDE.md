# Backend

API REST de Smartcoin. Leer primero el `CLAUDE.md` de la raíz.

## Stack

- Java 25 (LTS; mínimo 21) y Spring Boot 4.1.1, la versión estable más reciente al crear el proyecto con Spring Initializr. Maven con Maven Wrapper. Hace falta un **JDK** (con `javac`), no solo un JRE.
- Dependencias: Spring Web, Validation, Data JPA, Security, OAuth2 Resource Server (valida el JWT), Flyway con su módulo de MySQL (`flyway-mysql`), MySQL Connector/J y springdoc-openapi con Swagger UI.
- Tests: JUnit 6 (Boot 4 lo trae; la API de `org.junit.jupiter` es la misma que en JUnit 5), AssertJ, Mockito y `spring-security-test`. En Boot 4 vienen de un starter de test por módulo (`spring-boot-starter-webmvc-test`, `spring-boot-starter-security-test`, etc.), que arrastran `spring-boot-starter-test`. `@WebMvcTest` está en `org.springframework.boot.webmvc.test.autoconfigure`. Jackson es la versión 3 (`tools.jackson`).
- Sin Lombok. DTOs como `record`.
- Paquete base: `com.smartcoin`. Grupo Maven `com.smartcoin`, artefacto `smartcoin-backend`.

## Comandos

| Qué | Comando |
|---|---|
| Levantar la API | `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` (Windows: `mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"`); sin perfil `local`, las variables deben estar en el entorno |
| Tests | `./mvnw test` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| Exportar el contrato (con la API levantada) | `curl -s http://localhost:8080/v3/api-docs -o ../docs/openapi.json` |

En PowerShell, `curl` es un alias de otro comando: usar `curl.exe` o `Invoke-WebRequest -Uri http://localhost:8080/v3/api-docs -OutFile ../docs/openapi.json`.

## Base de datos local

Una sola base, creada una vez a mano:

```sql
CREATE DATABASE smartcoin CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci;
CREATE USER 'smartcoin'@'localhost' IDENTIFIED BY '<contraseña>';
GRANT ALL PRIVILEGES ON smartcoin.* TO 'smartcoin'@'localhost';
```

Tiene datos reales. Hacer un respaldo con `mysqldump` antes de aplicar migraciones que cambien tablas con datos.

## Configuración

Todo lo sensible viene de variables de entorno. Alternativa: `smartcoin-backend/config/application-local.yml` con el perfil `local` (Spring Boot lo lee de `./config/` al correr desde `smartcoin-backend/`), listado en `.gitignore`, así no viaja dentro del jar.

| Variable | Ejemplo | Notas |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/smartcoin` | |
| `DB_USER` | `smartcoin` | |
| `DB_PASSWORD` | | |
| `APP_JWT_SECRET` | 32 o más caracteres aleatorios | Obligatoria: sin ella la aplicación no arranca. |
| `APP_ADMIN_KEY` | Clave larga aleatoria | Si falta o está vacía, el alta y el restablecimiento de usuarios quedan deshabilitados. |

Si falta `DB_URL`, `DB_USER`, `DB_PASSWORD` o `APP_JWT_SECRET`, `RequiredConfigurationCheck` corta el arranque y nombra las variables que faltan. `DB_PASSWORD` puede estar definida y vacía. Los tests de capa web lo desactivan con `app.required-config-check=false` en `src/test/resources/application.properties`.

`application.yml` versionado:

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate.jdbc.time_zone: UTC
  flyway:
    locations: classpath:db/migration/{vendor}
    clean-disabled: true

app:
  zone: America/Argentina/Mendoza
  budget:
    horizon-months: 24
    early-days: 10
  security:
    jwt-secret: ${APP_JWT_SECRET}
    jwt-expiration: 8h
    admin-key: ${APP_ADMIN_KEY:}
    password-min-length: 10
```

Las propiedades `app.*` se leen con clases `@ConfigurationProperties` validadas al arrancar.

## Estructura

Paquetes por funcionalidad; dentro de cada uno, las mismas capas.

```
com.smartcoin
├── shared/       configuración, Clock, seguridad, manejo de errores, convertidores
├── user/         usuarios, alta y restablecimiento por administrador, login, cambio de contraseña
├── account/      cuentas y saldos
├── category/
├── budgetitem/   Conceptos, generación de partidas, horizonte
├── period/       períodos, vista del mes, cierre de mes
├── entry/        partidas, consolidación, propagación
├── movement/
├── transfer/
└── report/       flujo de caja, proyección, vista de varios meses
```

Capas dentro de cada paquete:

| Subpaquete | Contenido |
|---|---|
| `domain` | Entidades JPA y **reglas puras**: clases sin Spring ni JPA que reciben valores y devuelven resultados. |
| `service` | Casos de uso. Cargan datos, aplican las reglas, guardan. Son los únicos `@Transactional`. |
| `repository` | Interfaces Spring Data. Todo método recibe el `userId`. |
| `web` | Controladores y DTOs (`record`). Sin lógica de negocio. |

Reglas puras previstas, cada una con su test unitario exhaustivo:

| Clase | Regla |
|---|---|
| `DueDateCalculator` | RN-12 |
| `BudgetItemEditability` | RN-15: qué datos de un Concepto no se editan (tipo, periodicidad, inicio, fin, cuotas) y el motivo |
| `BudgetItemEditEffects` | RN-15: a qué partidas alcanza cada cambio (vencimiento, cuenta, monto vigente) |
| `ScheduleCalculator` | RN-11, RN-13, RN-14: qué períodos corresponden y el índice k de cada uno, del que sale el número de cuota |
| `EstimationCalculator` | RN-26: base, estimación y destino |
| `EntryAmounts` | RN-16, RN-17: real, pendiente, estimado y estado mostrado de una partida |
| `OverdueRule` | RN-20: partida vencida |
| `EntryDueDateRange` | RN-19: rango del vencimiento de una partida sin Concepto |
| `MonthTotals` | RN-44: totales por moneda de ingresos y de gastos, y resultado |
| `MovementDateValidator` | RN-21: ventana, apertura, fecha futura |
| `BalanceCalculator` | RN-35 |
| `ClosingPlanner` | RN-38 a RN-40: a partir de los datos del período, el plan de cierre (consolidaciones, partidas nuevas, cierres de cuenta) |

## Convenciones

- **Usuario actual**: un componente `CurrentUser` obtiene el id del token. Nunca se toma de la ruta, el cuerpo o la consulta.
- **Aislamiento**: los repositorios buscan por `id` y `userId` (`findByIdAndUserId`). Si no encuentra, `NOT_FOUND` (404).
- **Seguridad**: emisión del JWT con `NimbusJwtEncoder` y validación con el Resource Server, HS256 con `APP_JWT_SECRET`. Claims: `sub` (id del usuario), `cv` (versión de credenciales), `iat`, `exp`. En cada pedido, un filtro carga el usuario y aplica RN-50 (habilitado, versión, cambio obligatorio).
- **Clave de administración**: se compara con `MessageDigest.isEqual` sobre los bytes, nunca con `equals`.
- **Generación de partidas** (RN-13): siempre con `EntryGenerator.generate(Concepto, horizonte)`, dentro de la transacción del caso de uso y después de `HorizonService.ensureHorizon`, que crea los períodos destino.
- **Transacciones**: consolidar, pago rápido, eliminar con alcance y cerrar el mes son una sola transacción cada uno.
- **Errores**: las reglas lanzan `BusinessException(code, detail)`; un `@RestControllerAdvice` las convierte a `ProblemDetail` con `code` y el HTTP de la tabla de códigos de `reglas-de-negocio.md`. Los errores de Bean Validation salen como `VALIDATION_ERROR` con `errors` por campo. Un dato del cuerpo que solo se puede validar con datos (fin anterior al inicio, referencia a una cuenta o categoría que no existe para el usuario, D-24) se lanza con `BusinessException.invalidField(campo, detail)` y sale igual. Los mensajes `detail` van en español.
- **API**:
  - Prefijo `/api`, JSON en `camelCase`.
  - Fechas ISO (`2026-11-25`), períodos `YYYY-MM`, montos como número con 2 decimales, enums con los valores del glosario.
  - Alta: 201 con el recurso. Eliminación: 204. Listas sin paginación (el volumen es chico).
  - `PATCH`: solo cambia lo que se envía; un campo omitido o `null` significa «no cambia». Lo que se puede vaciar se vacía con un campo aparte (`clearCategory`), porque Jackson 3 no distingue un campo ausente de un `null` (D-30).
  - Las respuestas de partidas incluyen los valores derivados (real, pendiente, estimado, estado mostrado, total de cuotas) calculados en el backend.
  - Cada endpoint documentado con anotaciones de springdoc, en español.
- **Dinero**: `BigDecimal` con escala 2. Comparar con `compareTo`, nunca con `equals`. Redondear solo donde lo indica una regla, con `RoundingMode.HALF_UP`.
- **Fechas**: `LocalDate` y `YearMonth` para negocio; `Instant` para fechas técnicas. "Hoy" con `LocalDate.now(clock)`.
- **Enums**: `@Enumerated(EnumType.STRING)`.
- **Logs**: nunca contraseñas, tokens ni la clave de administración. Los datos financieros (montos, nombres de partidas) solo en nivel DEBUG.

## Tests

| Qué | Cómo |
|---|---|
| Reglas puras (`domain`) | JUnit + AssertJ, sin Spring ni base. Tests parametrizados para las tablas de ejemplos. |
| Servicios | JUnit + Mockito, con repositorios simulados y un `Clock` fijo. |
| Controladores y seguridad | `@WebMvcTest` con MockMvc y servicios simulados (`@Import` de `SecurityConfig` y `GlobalExceptionHandler`, y `app.security.jwt-secret` por `@TestPropertySource`; en Boot 4 `@WebMvcTest` no carga clases `@Configuration`, así que un controlador con un período en la ruta importa también `WebConfig`, que tiene su convertidor): 401, 403 por cambio obligatorio, clave de administración, formato de errores. |

### Aislamiento entre usuarios (HU-06, RN-01)

Tres tests en `src/test/java/com/smartcoin/shared/isolation/` fallan solos si una historia nueva rompe el aislamiento. No hace falta tocarlos al agregar controladores o repositorios: toman todo del registro de Spring o del classpath.

| Test | Qué verifica |
|---|---|
| `EndpointsRequireTokenTest` | Todo endpoint registrado responde 401 sin token. `@WebMvcTest` con todos los controladores; las dependencias de sus constructores se simulan solas (`ControllerDependencyMocks`). Excepciones explícitas: `/api/auth/login`; `/api/admin/**` (se autentica con `X-Admin-Key`: también se verifica el 401 con clave incorrecta); Swagger y `/v3/api-docs` no figuran en el registro. |
| `RequestDtosHaveNoUserFieldTest` | Ningún tipo usado como `@RequestBody`, ni los que contiene, tiene un componente `userId` o `user`. Los DTO de respuesta no se revisan. |
| `RepositoryConventionTest` | Todo método **declarado** en un repositorio recibe `userId`. Exceptuado `UserRepository`: `User` es el propio usuario y se lo busca por email o por el id del token. Los métodos heredados de `JpaRepository` (`findById`, `findAll`, `deleteById`) no se pueden controlar: no usarlos con datos del usuario. |

Qué hace cada historia que agrega un recurso (cuentas, categorías, Conceptos, partidas, movimientos, transferencias), porque los criterios 2, 3 y 5 de HU-06 no tienen test automático genérico:

1. Repositorio con `findByIdAndUserId` y listados `...ByUserId`; sin usar `findById` ni `findAll`.
2. Test de servicio: un id que pertenece a otro usuario produce `BusinessException` con `NOT_FOUND` en leer, modificar y eliminar (repositorio simulado que devuelve vacío para ese `userId`).
3. Test de servicio de listado y totales: se consulta solo con el `userId` del usuario actual (`verify` sobre el repositorio).
4. Los DTO de entrada no llevan usuario, y los endpoints nuevos exigen token. No agregar excepciones al test de token.

Los repositorios de Concepto, partida, movimiento, transferencia y cierre de cuenta nacen en HU-07 solo con consultas de existencia (`existsByUserIdAndAccountId`, primera fecha de movimiento y de transferencia): lo que necesita el servicio de cuentas. Cada historia dueña de su tabla agrega el resto.

No hay una clase base compartida para el punto 2: con un solo patrón de dos líneas no ahorra repetición. Si varias historias repiten el mismo código, extraerlo entonces.

**Prohibido por ahora**: `@SpringBootTest`, `@DataJpaTest` y cualquier test que se conecte a MySQL, porque la única base tiene datos reales. Si una consulta necesita test de integración, se anota como pendiente en el PR o la historia, para cuando haya Docker y Testcontainers.

Casos límite que siempre deben tener test:

- Vencimientos: día 29, 30 y 31 en febrero, año bisiesto, desfase −1 en enero (vence en diciembre del año anterior).
- Periodicidades bimestral, trimestral, semestral y anual, con inicio en cualquier mes.
- Cuotas con primera cuota distinta de 1 y con una sola cuota.
- Promedio con 1, 2, 3 y más de 3 consolidaciones; redondeo `HALF_UP`; consolidaciones de cierre excluidas; consolidación fuera de orden.
- Partidas editadas con `KEEP`, con `OVERWRITE` y sin política.
- Ventana de anticipación en el día exacto del límite y un día antes.
- Saldo al fin de mes con cobros anticipados de partidas del mes siguiente.
- Diferencia de cierre positiva, negativa y cero; postergar con pendiente cero.
- Eliminar con `THIS_AND_FUTURE` cuando una partida futura tiene movimientos.

## Migraciones

- Ubicación: `src/main/resources/db/migration/mysql/`, con nombres `V{n}__descripcion_en_snake_case.sql`.
- `V1` crea el esquema completo de `docs/modelo-de-datos.md`.
- Nunca se edita una migración aplicada; todo cambio es una migración nueva y se refleja en `docs/modelo-de-datos.md`.
- SQL lo más estándar posible: `VARCHAR` con `CHECK` en lugar de `ENUM`, sin funciones propias de MySQL, sin palabras reservadas como nombres.
