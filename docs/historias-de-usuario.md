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
| 5 | HU-19 a HU-21 | Registrar cobros y pagos, parciales y anticipados. |
| 6 | HU-23 a HU-25, HU-22 y HU-26 | Consolidar y ver cómo se ajustan los meses siguientes, y pagar o cobrar todo con un clic. |
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
- *Verificado con datos en HU-19* lo de los movimientos (con un usuario de prueba, por la API): una cuenta que solo se usa como origen de un movimiento responde 409 `ACCOUNT_IN_USE`, y una sin ningún uso se elimina; con movimientos, mover la fecha de apertura al día siguiente de su primer movimiento responde 409 `FIELD_NOT_EDITABLE`, al mismo día de su primer movimiento se acepta (200) y cambiar la moneda responde 409 `FIELD_NOT_EDITABLE`; un movimiento anterior a la nueva apertura responde `DATE_OUT_OF_RANGE`. `MovementRepository.existsByUserIdAndAccountId` y `findFirstMovementDate` devolvieron datos. *Sigue pendiente* lo de transferencias (HU-27) y cierres (HU-32).

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
- *Verificado con datos en HU-19* (usuario de prueba, por la API, 81 comprobaciones junto con las de abajo): el saldo de cada cuenta suma los cobros y resta los pagos según el tipo de la partida (`sumByAccountUpTo` devuelve filas con signo correcto); cuenta la cuenta del movimiento y no la de la partida (un pago desde otra cuenta de la misma moneda resta de esa); un cobro con fecha del mes anterior al de su partida (dentro de la ventana) suma en el saldo (criterio 4); los subtotales ARS y USD no se mezclan; un movimiento con fecha futura se rechaza y el saldo no cambia; y los saldos de otro usuario no se mueven. **Límite de lo que se puede verificar**: con S-09 no se puede crear un movimiento con fecha posterior a hoy, y la API no expone el saldo a una fecha arbitraria, así que que el filtro `<= fecha` **excluya** movimientos posteriores no se puede producir a través de la aplicación. Queda pendiente para HU-30 a HU-34 (que consultan saldos a otra fecha) o para cuando haya Docker. El ejemplo del criterio 3 incluye una transferencia saliente: sigue pendiente para HU-27.

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
- **Criterio 4 diferido**: que la categoría sea opcional en Conceptos y partidas puntuales se cumple en HU-10 y HU-16, al crear esos recursos. *Cumplido en HU-16*: el alta y la edición de una partida puntual aceptan una categoría o ninguna.
- Frontend: la lista y el alta comparten pantalla; el cambio de nombre se hace en la propia fila (Enter guarda, Escape cancela, el foco va al campo) y eliminar pide confirmación con el nombre.
- **Pendiente de integración** (cuando haya Docker y Testcontainers): las dos consultas de existencia no se pudieron probar contra MySQL con Conceptos o partidas reales, porque todavía no se pueden crear. El servicio se prueba con repositorios simulados, que **no prueban el SQL**; solo se verificó que la API arranca y que Spring Data valida sus JPQL. Verificarlas en HU-10 (Conceptos) y HU-16 (partidas puntuales). *Verificado con datos en HU-16* para partidas: una categoría y una cuenta usadas solo por una partida puntual responden 409 `CATEGORY_IN_USE` y `ACCOUNT_IN_USE`.

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
- Frontend: el formulario de alta (`BudgetItemForm`) está en `/conceptos/nuevo` desde HU-14 (antes estaba en `/conceptos`) y, después de guardar, un resumen ("Se generaron 25 partidas, de octubre 2026 a octubre 2028. Primer vencimiento: 25/09/2026."). La lista de Conceptos es HU-14; las partidas se ven en HU-15. Sin cuentas, la pantalla manda a cargar una. El período de inicio sugerido es el mes actual del navegador; el rango válido lo decide el backend. Los períodos usan `<input type="month">`; donde el navegador no lo soporta se escriben como `AAAA-MM`.
- Verificado de punta a punta contra la base, a través de la aplicación y con un usuario de prueba: el alta inserta el Concepto y sus partidas sin violar ninguna restricción; y, pendiente de HU-07 y HU-09, eliminar la cuenta o la categoría que usa un Concepto responde `ACCOUNT_IN_USE` y `CATEGORY_IN_USE` (las consultas de existencia sobre `budget_item`).
- **Pendiente de integración**: las consultas nuevas (`BudgetPeriodRepository.findFirstByUserIdAndStatusOrderByPeriodMonthDesc` y `findByUserIdAndPeriodMonthIn`, y el `saveAll` de partidas) no se pudieron probar contra MySQL con tests (sin Docker ni Testcontainers); los tests del servicio usan repositorios simulados, que **no prueban el SQL**. La aplicación las ejecuta sin error, pero como todavía no hay forma de leer partidas, su contenido no se vio. **Verificar con datos cuando exista la vista del mes (HU-15)** (hecho en HU-15 con dos usuarios de prueba, a través de la aplicación; cada punto dice qué se vio y qué sigue pendiente):
  - Un Concepto mensual creado hoy aparece una vez en cada mes, desde su inicio hasta el horizonte, y no aparece antes del inicio ni después del fin.
    - *Verificado con datos en HU-15:* 25 partidas del mes actual al horizonte y ninguna antes; con inicio y fin, solo los 5 meses entre los dos; con inicio en el período inicial, también en los meses pasados sin cerrar (27 partidas).
  - Uno bimestral, trimestral, semestral o anual aparece solo en los meses que le tocan.
    - *Verificado con datos en HU-15:* los cuatro, con inicio en distintos meses, mes por mes hasta el horizonte.
  - El vencimiento de cada partida: día 31 en meses de 30, febrero, y con desfase el mes anterior al período (incluido enero → diciembre del año anterior).
    - *Verificado con datos en HU-15:* día 31 → 30/11, 28/02/2027 y 29/02/2028; día 29 → 28/02/2027 y 29/02/2028; desfase −1 con día 25 → enero de 2027 y de 2028 vencen el 25/12 del año anterior; desfase −1 con día 30 → marzo vence el 28/02/2027 y el 29/02/2028. La partida del mes actual con desfase vence el mes pasado y figura vencida (S-20).
  - Presupuestado igual al monto vigente con sus 2 decimales, moneda de la cuenta por defecto, estado Estimada y sin marca de editada.
    - *Verificado con datos en HU-15:* en las 260 partidas del usuario de prueba, con montos como `85000.50` y `1200000.00` en el JSON, real 0 y pendiente igual al presupuestado.
  - Cada partida recurrente muestra el nombre y la categoría de su Concepto (en la fila están nulos).
    - *Verificado con datos en HU-15:* con categoría y sin categoría.
  - Las partidas de un usuario no aparecen en los meses de otro (`period_id` del propio usuario).
    - *Verificado con datos en HU-15:* un segundo usuario de prueba, con los mismos 27 meses, los ve vacíos y sin totales.
  - Con un período cerrado (HU-30 a HU-33): un Concepto con inicio en ese período responde `PERIOD_NOT_AVAILABLE`. Hoy no se puede cerrar un mes, así que la consulta del último período cerrado solo corrió sin resultados.
    - *Sigue pendiente:* se verifica en HU-33.
  - Avance del horizonte (HU-12): el primer mes que cambie, el período nuevo aparece con una partida por cada Concepto que le toca, con el monto vigente de ese momento y sin duplicados; `generated_until` queda en el horizonte nuevo (o en el fin del Concepto).
    - *Sigue pendiente:* se verifica en el primer cambio de mes, ahora mirando la vista del mes.
  - Cuotas (HU-11): un plan mensual de 12 cuotas con primera cuota 4 y inicio en el mes actual tiene 9 partidas, con las cuotas 4 a 12 en orden, y ninguna después de la última.
    - *Verificado con datos en HU-15:* por la API y en el e2e (la Heladera muestra «Cuota 12 de 12» en su último mes y no aparece en el siguiente).
  - Cuotas: cada partida de un Concepto en cuotas muestra "cuota x de n" con el número que le toca; en uno bimestral, trimestral, semestral o anual, la cuota avanza de a una por partida, no por mes.
    - *Verificado con datos en HU-15:* bimestral con primera cuota 3 (cuotas 3 a 6 cada dos meses), trimestral (1 a 9 hasta el horizonte), anual con primera cuota 3 (3, 4 y 5) y un plan de una sola cuota. No se probó un plan semestral: usa el mismo cálculo.
  - Cuotas: un plan que termina después del horizonte muestra las cuotas 1 a 25 de n (si empieza en el mes actual) y ninguna partida más allá del horizonte; el fin que muestra el Concepto es el calculado.
    - *Verificado con datos en HU-14 (e2e, un plan de 60 cuotas que empieza este mes):* el fin que muestra la lista es el calculado, `inicio + 59 meses`, y la cuota actual es la 1 de 60.
    - *Verificado con datos en HU-15:* las partidas son las cuotas 1 a 25 de 60, una por mes hasta el horizonte.
  - Cuotas: una partida de un Concepto sin cuotas no muestra "cuota x de n".
    - *Verificado con datos en HU-15:* no trae número ni total, y la pantalla muestra «—».
  - Edición (HU-13): un cambio de monto vigente se refleja en el presupuestado de cada partida pendiente no editada de los meses abiertos (también en las parciales, cuando haya movimientos) y no en el de las editadas, consolidadas ni de períodos cerrados.
    - *Verificado con datos en HU-14 (e2e):* después de editar y confirmar el aviso, el monto vigente del Concepto en la lista es el nuevo.
    - *Verificado con datos en HU-15:* el presupuestado y el pendiente de las 27 partidas del Concepto pasan al monto nuevo, también en los meses pasados sin cerrar (S-24), y las partidas de los demás Conceptos no cambian. *Verificado con datos en HU-17:* con una partida editada (240.000,00) y un cambio del monto vigente a 200.000,00, la editada conserva 240.000,00 y su marca, las demás pasan a 200.000,00, y los conteos del aviso (24 pendientes sin editar y 1 editada) coinciden con las partidas. *Verificado con datos en HU-19:* una partida Parcial no editada toma el monto nuevo (presupuestado 500.000,00 con 100.000,00 pagados: pendiente 400.000,00), y una Parcial editada con movimientos (60.000,00) no cambia. *Sigue pendiente* lo que hoy no se puede producir: que no cambien las consolidadas (HU-23) ni las de períodos cerrados (HU-33).
  - Edición (HU-13): un cambio de día de vencimiento o de desfase se refleja en el vencimiento de cada partida pendiente de los meses abiertos, editada o no (día 31 en un mes de 30 y en febrero; desfase −1 en enero).
    - *Verificado con datos en HU-15:* día 29 → 31 (30/11, 28/02/2027, 29/02/2028); desfase 0 → −1 (cada partida vence el mes anterior, también las de meses pasados; enero vence el 25/12 del año anterior) y de vuelta a 0. *Verificado con datos en HU-17:* una partida editada en su monto sí cambia de vencimiento con el Concepto (día 10 → 25: 25/10 y 25/11).
  - Edición (HU-13): un cambio de cuenta por defecto se refleja en la cuenta de las partidas pendientes sin movimientos; una parcial conserva la anterior.
    - *Verificado con datos en HU-15:* las 25 partidas pasan a la cuenta nueva; una cuenta de otra moneda responde `CURRENCY_MISMATCH` y no cambia ninguna. *Verificado con datos en HU-19* que una parcial conserve la anterior: el Alquiler de este mes, con un pago desde el Banco, sigue en el Banco después de pasar el Concepto a la Billetera, y las de los meses anterior y siguientes pasan a la Billetera; el movimiento conserva su cuenta.
  - Edición (HU-13): el nombre y la categoría nuevos aparecen en todas las partidas del Concepto.
    - *Verificado con datos en HU-15:* también al quitar la categoría. Las ediciones no crean ni duplican partidas.
  - Edición (HU-13): si el horizonte avanzó, la partida que genera la edición sale con el vencimiento, la cuenta y el monto nuevos.
    - *Sigue pendiente:* se verifica en el primer cambio de mes.

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
  - Criterio 2, "cada partida muestra cuota x de n": se ve en la vista del mes (HU-15). *Completado y verificado con datos en HU-15.*
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
  - Criterio 3, "una partida eliminada con Solo este mes no vuelve a aparecer": *verificado de punta a punta en HU-18*, con la aplicación y un usuario de prueba: se elimina la partida de un mes, se inicia sesión de nuevo y la partida no reaparece ni cambia el Concepto. También lo cubre `EntryDeletionHorizonTest`, que usa el servicio y el horizonte reales sobre repositorios simulados.
  - Criterio 1, "al editar un Concepto se asegura el horizonte": el disparo llega con HU-13.
  - *HU-15 no cambia esto: sigue pendiente.* El avance del horizonte con datos reales no se puede probar porque no se puede adelantar el reloj de la aplicación. **Verificar la primera vez que cambie el mes, mirando en la vista del mes (HU-15)**: que aparece el período nuevo con sus partidas, una por cada Concepto que le toca, con el monto vigente de ese momento, sin duplicados, y que un plan de cuotas cortado por el horizonte sigue con el número que corresponde. Se suma a la lista de verificación de HU-15 de las notas de HU-10.

