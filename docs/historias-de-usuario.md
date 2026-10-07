# Historias de usuario

Backlog funcional del proyecto, ordenado para avanzar en cortes verticales: cada historia se termina completa (reglas con tests, endpoint y pantalla) antes de pasar a la siguiente. Las reglas citadas (RN-xx) están en `reglas-de-negocio.md`; los códigos de error, al final de ese documento.

Formato de cada historia: qué se quiere y para qué, criterios de aceptación verificables y reglas relacionadas. Los endpoints de cada épica son el diseño inicial; el contrato real es `openapi.json`.

## Orden de implementación

| Iteración | Historias | Al terminar se puede… |
|---|---|---|
| 0 | HT-01 a HT-03 | Levantar backend y frontend, ver Swagger y tener el esquema creado. |
| 1 | HU-01 a HU-06 | Crear un usuario por Swagger, entrar y cambiar la contraseña. |
| 2 | HU-07 a HU-09 | Cargar cuentas con su saldo inicial, y categorías. |
| 3 | HU-10 a HU-15 | Crear Conceptos, ver 24 meses de partidas generadas y recorrer los meses. |
| 4 | HU-16 a HU-18 | Agregar partidas puntuales, editar montos y dar de baja. |
| 5 | HU-19 a HU-22 | Registrar cobros y pagos, parciales y anticipados. |
| 6 | HU-23 a HU-26 | Consolidar y ver cómo se ajustan los meses siguientes. |
| 7 | HU-27 a HU-29 | Transferir entre cuentas y comprar o vender dólares. |
| 8 | HU-30 a HU-33 | Cerrar el mes. |
| 9 | HU-34 a HU-36 | Flujo de caja, proyección de saldos y vista de varios meses (propuestas, S-18). |

---

## Iteración 0 · Base técnica

### HT-01 · Esqueleto del backend

Proyecto Spring Boot listo para crecer, según `smartcoin-backend/CLAUDE.md`.

1. Se crea con Spring Initializr (Maven, Java LTS) con las dependencias listadas en `smartcoin-backend/CLAUDE.md`.
2. La configuración sale de variables de entorno; sin `APP_JWT_SECRET` o sin datos de la base, la aplicación no arranca y lo dice claramente.
3. Existe un `Clock` con la zona configurada, un manejador global de errores que responde Problem Details con `code`, y el convertidor de `YearMonth`.
4. Swagger UI responde en `/swagger-ui.html` y `/v3/api-docs` devuelve el contrato.
5. `./mvnw test` pasa sin conectarse a la base.

### HT-02 · Esquema inicial

1. `V1__esquema_inicial.sql` crea las 9 tablas de `modelo-de-datos.md` con todas sus restricciones e índices.
2. Al arrancar, Flyway aplica la migración e Hibernate valida el esquema (`ddl-auto: validate`) sin errores.
3. Las entidades JPA mapean todas las columnas con los tipos de la tabla "Mapeo en Java".

### HT-03 · Esqueleto del frontend

Proyecto Angular según `smartcoin-frontend/CLAUDE.md`.

1. `npm start` levanta la aplicación con proxy de `/api` al backend.
2. `npm run generate:api` genera el cliente desde `../docs/openapi.json`.
3. Locale `es-AR` registrado: montos como `$ 1.234,50` y `US$ 1.234,50`, fechas como `dd/MM/yyyy`.
4. Estructura de carpetas, layout con menú lateral y ruteo vacío para las pantallas de la tabla de `smartcoin-frontend/CLAUDE.md`.
5. Playwright instalado con un test de humo que abre la aplicación.

---

## Épica 1 · Usuarios y acceso

### HU-01 · Alta de usuario por administrador

**Como** administrador **quiero** crear usuarios desde Swagger o Postman **para** dar acceso sin que exista un registro público.

1. Con `X-Admin-Key` correcto, `POST /api/admin/users` con email, contraseña inicial y período inicial crea el usuario y responde 201 con id, email y período inicial. Nunca devuelve la contraseña.
2. Sin período inicial, se usa el período actual. Un período inicial posterior al actual responde 400.
3. El usuario queda habilitado, con cambio de contraseña obligatorio y con sus períodos creados desde el inicial hasta el horizonte, todos abiertos.
4. Sin header, con una clave incorrecta, o si `APP_ADMIN_KEY` no está configurada: 401 y no se crea nada.
5. Email ya existente, sin distinguir mayúsculas: 409 `EMAIL_ALREADY_EXISTS`.
6. Email inválido o contraseña de menos de 10 caracteres: 400 `VALIDATION_ERROR`.

Reglas: RN-06, RN-07, RN-47, RN-51.

### HU-02 · Restablecer contraseña por administrador

**Como** administrador **quiero** asignar una contraseña temporal **para** que un usuario que la olvidó vuelva a entrar.

1. `POST /api/admin/users/password-reset` con `X-Admin-Key`, email y contraseña temporal responde 204.
2. El usuario queda con cambio de contraseña obligatorio, y los tokens emitidos antes dejan de funcionar (401).
3. Clave inválida: igual que en HU-01. Email inexistente: 404.

Reglas: RN-48, RN-50.

### HU-03 · Iniciar y cerrar sesión

**Como** usuario **quiero** entrar con mi email y contraseña **para** trabajar con mis datos.

1. Credenciales válidas: 200 con token, vencimiento y `mustChangePassword`.
2. El email no distingue mayúsculas.
3. Usuario inexistente, contraseña incorrecta o usuario deshabilitado: 401 con el mismo mensaje genérico.
4. Al iniciar sesión se asegura el horizonte (RN-07).
5. Con un token vencido, inválido o revocado la API responde 401, y el frontend vuelve al login.
6. "Cerrar sesión" descarta el token y vuelve al login.
7. Pantalla de login: email, contraseña y mensaje de error. Después del login va al cambio de contraseña si es obligatorio, o al presupuesto del mes actual.

Reglas: RN-07, RN-49, RN-50.

### HU-04 · Cambio de contraseña obligatorio

**Como** usuario nuevo **quiero** que se me obligue a cambiar la contraseña inicial **para** que solo yo la conozca.

1. Con el cambio pendiente, cualquier endpoint salvo `POST /api/auth/change-password` y `GET /api/auth/me` responde 403 `PASSWORD_CHANGE_REQUIRED`.
2. En el frontend, con el cambio pendiente, cualquier ruta lleva a la pantalla de cambio de contraseña.
3. El cambio exige la contraseña actual y una nueva de al menos 10 caracteres y distinta de la actual. Si es correcto, responde con un token nuevo y el cambio deja de ser obligatorio.
4. Contraseña actual incorrecta: 400 `INVALID_CURRENT_PASSWORD`. Nueva inválida: 400 `VALIDATION_ERROR`.
5. El token anterior deja de funcionar.

