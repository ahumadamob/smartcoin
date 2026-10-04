# Backend

API REST de Smartcoin. Leer primero el `CLAUDE.md` de la raíz.

## Stack

- Java LTS (21 o posterior) y la versión estable más reciente de Spring Boot al crear el proyecto con Spring Initializr. Maven con Maven Wrapper.
- Dependencias: Spring Web, Validation, Data JPA, Security, OAuth2 Resource Server (valida el JWT), Flyway con su módulo de MySQL (`flyway-mysql`), MySQL Connector/J y springdoc-openapi con Swagger UI.
- Tests: JUnit 5, AssertJ, Mockito y `spring-security-test` (vienen con `spring-boot-starter-test`).
- Sin Lombok. DTOs como `record`.
- Paquete base: `com.smartcoin`. Grupo Maven `com.smartcoin`, artefacto `smartcoin-backend`.

## Comandos

| Qué | Comando |
|---|---|
| Levantar la API | `./mvnw spring-boot:run` (Windows: `mvnw.cmd spring-boot:run`) |
| Tests | `./mvnw test` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| Exportar el contrato (con la API levantada) | `curl -s http://localhost:8080/v3/api-docs -o ../docs/openapi.json` |

En PowerShell, `curl` es un alias de otro comando: usar `curl.exe` o `Invoke-WebRequest -Uri http://localhost:8080/v3/api-docs -OutFile ../docs/openapi.json`.

## Base de datos local

Una sola base, creada una vez a mano:

```sql
CREATE DATABASE smartcoin CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'smartcoin'@'localhost' IDENTIFIED BY '<contraseña>';
GRANT ALL PRIVILEGES ON smartcoin.* TO 'smartcoin'@'localhost';
```

Tiene datos reales. Hacer un respaldo con `mysqldump` antes de aplicar migraciones que cambien tablas con datos.

## Configuración

Todo lo sensible viene de variables de entorno. Alternativa: un `application-local.yml` con el perfil `local`, listado en `.gitignore`.

| Variable | Ejemplo | Notas |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/smartcoin` | |
| `DB_USER` | `smartcoin` | |
| `DB_PASSWORD` | | |
| `APP_JWT_SECRET` | 32 o más caracteres aleatorios | Obligatoria: sin ella la aplicación no arranca. |
| `APP_ADMIN_KEY` | Clave larga aleatoria | Si falta o está vacía, el alta y el restablecimiento de usuarios quedan deshabilitados. |

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
| `ScheduleCalculator` | RN-11, RN-13, RN-14: qué períodos y qué número de cuota corresponden |
| `EstimationCalculator` | RN-26: base, estimación y destino |
| `MovementDateValidator` | RN-21: ventana, apertura, fecha futura |
| `BalanceCalculator` | RN-35 |
| `ClosingPlanner` | RN-38 a RN-40: a partir de los datos del período, el plan de cierre (consolidaciones, partidas nuevas, cierres de cuenta) |

## Convenciones

- **Usuario actual**: un componente `CurrentUser` obtiene el id del token. Nunca se toma de la ruta, el cuerpo o la consulta.
- **Aislamiento**: los repositorios buscan por `id` y `userId` (`findByIdAndUserId`). Si no encuentra, `NOT_FOUND` (404).
- **Seguridad**: emisión del JWT con `NimbusJwtEncoder` y validación con el Resource Server, HS256 con `APP_JWT_SECRET`. Claims: `sub` (id del usuario), `cv` (versión de credenciales), `iat`, `exp`. En cada pedido, un filtro carga el usuario y aplica RN-50 (habilitado, versión, cambio obligatorio).
- **Clave de administración**: se compara con `MessageDigest.isEqual` sobre los bytes, nunca con `equals`.
- **Transacciones**: consolidar, pago rápido, eliminar con alcance y cerrar el mes son una sola transacción cada uno.
- **Errores**: las reglas lanzan `BusinessException(code, detail)`; un `@RestControllerAdvice` las convierte a `ProblemDetail` con `code` y el HTTP de la tabla de códigos de `reglas-de-negocio.md`. Los errores de Bean Validation salen como `VALIDATION_ERROR` con `errors` por campo. Los mensajes `detail` van en español.
- **API**:
  - Prefijo `/api`, JSON en `camelCase`.
  - Fechas ISO (`2026-11-25`), períodos `YYYY-MM`, montos como número con 2 decimales, enums con los valores del glosario.
  - Alta: 201 con el recurso. Eliminación: 204. Listas sin paginación (el volumen es chico).
  - Las respuestas de partidas incluyen los valores derivados (real, pendiente, estimado, estado mostrado, total de cuotas) calculados en el backend.
  - Cada endpoint documentado con anotaciones de springdoc, en español.
- **Dinero**: `BigDecimal` con escala 2. Comparar con `compareTo`, nunca con `equals`. Redondear solo donde lo indica una regla, con `RoundingMode.HALF_UP`.
- **Fechas**: `LocalDate` y `YearMonth` para negocio; `Instant` para fechas técnicas. "Hoy" con `LocalDate.now(clock)`.
- **Enums**: `@Enumerated(EnumType.STRING)`.
- **Logs**: nunca contraseñas, tokens ni la clave de administración. Los datos financieros (montos, nombres de partidas) solo en nivel DEBUG.

## Tests

| Qué | Cómo |
|---|---|
| Reglas puras (`domain`) | JUnit 5 + AssertJ, sin Spring ni base. Tests parametrizados para las tablas de ejemplos. |
| Servicios | JUnit 5 + Mockito, con repositorios simulados y un `Clock` fijo. |
| Controladores y seguridad | `@WebMvcTest` con MockMvc y servicios simulados: 401, 403 por cambio obligatorio, clave de administración, formato de errores. |

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