### HU-13 · Editar un Concepto

**Como** usuario **quiero** corregir un Concepto **para** que sus partidas futuras reflejen el cambio.

1. Se editan nombre, categoría, cuenta por defecto (solo otra de la misma moneda; si no, `CURRENCY_MISMATCH`), día de vencimiento, desfase, regla de estimación y monto vigente, con los efectos de RN-15.
2. Tipo, periodicidad, período de inicio, período de fin y cuotas no se editan: 409 `FIELD_NOT_EDITABLE`.
3. Antes de guardar un cambio de monto vigente, la pantalla avisa que reemplaza las partidas pendientes no editadas y que las editadas no cambian.
4. Nunca cambian partidas consolidadas ni de períodos cerrados.
5. Para dar de baja un Concepto se elimina su partida desde el mes elegido (HU-18); la pantalla del Concepto lo explica.

Reglas: RN-15.

**Notas de implementación**

- Depende de S-11 (el monto vigente reemplaza el presupuestado de las pendientes no editadas, parciales incluidas), S-12 (ahora también el fin no se edita), S-19 (renombrar no valida unicidad), S-20 (un vencimiento recalculado puede caer en un mes cerrado o anterior al período inicial: no se rechaza), D-11 (editar un Concepto no es editar una partida: no marca ni desmarca partidas editadas), D-24 (cuenta o categoría inexistentes en el cuerpo: 400 en su campo) y S-21. Agrega S-23 (un Concepto finalizado se edita igual), S-24 (período abierto = no cerrado, meses pasados incluidos) y S-25 (cambiar la cuenta no se compara con su fecha de apertura, sin confirmar). Aclaraciones en RN-15.
- `GET /api/budget-items/{id}` (`getBudgetItem`) y `PUT /api/budget-items/{id}` (`updateBudgetItem`). El `PUT` recibe el mismo `BudgetItemRequest` del alta, completo, y ambos responden `BudgetItemDetail`: los datos del Concepto, su `currency`, `editability` (tipo, periodicidad, inicio, fin y cuotas, todos no editables, con su motivo; reutiliza `FieldEditability` de cuentas) y `entryCounts`: `pendingNotManual` (las que reemplazaría un cambio de monto vigente) y `pendingManual` (las que no cambian), de períodos abiertos y calculadas en el backend. Las consolidadas y las de períodos cerrados no cuentan.
- Datos no editables: `BudgetItemEditability` (regla pura). Enviar el mismo valor no es un cambio. En un Concepto en cuotas el fin es calculado: si el pedido no lo informa, no cuenta como cambio; una primera cuota vacía vale 1, como al crear. El `detail` del 409 trae el motivo, o la lista de datos si cambian varios. Un pedido rechazado no guarda nada.
- Orden de los errores: Bean Validation (400); Concepto inexistente o ajeno (404); cuenta o categoría inexistentes o ajenas (400, campos `defaultAccountId` y `categoryId`); datos no editables (409 `FIELD_NOT_EDITABLE`); cuenta de otra moneda (409 `CURRENCY_MISMATCH`).
- Efectos sobre las partidas: `BudgetItemEditEffects` (regla pura, una fila por tipo de partida y dato editado en su test): vencimiento, toda pendiente de un período abierto; cuenta, la pendiente de un período abierto sin movimientos (editada o no); monto vigente, la pendiente de un período abierto no editada (con o sin movimientos). Solo se recalcula lo que cambió. Los filtros (consolidada, período cerrado, con movimientos) se aplican en Java sobre todas las partidas del Concepto (`BudgetEntryRepository.findByUserIdAndBudgetItemId`, `MovementRepository.findEntryIdsWithMovements`, que se consulta solo para las candidatas), así se prueban con repositorios simulados aunque hoy no se puedan producir desde la aplicación.
- Orden con el horizonte (RN-07): el Concepto se modifica, después se recalculan las partidas existentes y al final se asegura el horizonte, así las partidas que genera salen ya con los datos nuevos y no pasan por el recálculo. También sin cambios se asegura el horizonte (RN-07 dice «al editar»). El test (`BudgetItemEditServiceTest`) mira los valores de la partida en el instante de guardarla, no solo cómo termina, y el orden de las consultas; se comprobó que falla si se invierte el orden.
- La respuesta no puede leer asociaciones diferidas (`open-in-view: false`): la moneda del Concepto la lee el servicio dentro de la transacción. Es un error que los tests con repositorios simulados no podían ver y que apareció en la primera corrida de punta a punta.
- Frontend: `/conceptos/:id/editar` (`BudgetItemEdit`) reutiliza `BudgetItemForm` con un `item`. Tipo, periodicidad, inicio, fin y cuotas quedan deshabilitados con el motivo del backend; cada dato editable explica su efecto en las partidas. Un cambio de monto vigente abre un diálogo con el monto de antes y de después, cuántas partidas reemplaza y que las editadas, consolidadas y de períodos cerrados no cambian (con los conteos con los que se cargó la pantalla; no hay vista previa nueva en el servidor). Al guardar se queda en la pantalla, con un aviso y los datos actualizados. La cuenta por defecto muestra todas las cuentas y el backend rechaza otra moneda (`CURRENCY_MISMATCH`, con mensaje propio). La pantalla explica cómo dar de baja el Concepto.
- Cómo se llega a la pantalla: desde «Editar» en cada fila de la lista de Conceptos (HU-14) y desde el enlace «Editar este Concepto» del resumen que aparece al crear un Concepto. Desde la edición, «Volver a la lista» lleva a `/conceptos`.
- **Criterios que se completan en otra historia**:
  - Criterio 5, dar de baja eliminando la partida desde el mes elegido: *resuelto en HU-18*. La pantalla explica el paso a paso y enlaza al presupuesto del mes; el Concepto termina el mes anterior o, si no le queda ninguna partida, se elimina (y deja libres su cuenta y su categoría).
  - El efecto de los cambios sobre las partidas no se puede ver hasta la vista del mes (HU-15). Los puntos a verificar se sumaron a la lista de HU-10. *Verificados con datos en HU-15*, salvo lo que depende de partidas editadas, movimientos, consolidación o cierre.
  - Los filtros de «con movimientos», «consolidada» y «período cerrado» solo se probaron con repositorios simulados: se verifican con datos en HU-19 (movimientos, *hecho*: el de «con movimientos» filtra a la Parcial en el cambio de cuenta y no en el de monto), HU-23 (consolidar) y HU-33 (período cerrado).