Reglas: RN-50, RN-51.

### HU-05 · Cambiar mi contraseña

**Como** usuario **quiero** cambiar mi contraseña cuando quiera **para** mantener mi cuenta segura.

1. Desde el menú de usuario se accede al mismo formulario de HU-04, con las mismas validaciones.
2. Después del cambio el frontend sigue con el token nuevo y los anteriores dejan de funcionar.

Reglas: RN-50, RN-51.

### HU-06 · Aislamiento de datos entre usuarios

**Como** usuario **quiero** que nadie más vea ni modifique mis datos **para** usar la aplicación con información real.

1. Todo endpoint, salvo login y los de administración, exige un token válido; sin token responde 401.
2. Pedir, modificar o eliminar por id un recurso de otro usuario responde 404, igual que si no existiera.
3. Listados y totales incluyen solo datos del usuario del token.
4. Ningún DTO de entrada tiene un campo de usuario; el usuario siempre sale del token.
5. Los tests de servicio verifican, para cada recurso, que un id de otro usuario produce `NOT_FOUND`.

**Cómo se verifica**: los criterios 1 y 4 los cubren tests automáticos genéricos (cualquier endpoint o DTO nuevo queda cubierto solo), más una convención de repositorios (todo método recibe `userId`). Los criterios 2, 3 y 5 se verifican en cada historia que agrega un recurso, con sus tests de servicio; ver `smartcoin-backend/CLAUDE.md`, sección de tests.

Reglas: RN-01.

**Endpoints de la épica**

| Método y ruta | Uso |
|---|---|
| `POST /api/admin/users` | Alta (con `X-Admin-Key`). |
| `POST /api/admin/users/password-reset` | Restablecer contraseña (con `X-Admin-Key`). |
| `POST /api/auth/login` | Iniciar sesión. |
| `POST /api/auth/change-password` | Cambiar contraseña. |
| `GET /api/auth/me` | Datos del usuario actual, incluido `mustChangePassword`. |

---

## Épica 2 · Cuentas y categorías

### HU-07 · Administrar cuentas

**Como** usuario **quiero** cargar mis cuentas **para** saber dónde está mi plata.

1. Alta con nombre, tipo, moneda, fecha de apertura y saldo inicial (puede ser negativo). La fecha de apertura sugerida es el primer día del período inicial; no puede ser anterior a ese día ni futura (400 `VALIDATION_ERROR`).
2. Nombre repetido, sin distinguir mayúsculas: 409 `ACCOUNT_NAME_TAKEN`.
3. Nombre y tipo se editan siempre. Moneda, saldo inicial y fecha de apertura, solo en las condiciones de RN-33; si no, 409 `FIELD_NOT_EDITABLE`.
4. Eliminar una cuenta referenciada: 409 `ACCOUNT_IN_USE`.
5. Pantalla: lista agrupada por moneda (con el saldo de HU-08) y formulario de alta y edición. Los campos no editables se ven deshabilitados con el motivo.

Reglas: RN-33.

**Notas de implementación**

- La respuesta de cada cuenta trae `editability` (`currency`, `initialBalance` y `openingDate`, cada uno con `editable` y `reason`), calculado por el backend; el frontend solo lo muestra. Nombre y tipo siempre se editan.
- La edición (`PUT`) reemplaza todos los campos. Un campo no editable se envía con su valor actual; enviar el mismo valor no es un cambio.
- La fecha de apertura se valida (400 `VALIDATION_ERROR`) solo cuando cambia en una edición. Si no se puede cambiar por la fecha de su primer movimiento o transferencia, responde 409 `FIELD_NOT_EDITABLE`.
- El nombre se guarda sin espacios en los extremos. La unicidad la da la colación de la base (`utf8mb4_0900_as_ci`): no distingue mayúsculas, sí tildes.
- Las referencias (Conceptos, partidas, movimientos, transferencias, cierres) todavía no se pueden crear. Sus verificaciones están probadas con repositorios simulados, y las consultas de existencia solo se validan al arrancar la API. **Pendiente de integración** (cuando haya Docker y Testcontainers): ejecutarlas contra una base real con datos en cada tabla. El saldo actual y los subtotales por moneda los agregó HU-08.

### HU-08 · Ver el saldo actual de cada cuenta

**Como** usuario **quiero** ver cuánto tengo en cada cuenta **para** saber con qué cuento hoy.

1. La lista de cuentas muestra el saldo a hoy de cada una.
2. Muestra un subtotal por moneda y nunca un total que mezcle ARS y USD.
3. Ejemplo: saldo inicial 100.000,00 + ingreso 50.000,00 − gasto 20.000,00 − transferencia saliente 30.000,00 = 100.000,00.
4. Un sueldo cobrado por adelantado suma en el saldo desde la fecha del cobro, aunque su partida sea del mes siguiente.

Reglas: RN-04, RN-35.

**Notas de implementación**

- `GET /api/accounts` devuelve un objeto `{ accounts, subtotals }` y no un array. Cada cuenta trae `currentBalance` (también en `GET /{id}`, `POST` y `PUT`). `subtotals` tiene un elemento `{ currency, balance }` por cada moneda que tiene cuentas, en el orden ARS, USD; nunca hay un total que mezcle monedas. Los calcula el backend.
- `BalanceCalculator` (`account/domain`) es la regla pura de RN-35: recibe el saldo inicial y los totales ya acotados a la fecha de corte, y no redondea. La fecha se aplica en las consultas: `AccountService.totalsUpTo(userId, date)` hace cuatro consultas agrupadas por cuenta (movimientos de ingreso, movimientos de gasto, transferencias entrantes y salientes), sin importar cuántas cuentas haya. Cuenta la fecha del movimiento, no el período de su partida, y la cuenta es la del movimiento. Hoy se pasa la fecha de hoy del `Clock`; el cierre de mes (HU-30 a HU-33) reutilizará `totalsUpTo` con el último día del período. No hay saldo a fecha arbitraria por la API.
- Frontend: la lista muestra "Saldo actual" de cada cuenta y el "Subtotal" de cada grupo con el pipe `money`. Un saldo negativo lleva el signo menos y el color de error.
- **Pendiente de integración**: las cuatro consultas de suma (`MovementRepository.sumByAccountUpTo`, `TransferRepository.sumIncomingByAccountUpTo` y `sumOutgoingByAccountUpTo`) no se pudieron probar contra MySQL (sin Docker ni Testcontainers) ni desde la aplicación, porque todavía no se pueden crear movimientos ni transferencias. Solo se verificó que la API arranca y que Spring Data valida sus JPQL. Los tests del servicio usan repositorios simulados, así que **no prueban el SQL**: el filtro por fecha (`<= hoy`), el signo según el tipo de la partida y el aislamiento por usuario quedan sin verificar con datos reales. Verificarlos con movimientos reales en HU-19 (movimientos) y con transferencias en HU-27, y con una base de pruebas cuando haya Docker.

### HU-09 · Administrar categorías

**Como** usuario **quiero** agrupar Conceptos y partidas en categorías **para** ordenar y filtrar.

1. Alta, cambio de nombre y eliminación.
2. Nombre repetido, sin distinguir mayúsculas: 409 `CATEGORY_NAME_TAKEN`.
3. Eliminar una categoría en uso: 409 `CATEGORY_IN_USE`.
4. La categoría es opcional en Conceptos y partidas puntuales.

Reglas: RN-34.

**Notas de implementación**

- Depende del supuesto S-17 (`docs/decisiones.md`): las categorías son opcionales y no tienen tipo, sirven para ingresos y gastos. Por eso solo guardan `name`.
- `GET /api/categories` devuelve un array de `{ id, name }` ordenado por nombre, sin paginación. `POST` responde 201 con el recurso, `PUT` 200 y `DELETE` 204. El cuerpo de alta y de cambio de nombre es solo `{ name }`.
- El nombre es obligatorio, de hasta 60 caracteres, y se guarda sin espacios en los extremos. Uno vacío, en blanco o demasiado largo responde 400 `VALIDATION_ERROR`. La unicidad la da la colación de la base (`utf8mb4_0900_as_ci`): no distingue mayúsculas, sí tildes. Dos usuarios pueden usar el mismo nombre.
- Al renombrar, la búsqueda de conflicto excluye a la propia categoría: cambiar solo las mayúsculas de su nombre no es un conflicto.
- Eliminar verifica con `BudgetItemRepository.existsByUserIdAndCategoryId` y `BudgetEntryRepository.existsByUserIdAndCategoryId`. Si alguna da verdadero, responde 409 `CATEGORY_IN_USE`; la clave foránea frena además una referencia creada entre la verificación y el borrado.
- **Criterio 4 diferido**: que la categoría sea opcional en Conceptos y partidas puntuales se cumple en HU-10 y HU-16, al crear esos recursos. Hoy lo permite el modelo (`category_id` admite nulo) pero no hay nada que implementar.
- Frontend: la lista y el alta comparten pantalla; el cambio de nombre se hace en la propia fila (Enter guarda, Escape cancela, el foco va al campo) y eliminar pide confirmación con el nombre.
- **Pendiente de integración** (cuando haya Docker y Testcontainers): las dos consultas de existencia no se pudieron probar contra MySQL con Conceptos o partidas reales, porque todavía no se pueden crear. El servicio se prueba con repositorios simulados, que **no prueban el SQL**; solo se verificó que la API arranca y que Spring Data valida sus JPQL. Verificarlas en HU-10 (Conceptos) y HU-16 (partidas puntuales).

**Endpoints de la épica**

| Método y ruta | Uso |
|---|---|
| `GET /api/accounts` | Lista con saldo actual por cuenta y subtotales por moneda. |
| `POST /api/accounts` · `PUT /api/accounts/{id}` · `DELETE /api/accounts/{id}` | Alta, edición y eliminación. |
| `GET /api/categories` · `POST /api/categories` · `PUT /api/categories/{id}` · `DELETE /api/categories/{id}` | Categorías. |

---

## Épica 3 · Conceptos y presupuesto del mes

### HU-10 · Crear un Concepto recurrente

**Como** usuario **quiero** definir una vez algo que se repite **para** no cargarlo cada mes.

1. Datos: nombre, tipo, cuenta por defecto, categoría opcional, periodicidad, día de vencimiento (1 a 31), desfase de mes (0 o −1), período de inicio, período de fin opcional, regla de estimación y monto vigente.
2. Período de inicio fuera del rango entre el primer período abierto y el horizonte: 409 `PERIOD_NOT_AVAILABLE`. Fin anterior al inicio: 400.
3. Al guardar se generan las partidas desde el inicio hasta el horizonte o el fin, según la periodicidad, con el vencimiento de RN-12 y el monto vigente como presupuestado.
4. Los tests cubren todos los ejemplos de la tabla de RN-12 y el seguro semestral de RN-11.
5. Pantalla: el desfase se presenta como una opción clara ("Vence el mes anterior al período, por ejemplo un sueldo que se cobra a fin del mes anterior"), y la regla de estimación con una línea que explique cada opción.

Reglas: RN-10, RN-11, RN-12, RN-13.

**Notas de implementación**