- **Pendiente de integración**: las dos consultas nuevas (`findByUserIdAndBudgetItemId` y `findEntryIdsWithMovements`) no se pueden probar contra MySQL con tests (sin Docker ni Testcontainers). La primera la ejecutó sin error la corrida de punta a punta (editar el monto o el día guarda 25 partidas); la segunda solo corre al cambiar la cuenta de un Concepto con partidas pendientes, y todavía no existen movimientos para probarla con datos. En HU-15 corrió sin error contra la base (cambio de cuenta con 25 partidas pendientes) y no devolvió ninguna, que es lo correcto sin movimientos; con movimientos se verifica en HU-19. *Verificada con datos en HU-19*: `findEntryIdsWithMovements` devolvió las partidas con movimientos en el cambio de cuenta y de monto, sin error.
- Verificado de punta a punta con un usuario de prueba, a través de la aplicación y en el navegador: editar el nombre y el monto vigente pasando por el aviso (cancelarlo no guarda nada, confirmarlo guarda), cambiar solo el día sin aviso, y una cuenta en dólares sobre un Concepto en pesos muestra el mensaje y no guarda. Los datos bloqueados se ven deshabilitados con su motivo. Por la API: un dato no editable responde 409 sin guardar nada y un id ajeno o inexistente, 404.

### HU-14 · Listar Conceptos

**Como** usuario **quiero** ver todos mis Conceptos **para** revisar qué tengo configurado.

1. Lista con nombre, tipo, cuenta y moneda, categoría, periodicidad, vencimiento (día y desfase), regla de estimación, monto vigente y estado: Activo, Finalizado (su fin es anterior al período actual) o Por comenzar (su inicio es posterior). En un Concepto en cuotas activo, además, la cuota actual y cuántas quedan (D-27).
2. Filtros por tipo y por categoría, que incluye «Sin categoría» (D-28).
3. Estado vacío distinto cuando no hay Conceptos y cuando el filtro no devuelve nada.
4. «Nuevo Concepto» lleva al alta (`/conceptos/nuevo`) y cada fila tiene «Editar» (`/conceptos/:id/editar`).

Reglas: RN-10, RN-14.

**Notas de implementación**

- Depende de D-27 (estado y cuotas por calendario) y D-28 (orden, filtros y errores del filtro), que agregó esta historia, y de S-23 y S-12. El glosario suma `BudgetItemStatus`, `currentInstallment` e `installmentsRemaining`. No agrega supuestos nuevos.
- `GET /api/budget-items` (`listBudgetItems`): lista de `BudgetItemListItem` sin paginación ni totales. Parámetros opcionales `kind`, `categoryId` y `withoutCategory`. Errores: `kind` inválido o `categoryId` con `withoutCategory` juntos, 400 `VALIDATION_ERROR` (el segundo con el campo `withoutCategory`); categoría inexistente o de otro usuario, 404 `NOT_FOUND`.
- Cada fila trae la cuenta (id y nombre), la moneda, la categoría (id y nombre), los datos de la periodicidad y el vencimiento, el fin (el calculado en un plan de cuotas), los datos del plan, `status`, `currentInstallment` e `installmentsRemaining`.
- Regla pura `BudgetItemStatusCalculator` en `budgetitem/domain`, con el período actual del `Clock`. Un plan bimestral, trimestral, semestral o anual avanza una cuota por paso; entre dos cuotas, la actual es la última cuyo período ya llegó.
- Sin consultas por fila: `BudgetItemRepository.findAllByUserIdWithAccountAndCategory` trae cuenta y categoría con `join fetch`, y el servicio arma cada fila dentro de la transacción (`open-in-view: false`). Los filtros y el orden se aplican en Java sobre los Conceptos del usuario, así se prueban con repositorios simulados.
- **Pendiente de integración**: ese `join fetch` (con `left join` para la categoría) no se pudo probar contra MySQL con tests (sin Docker ni Testcontainers). La aplicación lo ejecutó sin error en la corrida de punta a punta y en el navegador, con Conceptos con y sin categoría.
- Frontend: `/conceptos` es la lista (`BudgetItems`, tabla de Angular Material); el alta pasó a `/conceptos/nuevo` (`BudgetItemNew`). Los filtros se piden al backend; cada cambio cancela el pedido anterior. El estado se ve en palabras («Cuota 4 de 12, quedan 8», «Cuota 12 de 12, última»), con el fin («termina en junio 2027», «terminó en junio 2026») o el inicio («en diciembre 2026») debajo. Dos estados vacíos distintos (sin Conceptos / el filtro no devuelve nada, con «Quitar filtros»). Cada fila tiene «Editar» (con el nombre en su etiqueta para lectores de pantalla). El resumen del alta ofrece «Editar este Concepto» y «Volver a la lista»; el formulario ya queda vacío, así que no hace falta un «Cargar otro».
- Tests: regla del estado parametrizada (sin fin, fin en el período actual, fin anterior, a mitad del plan, última cuota, terminado, por comenzar, bimestral, trimestral, anual, primera cuota distinta de 1); servicio con filtros solos y combinados, categoría ajena y aislamiento por `userId`; `@WebMvcTest` de la forma de la respuesta y los parámetros inválidos; tests unitarios de la tabla, los filtros y los estados vacíos; y de punta a punta (`e2e/conceptos-y-presupuesto.spec.ts`) con un usuario propio: alta de un ingreso, un gasto con categoría y uno en cuotas, lista con su estado, filtros, edición desde una fila y el monto nuevo después de editar.
- Verificado en el navegador con un usuario de prueba: la lista con los cuatro estados, los filtros con el teclado (con foco visible), el estado vacío con filtros y el error del backend de la lista.

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