- Depende de los supuestos S-11 (el presupuestado nace igual al monto vigente y corregirlo será HU-13), S-12 (tipo, periodicidad, inicio y fin no se editan: el formulario lo avisa antes de guardar), S-16 (hasta 2 decimales, sin redondeo: un tercer decimal es 400), S-17 (la categoría es opcional y no se compara con el tipo), T-12 y T-13. Agrega S-19, S-20 y S-21, y las decisiones D-24 y T-17.
- `POST /api/budget-items` (`createBudgetItem`) responde 201 con el Concepto, su `currency` (la de la cuenta por defecto) y `generation`: `entryCount`, `firstPeriod`, `lastPeriod` y `firstDueDate`. No hay todavía `GET`, `PUT` ni listado: son HU-13 y HU-14.
- Orden de los errores: formato y rangos de campos (400, Bean Validation); fin anterior al inicio (400, campo `endPeriod`); cuenta por defecto o categoría que no existen para el usuario (400, campos `defaultAccountId` y `categoryId`, D-24); período de inicio fuera del rango (409 `PERIOD_NOT_AVAILABLE`, con el rango válido en el `detail`). Los 400 que decide el servicio salen con `errors` por campo, como los de Bean Validation (`BusinessException.invalidField`).
- El nombre se guarda sin espacios en los extremos y puede repetirse (S-19). El monto vigente puede ser 0 (RN-03).
- **Cuotas**: HU-10 no tiene `installmentsTotal` ni `firstInstallmentNumber`; los agrega HU-11.
- Reglas puras en `budgetitem/domain`: `DueDateCalculator` (RN-12) y `ScheduleCalculator` (RN-11 y RN-13). El calendario devuelve cada período con su índice k desde el inicio, así HU-11 calcula la cuota `f + k` sin tocarlo. El primer período abierto (RN-08) es `PeriodRange.firstOpen`.
- `EntryGenerator.generate(Concepto, horizonte)` es el componente reutilizable de RN-13: calcula el calendario, busca los períodos destino en una consulta, guarda las partidas con un `saveAll` (T-17) y actualiza `generated_until`. No abre transacción: corre en la del caso de uso. Lo llaman el alta y, desde HU-12, `HorizonService.ensureHorizon` por cada Concepto pendiente.
- El alta llama a `HorizonService.ensureHorizon` antes de generar (RN-07): si el mes cambió desde el último inicio de sesión, falta el período del nuevo horizonte. Desde HU-12 esa operación, además de crear los períodos, genera las partidas de los demás Conceptos.
- Las partidas recurrentes se guardan con `name` y `category_id` nulos: muestran los del Concepto.
- Frontend: `/conceptos` tiene el formulario de alta (`BudgetItemForm`) y, después de guardar, un resumen ("Se generaron 25 partidas, de octubre 2026 a octubre 2028. Primer vencimiento: 25/09/2026."). No hay lista de Conceptos (HU-14) ni se ven las partidas (HU-15). Sin cuentas, la pantalla manda a cargar una. El período de inicio sugerido es el mes actual del navegador; el rango válido lo decide el backend. Los períodos usan `<input type="month">`; donde el navegador no lo soporta se escriben como `AAAA-MM`.
- Verificado de punta a punta contra la base, a través de la aplicación y con un usuario de prueba: el alta inserta el Concepto y sus partidas sin violar ninguna restricción; y, pendiente de HU-07 y HU-09, eliminar la cuenta o la categoría que usa un Concepto responde `ACCOUNT_IN_USE` y `CATEGORY_IN_USE` (las consultas de existencia sobre `budget_item`).
- **Pendiente de integración**: las consultas nuevas (`BudgetPeriodRepository.findFirstByUserIdAndStatusOrderByPeriodMonthDesc` y `findByUserIdAndPeriodMonthIn`, y el `saveAll` de partidas) no se pudieron probar contra MySQL con tests (sin Docker ni Testcontainers); los tests del servicio usan repositorios simulados, que **no prueban el SQL**. La aplicación las ejecuta sin error, pero como todavía no hay forma de leer partidas, su contenido no se vio. **Verificar con datos cuando exista la vista del mes (HU-15)**:
  - Un Concepto mensual creado hoy aparece una vez en cada mes, desde su inicio hasta el horizonte, y no aparece antes del inicio ni después del fin.
  - Uno bimestral, trimestral, semestral o anual aparece solo en los meses que le tocan.
  - El vencimiento de cada partida: día 31 en meses de 30, febrero, y con desfase el mes anterior al período (incluido enero → diciembre del año anterior).
  - Presupuestado igual al monto vigente con sus 2 decimales, moneda de la cuenta por defecto, estado Estimada y sin marca de editada.
  - Cada partida recurrente muestra el nombre y la categoría de su Concepto (en la fila están nulos).
  - Las partidas de un usuario no aparecen en los meses de otro (`period_id` del propio usuario).
  - Con un período cerrado (HU-30 a HU-33): un Concepto con inicio en ese período responde `PERIOD_NOT_AVAILABLE`. Hoy no se puede cerrar un mes, así que la consulta del último período cerrado solo corrió sin resultados.
  - Avance del horizonte (HU-12): el primer mes que cambie, el período nuevo aparece con una partida por cada Concepto que le toca, con el monto vigente de ese momento y sin duplicados; `generated_until` queda en el horizonte nuevo (o en el fin del Concepto).
  - Cuotas (HU-11): un plan mensual de 12 cuotas con primera cuota 4 y inicio en el mes actual tiene 9 partidas, con las cuotas 4 a 12 en orden, y ninguna después de la última.
  - Cuotas: cada partida de un Concepto en cuotas muestra "cuota x de n" con el número que le toca; en uno bimestral, trimestral, semestral o anual, la cuota avanza de a una por partida, no por mes.
  - Cuotas: un plan que termina después del horizonte muestra las cuotas 1 a 25 de n (si empieza en el mes actual) y ninguna partida más allá del horizonte; el fin que muestra el Concepto es el calculado.
  - Cuotas: una partida de un Concepto sin cuotas no muestra "cuota x de n".

### HU-11 · Crear un Concepto en cuotas

**Como** usuario **quiero** cargar un plan de cuotas **para** ver "cuota x de n" y que deje de aparecer al terminar.

1. Además de los datos de HU-10: total de cuotas (≥ 1) y número de la primera cuota (entre 1 y el total; por defecto 1). El período de fin no se ingresa: se calcula.
2. Cada partida muestra "cuota x de n".
3. Ejemplo: Heladera, 12 cuotas, primera cuota 4, inicio 2026-10 → cuotas 4 a 12 en 2026-10 a 2027-06.
4. Aunque el horizonte avance, no se generan partidas después de la última cuota.

Reglas: RN-13, RN-14.

**Notas de implementación**

- Depende de S-12 (las cuotas no se editan: el formulario lo avisa antes de guardar) y de los de HU-10: S-19 (se puede cargar dos veces la misma "Heladera"), S-20 (con desfase −1 el vencimiento de la primera cuota puede caer en un mes cerrado) y S-21, más D-18 (las partidas se generan hasta la última cuota), D-24 y T-17 (un `saveAll`, con 25 inserts como máximo por Concepto, también con cuotas). Agrega D-25 (máximo de 360 cuotas), D-26 (errores por campo) y S-22 (un plan cortado por el horizonte queda incompleto hasta HU-12).
- `POST /api/budget-items` acepta `installmentsTotal` y `firstInstallmentNumber`, opcionales. La respuesta suma `installmentsTotal`, `firstInstallmentNumber` (nulos si no es en cuotas), `endPeriod` (el calculado) y, dentro de `generation`, `firstInstallment` y `lastInstallment`: el número de cuota de la primera y de la última partida generada. Si `lastInstallment` es igual a `installmentsTotal`, el plan se generó completo.
- Errores (400 `VALIDATION_ERROR`, D-26): formato en Bean Validation (`installmentsTotal` entre 1 y 360, `firstInstallmentNumber` ≥ 1); después, en el servicio y antes del chequeo de fin anterior al inicio: primera cuota sin total (campo `installmentsTotal`), primera cuota mayor que el total (`firstInstallmentNumber`), cuotas con fin informado (`endPeriod`). Con el total y sin la primera cuota, queda en 1.
- Regla pura `InstallmentPlan` en `budgetitem/domain` (RN-14): `installmentNumber(k)` = f + k y `endPeriod(inicio, periodicidad)` = inicio + (n − f) × paso. `ScheduleCalculator` no cambió: ya devolvía el índice k de cada período. `EntryGenerator` pone `installment_number` desde ese índice, así que un `generated_until` avanzado continúa la numeración sin repetir ni saltear. Sin cuotas, todo queda como en HU-10.
- Con cuotas, `end_period` se guarda calculado y `generated_until` queda en el fin o en el horizonte, el que llegue primero. Por eso, cuando el horizonte avance, HU-12 no generará nada después de la última cuota.
- Frontend: la casilla "Es en cuotas" del formulario muestra el total y la primera cuota (1 por defecto, o vacía, que el backend completa con 1), oculta el período de fin y explica que el fin se calcula y para qué sirve la primera cuota. Solo valida el formato (enteros ≥ 1). El resumen agrega "Son las cuotas 4 a 12 de 12." (o "Es la cuota 12 de 12."); si el plan termina después del horizonte, agrega cuándo termina y que las demás se generan a medida que avance el horizonte.
- **Criterios que se completan en otra historia**:
  - Criterio 2, "cada partida muestra cuota x de n": se ve en la vista del mes (HU-15). Hoy el backend guarda `installment_number` y la respuesta de partidas va a traer el total de cuotas del Concepto; todavía no hay dónde mostrarlo.
  - Criterio 4, "aunque el horizonte avance no se generan partidas después de la última cuota": HU-12 lo garantiza con tests (un plan cortado por el horizonte continúa con su número y no pasa de la última cuota). La verificación con datos reales queda en la lista pendiente de HU-12.
  - La lista de verificación con datos reales para HU-15 (en las notas de HU-10) suma los puntos de cuotas.