**Notas de implementación**

- Depende de S-20 (una partida se lista en su período aunque venza en otro mes; con desfase −1 puede nacer vencida), S-24 (un mes pasado sin cerrar se ve abierto, con sus partidas pendientes vencidas), S-19 (los nombres se repiten: hace falta un desempate), S-11, S-14, S-17 y S-22, y de T-12 (los montos viajan con 2 decimales y el frontend no hace cuentas) y T-13 («hoy» y el período actual salen del `Clock`). Agrega D-29, con aclaraciones en RN-20 y RN-44, y el glosario suma Vencida (`overdue`) y Resultado (`result`). No agrega supuestos.
- `GET /api/periods/{period}` (`getPeriod`) y `GET /api/periods/current` (`getCurrentPeriod`) responden `PeriodView`: `period`, `status`, `startPeriod`, `currentPeriod`, `horizon`, `incomes`, `expenses` y `totals`. Formato inválido: 400 `VALIDATION_ERROR`. Período que no existe para el usuario: 404 `NOT_FOUND`. `GET /api/periods?from=&to=` no se implementó: queda para HU-36 (D-29).
- Cada partida (`PeriodEntry`) trae `id`, `budgetItemId`, `origin`, `kind`, nombre y categoría (los del Concepto en una recurrente), cuenta, `currency`, `dueDate`, `installmentNumber`, `installmentsTotal`, `budgetedAmount`, `actualAmount`, `pendingAmount`, `forecastAmount`, `status`, `manual` y `overdue`.
- `totals`: por cada moneda con partidas, `income` y `expense` (cada uno con `entryCount`, presupuestado, real, pendiente y estimado) y `result`. `entryCount` le dice a la pantalla si la sección tiene partidas de esa moneda sin que tenga que contarlas.
- Reglas puras: `EntryAmounts` (RN-16 y RN-17) y `OverdueRule` (RN-20) en `entry/domain`; `MonthTotals` (RN-44) en `period/domain`. La suma de movimientos nula o en 0 es «sin movimientos», porque todo movimiento es mayor que 0 (RN-03).
- `PeriodViewService` (solo lectura) hace cuatro consultas, ninguna por fila: el período por usuario y mes, los meses que existen para el usuario (de ahí salen el período inicial y el horizonte informados), las partidas con cuenta, categoría, Concepto y categoría del Concepto (`findByUserIdAndPeriodIdWithDetails`, con `join fetch`), y las sumas de movimientos de todo el período agrupadas por partida (`sumByEntryOfPeriod`). Arma las filas dentro de la transacción (`open-in-view: false`). El orden se aplica en Java.
- Frontend: `BudgetMonth` en `features/budget/`, con `EntryTable` (una sección) y `period-nav` (funciones puras de navegación). `/presupuesto` pide `/current` y `/presupuesto/:period` pide ese mes; son **una sola ruta** (`budgetMonthMatcher`): con dos, Angular recreaba la pantalla al pasar de una a otra y «Mes siguiente» perdía el foco del teclado en el primer cambio de mes (apareció al probar en el navegador). El selector de mes es un `mat-select` con los meses del rango en palabras. Una URL que no es un mes no llama a la API. Mientras carga otro mes, la navegación se conserva.
- Marcas: «Vencida» junto al vencimiento y «Editada» junto al presupuestado, como texto con borde; el estado, en palabras; «Cerrado» y «Mes actual», junto al título. Un resultado negativo se ve con signo. Al pie de cada sección hay un total por moneda con partidas en esa sección; el bloque Resultado muestra, por cada moneda del período, ingresos estimados, gastos estimados y resultado.
- **Dónde van las acciones** (llegaron con HU-16): las del período (agregar una partida, cerrar el mes), en la cabecera junto al título; las de cada partida, en una última columna «Acciones» de `EntryTable`. Las dos dependen de `readonly`, que hoy es «el período está cerrado». Hoy no se dibuja nada vacío.
- Tests: `EntryAmountsTest`, `OverdueRuleTest` y `MonthTotalsTest` (parametrizados, con el ejemplo del criterio 6 y sus nueve números); `PeriodViewServiceTest` (orden, nombre y categoría del Concepto o propios, rango, período fuera de rango, datos de otro usuario); `PeriodControllerTest`; unitarios de la navegación, las marcas y los totales; y de punta a punta en `e2e/conceptos-y-presupuesto.spec.ts`. Los tres tests de aislamiento de HU-06 pasan sin cambios.
- **Pendiente de integración**: las tres consultas nuevas no se pueden probar contra MySQL con tests (sin Docker ni Testcontainers). Las ejecutaron sin error la corrida de punta a punta y la verificación con datos, con partidas de todo tipo; `sumByEntryOfPeriod` todavía no devolvió filas porque no existen movimientos: su contenido se verifica en HU-19. *Verificada con datos en HU-19*: devuelve la suma por partida del período y la vista coincide con los movimientos (incluida una partida con un cobro de otro mes, que cuenta por su partida y no por su fecha).
- **Criterios que se completan en otra historia**:
  - Criterio 5, período cerrado: la pantalla muestra «Cerrado» y no tiene acciones (test unitario con una respuesta `CLOSED`, que con HU-16 ya tiene acciones que ocultar), pero no se puede cerrar un mes hasta HU-33.
  - Criterio 6: el ejemplo necesita partidas consolidadas y parciales. Está cubierto por los tests de las reglas, del servicio y de la pantalla; con datos reales solo se vieron partidas Estimadas. Parcial, Consolidada, real y pendiente distintos del presupuestado se verifican en HU-19 y HU-23. *Verificado con datos en HU-19* Parcial y real y pendiente distintos del presupuestado: Luz de 45.000,00 con 20.000,00 y 10.000,00 pagados queda con real 35.000,00, pendiente 10.000,00, estimado 45.000,00 y Parcial; con un pago que lo supera, real 55.000,00, pendiente 0,00, estimado 55.000,00; y los totales por moneda del mes con movimientos son la suma de las filas y el resultado es estimado de ingresos menos estimado de gastos. *Sigue pendiente* Consolidada (HU-23).
  - La marca «Editada» *se verificó con datos en HU-17*: aparece junto al presupuestado de la partida recurrente cuyo monto se editó, y no en las demás.
  - Las partidas puntuales (nombre y categoría propios) *se verificaron con datos en HU-16*. Las de saldo postergado y de diferencia de cierre solo se probaron con repositorios simulados: llegan con HU-32.