- **Observación fuera de esta historia**: el cuerpo acepta números decimales en los campos enteros y los trunca (`"installmentsTotal": 12.5` se guarda como 12, como ya pasa con `dueDay`). Es la configuración por defecto de Jackson. Si se quiere rechazarlos hay que desactivar `ACCEPT_FLOAT_AS_INT` para toda la API, así que queda para decidir aparte.
- Verificado de punta a punta con un usuario de prueba, a través de la aplicación y en el navegador: la Heladera (12 cuotas, primera 4) genera 9 partidas, de octubre 2026 a junio 2027, y el resumen dice "Son las cuotas 4 a 12 de 12."; con la primera cuota en 13 se ve el error del backend y se conserva lo cargado; un plan de 60 cuotas genera 25 y avisa cuándo termina. Como con HU-10, las partidas y su `installment_number` no se pueden leer todavía: eso se verifica en HU-15.

### HU-12 · Mantener el horizonte de 24 meses

**Como** usuario **quiero** tener siempre 24 meses hacia adelante **para** planificar sin cargar nada a mano.

1. Al iniciar sesión y al crear o editar un Concepto se asegura el horizonte.
2. Ejemplo: con horizonte en 2028-10, al iniciar sesión en noviembre de 2026 se crea el período 2028-11 y las partidas que correspondan, con el monto vigente de cada Concepto.
3. Una partida eliminada con "Solo este mes" no vuelve a aparecer.
4. Ejecutarlo dos veces seguidas no crea nada nuevo.

Reglas: RN-06, RN-07, RN-13.

**Notas de implementación**

- Depende de D-09 (partidas materializadas; `generated_until` evita que una partida eliminada reaparezca), S-22 (resuelta: ver `decisiones.md`) y T-17 (un `saveAll` por Concepto). No agrega supuestos nuevos.
- Sin cambios de API ni de pantalla: `docs/openapi.json` queda igual al regenerarlo.
- `HorizonService.ensureHorizon` crea los períodos que faltan (sin llamar a `saveAll` si no falta ninguno) y después, con el horizonte calculado con el `Clock`, genera con `EntryGenerator.generate` los Conceptos que devuelve `BudgetItemRepository.findPendingGeneration(userId, horizonte)`. Todo en la transacción del caso de uso. Lo invocan el alta de usuario, el inicio de sesión y el alta de Conceptos.
- La consulta trae solo los Conceptos del usuario con `generated_until` nulo o anterior al menor entre el horizonte y su fin. Uno terminado o al día no se trae ni cuesta nada: importa porque corre en cada inicio de sesión. Un Concepto anual o semestral se trae una vez por mes de avance del horizonte aunque no le toque partida: ahí se anota `generated_until` y deja de traerse.
- `generated_until` se actualiza sobre la entidad gestionada (sin `save` explícito). La segunda ejecución seguida no trae ningún Concepto y no guarda períodos ni partidas.
- Varios meses de ausencia: el horizonte se calcula con el mes actual, así que se crean todos los períodos que faltan y cada Concepto genera de una vez todo lo que le corresponde según su periodicidad, con el monto vigente de ese momento.
- Alta de Conceptos: sigue llamando a `ensureHorizon` antes de guardar el Concepto nuevo, que por eso no está en la consulta; y aunque estuviera, `generated_until` impediría duplicar. Hay un test del orden en `BudgetItemServiceTest`.
- No se implementa el disparo al editar un Concepto (HU-13) ni la eliminación con "Solo este mes" (HU-18).
- **Riesgo conocido, sin resolver**: dos inicios de sesión simultáneos del mismo usuario en un mes nuevo pueden intentar crear los mismos períodos y partidas. `UNIQUE (user_id, period_month)` y `UNIQUE (budget_item_id, period_id)` evitan los duplicados, pero una de las transacciones fallaría y ese inicio de sesión daría error. Las reglas no lo definen; se acepta porque la aplicación se usa de a un usuario por vez.
- Tests (`HorizonServiceTest`, `HorizonGenerationTest`), con repositorios simulados y `Clock` fijo: el ejemplo de la historia, periodicidades, Concepto terminado, cuotas cortadas por el horizonte (con primera cuota distinta de 1 y trimestral), salto de varios meses con cruce de año, segunda ejecución sin guardados, período ya procesado sin partida, Conceptos de otro usuario.
- **Pendiente de integración**: `findPendingGeneration` no se pudo probar contra MySQL (sin Docker ni Testcontainers); el repositorio simulado de los tests aplica el mismo criterio pero **no prueba el JPQL ni la comparación de `CHAR(7)`**. La aplicación lo ejecuta sin error en el inicio de sesión.
- **Criterios que se completan en otra historia**:
  - Criterio 3, "una partida eliminada con Solo este mes no vuelve a aparecer": de punta a punta en HU-18. Hoy lo cubre el test de un período ya procesado sin partida.
  - Criterio 1, "al editar un Concepto se asegura el horizonte": el disparo llega con HU-13.
  - El avance del horizonte con datos reales no se puede probar porque no se puede adelantar el reloj de la aplicación. **Verificar la primera vez que cambie el mes, mirando en la vista del mes (HU-15)**: que aparece el período nuevo con sus partidas, una por cada Concepto que le toca, con el monto vigente de ese momento, sin duplicados, y que un plan de cuotas cortado por el horizonte sigue con el número que corresponde. Se suma a la lista de verificación de HU-15 de las notas de HU-10.