- Verificado en el navegador con un usuario de prueba: el mes actual con ingresos y gastos en pesos y dólares, totales y resultado separados por moneda, partidas vencidas marcadas, «Mes anterior», «Mes siguiente», selector y «Hoy»; «Mes siguiente» y «Hoy» también con el teclado, con foco visible y sin perderlo al cambiar de mes; y un período fuera de rango (`/presupuesto/2030-01`), que muestra el mensaje y «Ir al mes actual».
- Verificación con datos de las listas de HU-10 a HU-13: 40 comprobaciones por la API con dos usuarios de prueba, todas coinciden con lo esperado. El detalle está en cada punto de la lista de HU-10.

**Endpoints de la épica**

| Método y ruta | Uso |
|---|---|
| `GET /api/budget-items` | Lista de Conceptos. |
| `POST /api/budget-items` | Crear (genera partidas). |
| `GET /api/budget-items/{id}` · `PUT /api/budget-items/{id}` | Ver y editar. |
| `GET /api/periods/{period}` | Vista del mes: partidas con valores derivados y totales. |
| `GET /api/periods/current` | La misma vista, para el período actual (D-29). |
| `GET /api/periods?from=YYYY-MM&to=YYYY-MM` | Períodos con estado y totales por moneda. Pasa a HU-36 (D-29). |

---

## Épica 4 · Partidas

### HU-16 · Agregar una partida puntual

**Como** usuario **quiero** cargar algo que pasa una sola vez **para** que figure solo en ese mes.

1. En un período abierto: nombre, tipo, cuenta, vencimiento, presupuestado y categoría opcional.
2. Aparece solo en ese período y no se copia a otros.
3. El vencimiento debe estar entre el primer día del mes anterior al período y el último día del período; si no, 400.
4. Se edita (nombre, categoría, cuenta, vencimiento, presupuestado) mientras esté pendiente. Si tiene movimientos, la cuenta solo cambia por otra de la misma moneda.

Reglas: RN-18, RN-19.

**Notas de implementación**

- Depende de S-17 (categoría opcional y sin tipo), S-19 (los nombres se repiten), S-20 y S-24 (el vencimiento puede caer en un mes cerrado; un mes pasado sin cerrar admite partidas), S-21 y S-25 (la apertura de la cuenta no se compara), D-11, D-24, T-12 y T-13. Agrega **D-30**, con aclaraciones en RN-18 y RN-19. No cambia el modelo: sin migración. Se resolvieron con el usuario cuatro dudas: el vencimiento no se compara con la apertura de la cuenta, sin movimientos la cuenta puede ser de otra moneda, el tipo no se edita y un vencimiento anterior a hoy es válido (D-30).
- `POST /api/periods/{period}/entries` (`createOneOffEntry`, 201) y `PATCH /api/entries/{id}` (`updateEntry`, 200) responden `PeriodEntry`, la misma forma que la vista del mes; el frontend recarga la vista para los totales. El orden en que se evalúan los errores está en D-30 y en la descripción de cada operación del OpenAPI.
- **PATCH**: `name`, `kind`, `accountId`, `categoryId`, `clearCategory`, `dueDate` y `budgetedAmount`. Omitido o `null` = no cambia; `clearCategory: true` vacía la categoría. Una recurrente responde 409 `FIELD_NOT_EDITABLE` si el cuerpo cambia algo: HU-17 solo levantó eso para `budgetedAmount`.
- Regla pura `EntryDueDateRange` (`entry/domain`). Servicio `EntryService` (`entry/service`), controlador `EntryController` (`entry/web`). `PeriodViewService.row` pasó a ser público para construir la respuesta con el mismo código que la vista. El servicio no depende de `HorizonService` ni de Conceptos.
- Frontend: `EntryFormDialog` (alta y edición, Material, formularios reactivos), `canEditEntry` (qué partidas ofrecen «Editar»: sin Concepto y no consolidadas) y `suggestedDueDate` (hoy en el mes actual, el día 1 en cualquier otro: siempre dentro del período). La columna «Acciones» de `EntryTable` solo existe si el período no está cerrado y alguna partida de la sección tiene una acción: no se dibuja vacía. Al guardar se vuelve a pedir el mes **sin desmontar las tablas** y se devuelve el foco al «Editar» de la partida, aunque haya cambiado de lugar. El error de un campo sale bajo ese campo y lleva el foco ahí; cualquier otro, arriba del formulario. Ante `PERIOD_CLOSED` o `ENTRY_NOT_PENDING` la pantalla estaba desactualizada y al cerrar el diálogo se recarga.
- Tests: `EntryDueDateRangeTest` (bordes, enero, febrero común y bisiesto), `EntryServiceTest` (41, con repositorios simulados y `Clock` fijo), `EntryControllerTest` (39, códigos y formato de errores); los tres de aislamiento de HU-06 pasan sin cambios. Frontend: unitarios del diálogo, de `suggestedDueDate`, de la visibilidad de las acciones y de la recarga en `BudgetMonth`; de punta a punta en `e2e/partidas.spec.ts`.
- **Pendiente de integración**: `BudgetEntryRepository.findByIdAndUserIdWithDetails` y `MovementRepository.sumByEntry` no se pueden probar contra MySQL con tests (sin Docker ni Testcontainers). La primera la ejecutan la corrida de punta a punta y la verificación con datos; `sumByEntry` corre en cada edición pero todavía no devuelve filas (no hay movimientos): su contenido se verifica en HU-19. *Verificada con datos en HU-19*: devuelve la suma de la partida editada y el valor derivado de la respuesta es el correcto.
- **Criterios que se completan en otra historia**: la restricción de moneda con movimientos (criterio 4) está implementada y probada con repositorios simulados; con datos reales se verifica en HU-19. *Verificado con datos en HU-19*: una partida con movimientos cambia a otra cuenta en pesos (200, sus movimientos conservan cada uno su cuenta) y a una en dólares responde 409 `CURRENCY_MISMATCH` sin cambiar; sin movimientos sí admite otra moneda (D-30). «Editar» de una partida consolidada y el período cerrado reales dependen de HU-23 y HU-33: hoy se prueban con respuestas simuladas.
- Verificado en el navegador con un usuario de prueba: agregar un gasto en dólares con el teclado (el foco arranca en el nombre, Tipo y Cuenta se eligen con flechas y Enter, el monto con formato inválido se marca sin llamar al backend) y ver los totales en pesos y en dólares por separado; editar la partida; un vencimiento fuera de rango, con el error del backend bajo el campo, el diálogo abierto y todo lo cargado; Escape devuelve el foco al «Editar» de la partida. Al verificarlo se encontró y corrigió que la pista de la cuenta se proyectaba dentro del cuadro al editar.
- Verificación con datos (36 comprobaciones por la API con dos usuarios de prueba, todas coinciden): el alta con nombre recortado y categoría propia; el vencimiento en sus cuatro bordes (1.º del mes anterior, el día previo, último día del período y el siguiente); cuenta o categoría ajenas (400 en su campo); período inexistente (404) y de formato inválido (400); `PATCH` campo por campo, `categoryId: null` sin efecto, `clearCategory`, cuerpo vacío, cuenta de otra moneda sin movimientos, tipo distinto (409), recurrente (409), partida de otro usuario (404); y los totales por moneda.
  - *Verificado con datos en HU-16* lo que HU-15 dejó pendiente: las puntuales con nombre y categoría propios en la vista del mes, y su orden junto a las recurrentes (mismo vencimiento: «luz» recurrente antes que «Luz» puntual, por nombre y luego por creación). Las de saldo postergado y diferencia de cierre siguen para HU-32.
  - *Verificado con datos en HU-16* las consultas de HU-07 y HU-09: una categoría usada solo por una partida puntual responde 409 `CATEGORY_IN_USE` y, al vaciarla, se elimina; una cuenta usada solo por una partida puntual responde 409 `ACCOUNT_IN_USE`.