### HU-13 · Editar un Concepto

**Como** usuario **quiero** corregir un Concepto **para** que sus partidas futuras reflejen el cambio.

1. Se editan nombre, categoría, cuenta por defecto (solo otra de la misma moneda; si no, `CURRENCY_MISMATCH`), día de vencimiento, desfase, regla de estimación y monto vigente, con los efectos de RN-15.
2. Tipo, periodicidad, período de inicio, período de fin y cuotas no se editan: 409 `FIELD_NOT_EDITABLE`.
3. Antes de guardar un cambio de monto vigente, la pantalla avisa que reemplaza las partidas pendientes no editadas y que las editadas no cambian.
4. Nunca cambian partidas consolidadas ni de períodos cerrados.
5. Para dar de baja un Concepto se elimina su partida desde el mes elegido (HU-18); la pantalla del Concepto lo explica.

Reglas: RN-15.

### HU-14 · Listar Conceptos

**Como** usuario **quiero** ver todos mis Conceptos **para** revisar qué tengo configurado.

1. Lista con nombre, tipo, cuenta y moneda, categoría, periodicidad, vencimiento (día y desfase), regla de estimación, monto vigente y estado: activo, finalizado (su fin es anterior al período actual) o en cuotas (cuántas quedan).
2. Filtros por tipo y categoría.

Reglas: RN-10, RN-14.

### HU-15 · Ver el presupuesto de un mes

**Como** usuario **quiero** ver un mes completo **para** saber qué cobro, qué pago y cómo cierra.

1. Abre en el período actual. Tiene botones de mes anterior y siguiente, selector de mes y "Hoy". No permite ir antes del período inicial ni después del horizonte.
2. Dos secciones, Ingresos y Gastos, con vencimiento, nombre, categoría, cuenta, cuota x de n, presupuestado, real, pendiente y estado. Las partidas editadas y las vencidas tienen una marca visible.
3. Orden por vencimiento y luego por nombre.
4. Totales por moneda según RN-44.
5. Un período cerrado se ve igual pero sin acciones, con la indicación "Cerrado".
6. Ejemplo para 2026-11 en ARS:

   | Partida | Presupuestado | Real | Pendiente | Estado |
   |---|---|---|---|---|
   | Sueldo A (ingreso) | 1.200.000,00 | 1.200.000,00 | 0,00 | Consolidada |
   | Sueldo B (ingreso) | 650.000,00 | 0,00 | 650.000,00 | Estimada |
   | Alquiler (gasto) | 450.000,00 | 450.000,00 | 0,00 | Consolidada |
   | Luz (gasto) | 45.000,00 | 20.000,00 | 25.000,00 | Parcial |
   | Resumen Visa (gasto) | 240.000,00 | 0,00 | 240.000,00 | Estimada |

   Ingresos: presupuestado 1.850.000,00, real 1.200.000,00, pendiente 650.000,00, estimado 1.850.000,00. Gastos: presupuestado 735.000,00, real 470.000,00, pendiente 265.000,00, estimado 735.000,00. Resultado: 1.115.000,00.

Reglas: RN-16, RN-17, RN-20, RN-44.

**Endpoints de la épica**

| Método y ruta | Uso |
|---|---|
| `GET /api/budget-items` | Lista de Conceptos. |
| `POST /api/budget-items` | Crear (genera partidas). |
| `GET /api/budget-items/{id}` · `PUT /api/budget-items/{id}` | Ver y editar. |
| `GET /api/periods?from=YYYY-MM&to=YYYY-MM` | Períodos con estado y totales por moneda. |
| `GET /api/periods/{period}` | Vista del mes: partidas con valores derivados y totales. |

---

## Épica 4 · Partidas

### HU-16 · Agregar una partida puntual

**Como** usuario **quiero** cargar algo que pasa una sola vez **para** que figure solo en ese mes.

1. En un período abierto: nombre, tipo, cuenta, vencimiento, presupuestado y categoría opcional.
2. Aparece solo en ese período y no se copia a otros.
3. El vencimiento debe estar entre el primer día del mes anterior al período y el último día del período; si no, 400.
4. Se edita (nombre, categoría, cuenta, vencimiento, presupuestado) mientras esté pendiente. Si tiene movimientos, la cuenta solo cambia por otra de la misma moneda.

Reglas: RN-18, RN-19.

### HU-17 · Editar el monto de una partida

**Como** usuario **quiero** ajustar el monto de un mes puntual **para** reflejar lo que ya sé, sin tocar los demás meses.

1. En una partida pendiente de un período abierto se cambia el presupuestado (≥ 0) y queda marcada como editada.
2. No cambia ninguna otra partida ni el monto vigente del Concepto.
3. Ejemplo: Resumen Visa de noviembre pasa de 180.000,00 a 240.000,00; diciembre sigue en 180.000,00.
4. Partida consolidada: 409 `ENTRY_NOT_PENDING`. Período cerrado: 409 `PERIOD_CLOSED`.

Reglas: RN-18.

### HU-18 · Eliminar una partida

**Como** usuario **quiero** eliminar una partida **para** quitar algo que no va a pasar, en un mes o de ahí en adelante.

1. Partida sin Concepto: pide una confirmación simple y se elimina si está pendiente y sin movimientos.
2. Partida de un Concepto: el diálogo pregunta "Solo este mes" o "Este mes y los siguientes".
3. Solo este mes: se elimina esa partida, las demás siguen y no reaparece.
4. Este mes y los siguientes: se eliminan todas las partidas del Concepto desde ese período, el Concepto termina en el mes anterior y no genera más. Si se queda sin partidas, se elimina el Concepto.
5. Si alguna partida afectada tiene movimientos o está consolidada: 409 `ENTRY_HAS_MOVEMENTS` o `ENTRY_NOT_PENDING`, con la lista de partidas que lo impiden, y no se elimina nada.
6. Nunca se tocan partidas consolidadas ni de períodos cerrados.

Reglas: RN-13, RN-30, RN-31, RN-32.

**Endpoints de la épica**

| Método y ruta | Uso |
|---|---|
| `POST /api/periods/{period}/entries` | Crear partida puntual. |
| `PATCH /api/entries/{id}` | Editar partida. |
| `DELETE /api/entries/{id}?scope=ONLY_THIS\|THIS_AND_FUTURE` | Eliminar (el alcance solo aplica a recurrentes). |

---

## Épica 5 · Movimientos

### HU-19 · Registrar cobros y pagos

**Como** usuario **quiero** registrar lo que efectivamente cobro y pago, aunque sea en partes, **para** saber cuánto falta.