### HU-17 · Editar el monto de una partida

**Como** usuario **quiero** ajustar el monto de un mes puntual **para** reflejar lo que ya sé, sin tocar los demás meses.

1. En una partida pendiente de un período abierto se cambia el presupuestado (≥ 0) y queda marcada como editada.
2. No cambia ninguna otra partida ni el monto vigente del Concepto.
3. Ejemplo: Resumen Visa de noviembre pasa de 180.000,00 a 240.000,00; diciembre sigue en 180.000,00.
4. Partida consolidada: 409 `ENTRY_NOT_PENDING`. Período cerrado: 409 `PERIOD_CLOSED`.

Reglas: RN-18.

**Notas de implementación**

- Depende de D-11, S-11, S-24 y D-30, y de RN-15 (el monto vigente respeta las editadas). Agrega **D-31** (mismo monto, marca que no se quita, cuerpo mixto, parciales) y aclaraciones en RN-18. No cambia el modelo (`is_manual` ya existía): sin migración. Se resolvieron con el usuario cuatro dudas, todas por la opción conservadora (D-31).
- Backend: `EntryService.update` deja de tratar `budgetedAmount` como dato no editable en una recurrente y, si el monto cambia, pone `manual = true`. No toca otras partidas ni el Concepto, ni asegura el horizonte. El orden de errores de D-30 no cambia. `docs/openapi.json` solo cambió en las descripciones (resumen, texto del PATCH y del 409, descripción del cuerpo); el cliente regenerado no cambió en tipos.
- Frontend: `canEditAmount` (recurrentes pendientes) junto a `canEditEntry`; una partida ofrece una acción u otra. «Editar monto» abre `EntryFormDialog` en modo `amountOnly`: muestra el Concepto y el mes, solo el presupuestado, y aclara que vale para ese mes y que los demás y el monto vigente no se tocan. Reutiliza el manejo de errores, la recarga sin desmontar las tablas y la devolución del foco (que ahora busca «Editar» o «Editar monto»). Con las recurrentes pendientes ofreciendo acción, la columna «Acciones» aparece en cualquier mes abierto con partidas pendientes.
- Tests: `EntryServiceTest` (ejemplo de la historia con el monto vigente intacto, 0, por debajo de lo pagado, Parcial, mismo monto, volver al vigente, cuerpo mixto, consolidada, período cerrado, otro usuario, sin Concepto), un test de HU-17 con HU-13 en `BudgetItemEditServiceTest` (incluye los conteos del aviso) y `EntryControllerTest`; los de HU-16 y los tres de aislamiento de HU-06 pasan sin cambios. Frontend: `entry-actions.spec.ts`, el modo `amountOnly` del diálogo y `BudgetMonth`; de punta a punta en `e2e/partidas.spec.ts` (editar el mes actual, marca y totales, mes siguiente intacto, cambio del monto vigente que respeta la editada, formato inválido y rechazo del backend). Se ajustaron tests de HU-15 y HU-16 que daban por vacía la columna «Acciones».
- Verificación con datos (usuario de prueba propio, por la API y en el navegador con Playwright): mismo monto sin marca; 240.000,00 deja la partida editada y estimada con totales nuevos; 0 deja pendiente 0; volver a 180.000,00 la deja editada; el mes siguiente y el monto vigente no cambian; después del cambio del monto vigente a 200.000,00 la editada se conserva y las demás toman el valor nuevo, con conteos del aviso 24 y 1; un cambio de vencimiento del Concepto sí alcanza a la editada.
- **Pendiente de integración**: `sumByEntry` con movimientos reales (la Parcial y «por debajo de lo pagado» solo se probaron con repositorios simulados) se verifica en HU-19 (*hecho*: una puntual con presupuestado 20.000,00 y 30.000,00 pagados queda con pendiente 0,00, estimado 30.000,00 y Parcial; una recurrente Parcial con 100.000,00 pagados editada a 60.000,00 queda editada, con pendiente 0,00 y estimado 100.000,00, y un cambio posterior del monto vigente no la pisa); consolidada y período cerrado reales, en HU-23 y HU-33.

### HU-18 · Eliminar una partida

**Como** usuario **quiero** eliminar una partida **para** quitar algo que no va a pasar, en un mes o de ahí en adelante.

1. Partida sin Concepto: pide una confirmación simple y se elimina si está pendiente y sin movimientos.
2. Partida de un Concepto: el diálogo pregunta "Solo este mes" o "Este mes y los siguientes".
3. Solo este mes: se elimina esa partida, las demás siguen y no reaparece.
4. Este mes y los siguientes: se eliminan todas las partidas del Concepto desde ese período, el Concepto termina en el mes anterior y no genera más. Si se queda sin partidas, se elimina el Concepto.
5. Si alguna partida afectada tiene movimientos o está consolidada: 409 `ENTRY_HAS_MOVEMENTS` o `ENTRY_NOT_PENDING`, con la lista de partidas que lo impiden, y no se elimina nada.
6. Nunca se tocan partidas consolidadas ni de períodos cerrados.

Reglas: RN-13, RN-30, RN-31, RN-32.

**Notas de implementación**

- Depende de D-09 (`generated_until` evita que una partida eliminada reaparezca), D-19, D-27, D-30, D-31, S-08 (**sin confirmar**: una partida con movimientos no se elimina; toda la historia, desde el criterio 5, se apoya en esto), S-11, S-12 y S-24 (un mes pasado sin cerrar admite eliminar). Agrega **D-32** y aclaraciones en RN-32; ajusta D-27 y el glosario. No cambia el modelo: sin migración. Se resolvieron con el usuario seis dudas antes de escribir código, todas por la opción recomendada (D-32): alcance faltante o sobrante, «Solo este mes» sobre la última partida, plan de cuotas recortado, código con consolidadas y movimientos juntos, vista previa y partidas de saldo postergado y diferencia de cierre.
- `DELETE /api/entries/{id}?scope=` (`deleteEntry`, 204) y `GET /api/entries/{id}/deletion-preview` (`getEntryDeletionPreview`, 200), este último no listado en el diseño inicial de la épica: lo necesita el diálogo para decir cuántas partidas se eliminan y qué pasa con el Concepto, sin que el frontend repita reglas. El orden de los errores está en RN-32 y en la descripción de cada operación del OpenAPI.
- Regla pura `EntryDeletionPlanner` (`entry/domain`, con `DeletionScope`): qué partidas entran en el alcance, cuáles lo impiden y cómo queda el Concepto (sigue, fin nuevo o desaparece). La usan por igual la vista previa y la eliminación. `EntryService.delete` y `deletionPreview` cargan los datos y aplican el plan.
- Sin una consulta por partida: las partidas del Concepto se leen con `findByUserIdAndBudgetItemId` (ya existía), los movimientos se consultan una vez y solo para las pendientes con período igual o posterior al de la elegida (`findEntryIdsWithMovements`), y el borrado es una sola sentencia (`BudgetEntryRepository.deleteByUserIdAndIdIn`); después, si corresponde, `BudgetItemRepository.deleteByUserIdAndId`. Si el borrado afecta menos filas de las esperadas, falla y se deshace todo. Todo o nada, en una transacción. Con un impedimento no se llama a ningún borrado ni guardado.
- `generated_until` no se toca: siempre queda en o después del fin nuevo (P − 1 < P ≤ `generated_until`), así que `findPendingGeneration` no trae el Concepto. `BudgetItemEditability` tolera el fin recortado: un plan no informa el fin en el pedido (D-26).
- `BudgetItemStatusCalculator`: «cuotas que quedan» pasa a ser `última cuota que sigue en el plan − cuota actual` (D-27). Sin recortar da lo mismo que antes.
- Frontend: acción «Eliminar» en la columna «Acciones» (`canDeleteEntry`: Estimadas y Parciales), `EntryDeleteDialog` (confirmación simple, o «Solo este mes» / «Este mes y los siguientes» sin ninguna elegida, con el botón diciendo lo que hace), `EntryDeletionBlockers` (impedimentos por mes y motivo) y `entry-deletion-text.ts` (textos puros). Después de eliminar, la vista se vuelve a pedir **sin desmontar las tablas** y el foco pasa a la acción de la fila que ocupa el lugar de la eliminada, si no a la anterior y, si la sección quedó vacía, a su encabezado. La pantalla del Concepto explica la baja y enlaza al presupuesto.
- Tests: `EntryDeletionPlannerTest` (31, parametrizado: P igual al inicio, P posterior con cruce de año, bimestral, plan de cuotas, anteriores intactas, consolidada, con movimientos, ambas, «Solo este mes» sobre la última partida), `EntryDeletionServiceTest` (36: cada alcance, todo o nada, la lista `entries`, sin Concepto con movimientos, período cerrado, otro usuario, otros Conceptos, vista previa), `EntryDeletionHorizonTest` (HU-18 con HU-12: no reaparece y no genera más), `BudgetItemStatusCalculatorTest` (plan recortado), y en `EntryControllerTest` los códigos HTTP y el formato de errores; los tres de aislamiento de HU-06 pasan sin cambios. Frontend: textos, impedimentos, los dos diálogos, `canDeleteEntry`, y la recarga y el foco en `BudgetMonth`. De punta a punta en `e2e/partidas.spec.ts`: puntual, «Solo este mes», «Este mes y los siguientes» desde un mes futuro, plan de cuotas recortado, baja desde el primer mes, caso de error (la partida ya no existe) y errores por la API. Se ajustaron expectativas de HU-15 y HU-16 que daban por sola la acción de la celda «Acciones».
- **Pendiente de integración**: no hay tests contra MySQL (sin Docker ni Testcontainers). Las sentencias nuevas (`delete ... where id in :ids` y el borrado del Concepto) y las consultas que reutiliza las ejecutó sin error la verificación con datos, pero `findEntryIdsWithMovements` todavía no devuelve filas (no existen movimientos): su contenido se verifica en HU-19. *Verificada con datos en HU-19*: devuelve las partidas con movimientos del alcance.
- **Criterios que se completan en otra historia**: las partidas consolidadas y con movimientos, y un período cerrado reales, no se pueden producir hasta HU-19, HU-23 y HU-33. El backend (regla pura y servicio) y el diálogo (con respuestas simuladas) están probados; con datos reales se verifican en esas historias, junto con «Desconsolidar» (HU-26) y eliminar movimientos (HU-21), que son lo que desbloquea una partida.
- Verificación con datos (usuarios de prueba propios, por la aplicación en el navegador y por la API; nada contra el usuario real ni con SQL): «Solo este mes» elimina solo esa partida, el mes anterior y el siguiente la conservan y no reaparece al iniciar sesión de nuevo; «Este mes y los siguientes» desde un mes futuro deja los anteriores, saca los posteriores y el Concepto figura con su fin nuevo en la lista; un plan de 12 cuotas recortado a la mitad dice «Cuota 1 de 12, quedan 5»; desde el primer mes el Concepto desaparece de la lista y su cuenta y su categoría, que antes respondían 409 `ACCOUNT_IN_USE` y `CATEGORY_IN_USE`, se eliminan; las partidas de otros Conceptos no cambian; sin alcance, con alcance sobrante, con un alcance inexistente, con un id inexistente o de otro usuario, las respuestas son las de RN-32; y la vista previa coincide con lo que hace la eliminación.
  - *Verificado con datos en HU-18* lo que las listas de HU-12 y HU-13 dejaban pendiente de eliminar: el criterio 3 de HU-12 y el criterio 5 de HU-13 (arriba). Lo que depende de movimientos o consolidaciones sigue pendiente para HU-19 y HU-23.
  - *Verificado con datos en HU-19* lo que dependía de movimientos (criterio 5, con un usuario de prueba y por la API): una puntual con movimientos responde 409 `ENTRY_HAS_MOVEMENTS` con su id y sigue ahí; una recurrente con movimientos no se elimina con «Solo este mes»; desde el mes anterior, «Este mes y los siguientes» responde 409 `ENTRY_HAS_MOVEMENTS` con la lista de partidas que impiden (la de este mes, con movimientos), la vista previa dice lo mismo (`allowed: false` y el impedimento) y no se elimina ni se cambia nada; «Solo este mes» sobre la partida sin movimientos se elimina (204). *Sigue pendiente* lo que depende de consolidar (HU-23, HU-26) y de eliminar movimientos (HU-21).

**Endpoints de la épica**

| Método y ruta | Uso |
|---|---|
| `POST /api/periods/{period}/entries` | Crear partida puntual (HU-16). |
| `PATCH /api/entries/{id}` | Editar partida: sin Concepto en HU-16; HU-17 suma el presupuestado de las recurrentes sin cambiar el contrato (D-31). |
| `DELETE /api/entries/{id}?scope=ONLY_THIS\|THIS_AND_FUTURE` | Eliminar. El alcance es obligatorio en una recurrente y no se envía en una sin Concepto (D-32). |
| `GET /api/entries/{id}/deletion-preview` | Vista previa de la eliminación: qué haría cada alcance y qué partidas lo impiden (D-32). |

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

**Notas de implementación**

- Depende de **S-01** (la ventana vale también para gastos), **S-02** (otra cuenta de la misma moneda) y **S-09** (sin fecha futura), los tres **sin confirmar**; de S-13, S-21 y S-25 (la apertura de la cuenta se controla acá), de S-11 y S-24 (las Parciales se tratan como pendientes; un mes pasado sin cerrar admite movimientos), de D-13, D-17 y D-24, y de T-18 (el pago rápido llega después de HU-25). Agrega **D-33** y aclaraciones en RN-21. No cambia el modelo: sin migración. Se resolvieron con el usuario tres dudas antes de escribir código, todas por la opción recomendada: el orden de evaluación, el aviso del criterio 6 sin consolidación y el mes anterior al período inicial.
- `POST /api/entries/{id}/movements` (`registerMovement`, 201 con `{movement, entry}`) y `GET /api/entries/{id}/movements` (`listMovements`). El orden en que se evalúan los errores está en D-33 y en la descripción de cada operación del OpenAPI.
- Regla pura `MovementDateValidator` (`movement/domain`, sin Spring ni JPA): recibe la fecha, el período de la partida, la apertura de la cuenta, hoy, la ventana y el estado del mes de la fecha, y devuelve la primera condición que falla. `MovementService` (`movement/service`) carga los datos, la aplica y guarda; no toca la partida. La ventana sale de `app.budget.early-days`.
- Frontend: `MovementDialog` (Material, formularios reactivos), `canRegisterMovement` y `movement-text.ts` (textos puros). «Registrar pago» en los gastos y «Registrar cobro» en los ingresos pendientes de un período abierto, primera acción de la fila. El diálogo muestra presupuestado, real y pendiente de la fila, lista los movimientos, y arranca con hoy, la cuenta de la partida y el pendiente como monto; solo ofrece cuentas de la moneda de la partida. Al guardar se vuelve a pedir el mes sin desmontar las tablas y el foco queda en la acción de la partida. Un error de fecha sale bajo el campo con el `detail` del backend (que nombra la fecha límite); `PERIOD_CLOSED` y `ENTRY_NOT_PENDING` marcan la pantalla como desactualizada y al cerrar se recarga. `DATE_OUT_OF_RANGE` se agregó al archivo de mensajes.
- Se ajustaron expectativas de la celda «Acciones» de HU-15, HU-16 y HU-18 en los e2e (suma «Registrar pago» o «Registrar cobro» y, tras eliminar, el foco pasa a esa primera acción de la fila que ocupa el lugar).
- Tests: `MovementDateValidatorTest` (40: el ejemplo de RN-21, hoy y mañana, apertura y día anterior, mes cerrado dentro de la ventana, enero, marzo en año común y bisiesto, ventanas de 0, 3 y 31 días, y el orden entre condiciones), `MovementServiceTest` (32, con un repositorio de movimientos en memoria que suma de verdad: el ejemplo de la historia, un pago que supera el presupuestado, cada error, otra cuenta de la misma moneda, id de otro usuario y que la partida no se modifica), `MovementControllerTest` (26, códigos y formato de errores); los tres de aislamiento de HU-06 pasan sin cambios. Frontend: textos, diálogo (36 con los textos), `canRegisterMovement` y la recarga y el foco en `BudgetMonth`; de punta a punta en `e2e/movimientos.spec.ts`.
- **Pendiente de integración**: no hay tests contra MySQL (sin Docker ni Testcontainers). Las consultas que usa (`findByUserIdAndEntryIdWithAccount`, `sumByEntry`, `sumByEntryOfPeriod`, `sumByAccountUpTo`, `findEntryIdsWithMovements`) las ejecutó sin error, con filas, la verificación con datos.
- **Criterios que se completan en otra historia**: el criterio 6 es solo un aviso de texto, sin botón: «Consolidar» llega con HU-23 (D-33). Los criterios propios de HU-20 (el 21/11 de diciembre, mes cerrado) están implementados acá porque son parte de RN-21 y probados con repositorios simulados; su prueba de punta a punta con el `Clock` real no se puede escribir hoy (la ventana de diciembre abre el 21/11) y queda en HU-20. `ENTRY_NOT_PENDING` y `PERIOD_CLOSED` con datos reales dependen de HU-23 y HU-33. Una partida cuya ventana no abrió no admite ninguna fecha (D-33).
- Verificado en el navegador con un usuario de prueba: registrar un pago que deja el pendiente en 0 (aviso en el mensaje y en el diálogo, estado Parcial, foco en la acción de la fila) y una fecha futura (error bajo el campo con la fecha de hoy, foco en el campo, lo cargado intacto).
- Verificación con datos (81 comprobaciones por la API con dos usuarios de prueba, todas coinciden): las del pedido (monto 0, negativo y con 3 decimales, nota de 201 caracteres, sin fecha, cuenta ajena o inexistente, otra moneda, fecha futura, el día anterior y el primer día de la ventana, el día anterior y el de apertura de la cuenta, una partida de un mes lejano, id de otro usuario y sin token); los saldos de HU-08; la vista del mes de HU-15; los efectos de un Concepto de HU-13; HU-16, HU-17, HU-18 y HU-07 (cada una marcada en sus notas).