1. Desde una partida pendiente: fecha (hoy por defecto), monto > 0, cuenta (la de la partida por defecto, o cualquier otra de la misma moneda) y nota opcional.
2. Con el primer movimiento la partida pasa de Estimada a Parcial. No se consolida sola, aunque el real alcance al presupuestado.
3. Con varios movimientos, real = suma y pendiente = presupuestado − real, nunca negativo.
4. Ejemplo: Expensas de 120.000,00 pagadas con 70.000,00 el 05/11 y 50.000,00 el 12/11 → real 120.000,00, pendiente 0,00, sigue Parcial hasta consolidar.
5. Errores según RN-21: `DATE_OUT_OF_RANGE`, `CURRENCY_MISMATCH`, `PERIOD_CLOSED`, `ENTRY_NOT_PENDING`.
6. Cuando el pendiente llega a 0, la pantalla sugiere consolidar.

Reglas: RN-17, RN-21, RN-23.

### HU-20 · Registrar cobros anticipados

**Como** usuario **quiero** registrar un cobro antes de que empiece su mes **para** reflejar que los sueldos de diciembre se cobran a fines de noviembre.

1. La fecha puede ser hasta 10 días antes del primer día del período de la partida.
2. Ejemplo: sueldo de diciembre (2026-12). El 21/11 es válido (límite exacto), el 25/11 y el 30/11 también; el 20/11 responde 409 `DATE_OUT_OF_RANGE`.
3. El movimiento suma en el saldo de la cuenta por su fecha (noviembre), aunque la partida sea de diciembre.
4. Si noviembre ya está cerrado, un movimiento con fecha en noviembre se rechaza con 409 `PERIOD_CLOSED`, aunque esté dentro de la ventana.

Reglas: RN-21, RN-35, RN-41.

### HU-21 · Corregir o eliminar un movimiento

**Como** usuario **quiero** corregir un movimiento mal cargado **para** que los números sean los reales.

1. Se editan fecha, monto, cuenta y nota, o se elimina el movimiento.
2. Solo si la partida está pendiente y el mes de la fecha actual del movimiento está abierto; los valores nuevos cumplen RN-21.
3. Al eliminar el último movimiento, la partida vuelve a Estimada.

Reglas: RN-22.

### HU-22 · Pago rápido

**Como** usuario **quiero** pagar o cobrar todo lo pendiente con un clic **para** no cargar el movimiento y después consolidar.

1. La acción "Pagar todo" o "Cobrar todo" aparece en partidas pendientes con pendiente mayor que 0.
2. Crea un movimiento por el pendiente, con fecha de hoy (editable en el diálogo) y la cuenta de la partida, y consolida. Si algo falla, no queda nada registrado.
3. Si la consolidación encuentra partidas editadas, se pide la elección de HU-25 antes de confirmar.
4. Pendiente 0: 409 `NOTHING_PENDING` (corresponde consolidar).

Reglas: RN-24.

**Endpoints de la épica**

| Método y ruta | Uso |
|---|---|
| `GET /api/entries/{id}/movements` · `POST /api/entries/{id}/movements` | Ver y registrar movimientos de una partida. |
| `PUT /api/movements/{id}` · `DELETE /api/movements/{id}` | Corregir o eliminar. |
| `POST /api/entries/{id}/quick-settle` | Pago rápido (fecha, cuenta y política opcionales). |

---

## Épica 6 · Consolidación

### HU-23 · Consolidar una partida

**Como** usuario **quiero** dar por terminada una partida con lo que realmente pagué o cobré **para** que los meses siguientes se ajusten.

1. Se consolida una partida pendiente con al menos un movimiento. Sin movimientos: 409 `CONSOLIDATION_REQUIRES_MOVEMENT`.
2. Antes de confirmar se muestra la vista previa: monto real, nueva estimación y partidas futuras que cambian.
3. Al confirmar: queda Consolidada con su monto real, el monto vigente del Concepto pasa a la nueva estimación y las partidas futuras pendientes no editadas toman ese valor.
4. Ejemplo con último valor: el del sueldo de RN-26.
5. Las partidas sin Concepto se consolidan sin propagar.
6. En un Concepto en cuotas, la propagación llega hasta la última cuota.

Reglas: RN-25, RN-26, RN-27, RN-28.

### HU-24 · Estimar con el promedio de los últimos 3

**Como** usuario **quiero** que los servicios que varían por consumo se estimen con un promedio **para** no arrastrar un mes atípico.

1. En un Concepto con "Promedio de los últimos 3", la estimación es el promedio de hasta 3 consolidaciones normales más recientes por período, redondeado a 2 decimales.
2. Ejemplo de la luz en RN-26: 41.753,33.
3. Con 1 o 2 consolidaciones, promedia las que haya.
4. Las consolidaciones hechas durante el cierre del mes no entran en el promedio.
5. El resultado es el mismo sin importar el orden en que se consolidaron los meses.

Reglas: RN-26.

### HU-25 · Decidir sobre partidas editadas al consolidar

**Como** usuario **quiero** elegir si la consolidación pisa los meses que ajusté a mano **para** no perder lo que ya sé.

1. Si entre las partidas a propagar hay editadas, la vista previa las lista con su monto actual y el nuevo.
2. Se elige "Respetar" o "Pisar", una sola elección para todas. Sin elección: 409 `MANUAL_POLICY_REQUIRED` y no cambia nada.
3. Respetar: las editadas no cambian y siguen editadas. Pisar: toman la estimación y dejan de estar editadas.
4. Ejemplo del Resumen Visa en RN-26.
5. Si no hay partidas editadas en el destino, no se pregunta nada.

Reglas: RN-26, RN-27.

### HU-26 · Desconsolidar una partida

**Como** usuario **quiero** deshacer una consolidación **para** corregir un movimiento antes de cerrar el mes.

1. Una partida consolidada de un período abierto vuelve a Parcial.
2. No se revierte lo propagado ni el monto vigente; se recalcula en la próxima consolidación del Concepto.
3. Período cerrado: 409 `PERIOD_CLOSED`.

Reglas: RN-29.

**Endpoints de la épica**

| Método y ruta | Uso |
|---|---|
| `GET /api/entries/{id}/consolidation-preview` | Vista previa. |
| `POST /api/entries/{id}/consolidate` | Consolidar (con `manualEntriesPolicy` opcional). |
| `POST /api/entries/{id}/unconsolidate` | Desconsolidar. |

---

## Épica 7 · Transferencias

### HU-27 · Transferir entre cuentas de la misma moneda

**Como** usuario **quiero** registrar cuando paso plata entre mis cuentas **para** que los saldos coincidan con la realidad.

1. Datos: cuenta de origen, cuenta de destino, fecha (hoy por defecto), monto y nota opcional. En la misma moneda, la pantalla pide un solo monto y la API recibe los dos iguales.
2. Origen igual a destino: 400 `VALIDATION_ERROR`. Montos distintos en la misma moneda: 409 `TRANSFER_AMOUNTS_MISMATCH`.
3. Resta en el origen, suma en el destino y no aparece entre los ingresos ni los gastos del mes.
4. Validaciones de fecha de RN-36.
5. Ejemplo: extraer 50.000,00 del banco al efectivo deja igual el total en ARS.

Reglas: RN-36.

### HU-28 · Comprar y vender dólares

**Como** usuario **quiero** registrar compras y ventas de dólares **para** saber a qué tipo de cambio operé.

1. Si las monedas son distintas, se ingresan los dos montos: el de pesos y el de dólares.
2. La pantalla muestra el tipo de cambio que devuelve la API.
3. Ejemplos de RN-37: compra a 1.250,00 y venta a 1.220,00.
4. La lista de transferencias muestra el tipo de cambio en las de distinta moneda.
5. No se guarda ninguna cotización ni se convierten totales.

Reglas: RN-37.

### HU-29 · Corregir o eliminar una transferencia

**Como** usuario **quiero** corregir una transferencia mal cargada.

1. Se edita o elimina si el mes de su fecha actual está abierto; los valores nuevos cumplen RN-36.
2. Mes cerrado: 409 `PERIOD_CLOSED`.

Reglas: RN-36.

**Endpoints de la épica**

| Método y ruta | Uso |
|---|---|
| `GET /api/transfers?from=&to=` | Lista con tipo de cambio en las de distinta moneda. |
| `POST /api/transfers` · `PUT /api/transfers/{id}` · `DELETE /api/transfers/{id}` | Alta, edición y eliminación. |

---

## Épica 8 · Cierre de mes

El cierre se implementa como un asistente de tres pasos en el frontend (pendientes, saldos, confirmación) sobre dos endpoints: la simulación y el cierre.

### HU-30 · Ver qué falta para cerrar el mes

**Como** usuario **quiero** ver qué falta antes de cerrar **para** resolverlo.

1. Desde la vista del mes, "Cerrar mes" abre el asistente.
2. Muestra si se puede cerrar y, si no, el motivo (mes anterior abierto o mes no terminado).
3. Lista las partidas pendientes, con real y pendiente, y las cuentas a conciliar, con su saldo calculado al último día del mes.
4. No modifica nada.

Reglas: RN-38, RN-43.

### HU-31 · Resolver partidas pendientes al cerrar

**Como** usuario **quiero** decidir qué pasa con lo que quedó pendiente **para** cerrar el mes sin perder deudas ni cobros.

1. Para cada partida pendiente se elige "Postergar saldo" o "Cerrar con lo registrado". Falta alguna: 409 `UNRESOLVED_PENDING_ENTRIES`.
2. Postergar saldo: la partida se consolida con su real y, si quedaba pendiente, aparece "Saldo pendiente: <nombre>" en el mes siguiente, con vencimiento un mes después.
3. Cerrar con lo registrado: la partida se consolida con su real, que puede ser 0.
4. Ninguna de las dos propaga ni cambia estimaciones futuras.
5. Ejemplo de la luz en RN-40.

Reglas: RN-39, RN-40.

### HU-32 · Conciliar saldos y registrar diferencias

**Como** usuario **quiero** comparar los saldos de la aplicación con los reales **para** detectar lo que no registré.

1. Para cada cuenta abierta al último día del mes se ingresa el saldo real. Falta alguno: 409 `MISSING_REAL_BALANCE`.
2. El saldo calculado incluye los movimientos por fecha, también los cobros anticipados de partidas del mes siguiente.
3. El asistente muestra la diferencia de cada cuenta según la simulación del backend, antes de confirmar.
4. Cada diferencia distinta de 0 crea en el mes siguiente una partida "Diferencia de cierre": ingreso si es positiva, gasto si es negativa, con vencimiento el día 1.
5. Ejemplo del banco en RN-40.

Reglas: RN-35, RN-40, RN-41, RN-42, RN-43.

### HU-33 · Período cerrado inmutable

**Como** usuario **quiero** que un mes cerrado no cambie más **para** confiar en lo que ya cerré.

1. Al confirmar, el período queda Cerrado con su fecha de cierre, y todo el cierre ocurre en una sola transacción: si algo falla, no cambia nada.
2. Toda creación, edición o eliminación de partidas del período, o de movimientos y transferencias con fecha en ese mes, responde 409 `PERIOD_CLOSED`.
3. No existe ninguna forma de reabrir un período.
4. Mes anterior abierto: 409 `PREVIOUS_PERIOD_OPEN`. Antes del último día del mes: 409 `PERIOD_NOT_FINISHED`.

Reglas: RN-09, RN-38, RN-40.

**Endpoints de la épica**

| Método y ruta | Uso |
|---|---|
| `POST /api/periods/{period}/closing-preview` | Simulación; resoluciones y saldos reales opcionales. |
| `POST /api/periods/{period}/close` | Cierre con resoluciones y saldos reales. |

---

## Épica 9 · Saldos y proyección (propuestas, S-18)

Confirmar con Mario antes de implementar.

### HU-34 · Flujo de caja por fecha

**Como** usuario **quiero** ver cuándo entra y sale la plata **para** anticipar faltantes dentro del mes.

1. Rango de fechas (por defecto, el mes actual) y moneda.
2. Lista cronológica según RN-45, distinguiendo lo real de lo previsto.
3. Saldo acumulado en cada fila.
4. Ejemplo: los sueldos de diciembre aparecen el 25/11 y el 30/11.

Reglas: RN-45.

### HU-35 · Proyección de saldos por cuenta

**Como** usuario **quiero** saber cuánto voy a tener en cada cuenta a fin de cada mes **para** planificar a dos años.

1. Para cada mes desde el actual hasta el horizonte, el saldo proyectado al último día de cada cuenta, según RN-46.
2. Subtotales por moneda.

Reglas: RN-46.

### HU-36 · Vista de varios meses

**Como** usuario **quiero** ver mis Conceptos mes a mes en una grilla **para** planificar como en una planilla.

1. Filas: Conceptos y, agrupadas, las partidas sin Concepto. Columnas: meses (12 por defecto, desde el actual). Celdas: estimado de cada partida, con marca de editada y de consolidada.
2. Clic en una celda abre ese mes.
3. Totales por mes y moneda.

Reglas: RN-17, RN-44.

**Endpoints de la épica**

| Método y ruta | Uso |
|---|---|
| `GET /api/cashflow?from=&to=&currency=` | Flujo de caja. |
| `GET /api/projection?until=YYYY-MM` | Proyección de saldos. |
| `GET /api/budget-grid?from=YYYY-MM&to=YYYY-MM` | Vista de varios meses. |