### HU-20 · Registrar cobros anticipados

**Como** usuario **quiero** registrar un cobro antes de que empiece su mes **para** reflejar que los sueldos de diciembre se cobran a fines de noviembre.

1. La fecha puede ser hasta 10 días antes del primer día del período de la partida.
2. Ejemplo: sueldo de diciembre (2026-12). El 21/11 es válido (límite exacto), el 25/11 y el 30/11 también; el 20/11 responde 409 `DATE_OUT_OF_RANGE`.
3. El movimiento suma en el saldo de la cuenta por su fecha (noviembre), aunque la partida sea de diciembre.
4. Si noviembre ya está cerrado, un movimiento con fecha en noviembre se rechaza con 409 `PERIOD_CLOSED`, aunque esté dentro de la ventana.

Reglas: RN-21, RN-35, RN-41.

**Notas de implementación**

- Depende de **S-01** (la ventana vale también para gastos, sin confirmar), de D-16, D-17, D-33 y de las notas de HU-19: la ventana, la apertura y el mes cerrado ya estaban implementados y probados en `MovementDateValidator` y `MovementService` porque son parte de RN-21. No hay reglas nuevas ni migración. Agrega **D-34**. Se resolvieron con el usuario tres dudas antes de escribir código, todas por la opción recomendada: cómo se entera el usuario de la fecha más temprana (endpoint de solo lectura), qué sugiere el diálogo (hoy del backend; con la ventana sin abrir, aviso y botón deshabilitado) y que la vista del mes no marque nada.
- `GET /api/entries/{id}/movement-dates?accountId=` (`getMovementDates`) devuelve `{earliestDate, latestDate, earlyDays}`. `MovementDateValidator.earliestDate` es la más tardía entre el inicio de la ventana y la apertura de la cuenta, y `validate` usa la misma `windowStart`; un test parametrizado verifica que la fecha más temprana es exactamente la primera que valida.
- El mensaje de `DATE_OUT_OF_RANGE` por la ventana ya nombraba su primer día (HU-19). Ahora, si la cuenta se abrió después, agrega la fecha más temprana con esa cuenta.
- Frontend: `MovementDialog` toma del backend la fecha con la que arranca el campo (antes, la del navegador), muestra «Se puede fechar hasta N días antes del inicio de {mes}. Fecha más temprana con esta cuenta: {fecha}.» y la vuelve a pedir al cambiar de cuenta; con la ventana sin abrir muestra desde cuándo y no deja enviar. Textos en `movement-text.ts`.
- Tests: `MovementDateValidatorTest` (fecha más temprana: sueldo de diciembre, cruce de año, cuenta abierta después del inicio de la ventana, ventanas de 0, 3, 10 y 31, y que coincide con lo que valida), `MovementServiceTest` (rango, cuenta elegida, partida lejana, ventana configurable, mensajes, y que el mes cerrado dentro de la ventana sigue rechazado: criterio 4), `MovementControllerTest`, `AdvanceIncomeBalanceTest` (criterio 3: con el repositorio simulado, el sueldo cobrado el 25/11 está en el saldo al 25/11 y no en el del 24/11; el del 30/11, en el del 30/11 y no en el del 29/11; el del 02/12, no) y, en el frontend, el diálogo y los textos. De punta a punta en `e2e/movimientos.spec.ts`: con el reloj real no se puede usar diciembre, así que se usa el mes actual (el usuario de prueba nace con el período inicial dos meses antes), con todas las fechas calculadas desde hoy: el límite exacto es válido, el día anterior responde `DATE_OUT_OF_RANGE` y el saldo de `/cuentas` incluye el cobro.
- **Criterios que se completan en otra historia**: el criterio 4 con un mes **cerrado real** se verifica en HU-33 (hoy no hay cómo cerrar un mes: está probado con repositorios simulados). El caso real de los sueldos de diciembre se puede verificar a mano a partir del 21/11 (la ventana de diciembre abre ese día): registrar el cobro el 21/11 con fecha de ese día, y comprobar que el 20/11 era `DATE_OUT_OF_RANGE`.
- **Límite de lo que se puede verificar**: el saldo a una fecha arbitraria no se expone por la API, así que que el SQL excluya los movimientos posteriores a la fecha de corte sigue sin poder verificarse con datos reales hasta HU-30 a HU-34 (cierre y flujo de caja) o hasta que haya Docker y Testcontainers. El test de saldo por fecha prueba que el servicio pide el saldo a la fecha del reloj y arma el resultado con `BalanceCalculator`; el filtro por fecha lo hace el repositorio simulado. Con datos reales se verificó la inclusión (el cobro del mes anterior suma por su fecha).

### HU-21 · Corregir o eliminar un movimiento

**Como** usuario **quiero** corregir un movimiento mal cargado **para** que los números sean los reales.

1. Se editan fecha, monto, cuenta y nota, o se elimina el movimiento.
2. Solo si la partida está pendiente y el mes de la fecha actual del movimiento está abierto; los valores nuevos cumplen RN-21.
3. Al eliminar el último movimiento, la partida vuelve a Estimada.

Reglas: RN-22.

### HU-22 · Pago rápido

> Se implementa en la iteración 6, después de HU-25: el pago rápido consolida, y necesita la consolidación y la elección sobre partidas editadas (T-18).

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
| `GET /api/entries/{id}/movement-dates?accountId=` | Fecha más temprana, fecha más tardía (hoy) y ventana de anticipación que admite la partida (HU-20, D-34). |
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
