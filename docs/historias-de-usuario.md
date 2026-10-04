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

Proyecto Spring Boot listo para crecer, según `backend/CLAUDE.md`.

1. Se crea con Spring Initializr (Maven, Java LTS) con las dependencias listadas en `backend/CLAUDE.md`.
2. La configuración sale de variables de entorno; sin `APP_JWT_SECRET` o sin datos de la base, la aplicación no arranca y lo dice claramente.
3. Existe un `Clock` con la zona configurada, un manejador global de errores que responde Problem Details con `code`, y el convertidor de `YearMonth`.
4. Swagger UI responde en `/swagger-ui.html` y `/v3/api-docs` devuelve el contrato.
5. `./mvnw test` pasa sin conectarse a la base.

### HT-02 · Esquema inicial

1. `V1__esquema_inicial.sql` crea las 9 tablas de `modelo-de-datos.md` con todas sus restricciones e índices.
2. Al arrancar, Flyway aplica la migración e Hibernate valida el esquema (`ddl-auto: validate`) sin errores.
3. Las entidades JPA mapean todas las columnas con los tipos de la tabla "Mapeo en Java".

### HT-03 · Esqueleto del frontend

Proyecto Angular según `frontend/CLAUDE.md`.

1. `npm start` levanta la aplicación con proxy de `/api` al backend.
2. `npm run generate:api` genera el cliente desde `../docs/openapi.json`.
3. Locale `es-AR` registrado: montos como `$ 1.234,50` y `US$ 1.234,50`, fechas como `dd/MM/yyyy`.
4. Estructura de carpetas, layout con menú lateral y ruteo vacío para las pantallas de la tabla de `frontend/CLAUDE.md`.
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

1. Alta con nombre, tipo, moneda, fecha de apertura y saldo inicial (puede ser negativo). La fecha de apertura sugerida es el primer día del período inicial y no puede ser anterior.
2. Nombre repetido, sin distinguir mayúsculas: 409 `ACCOUNT_NAME_TAKEN`.
3. Nombre y tipo se editan siempre. Moneda, saldo inicial y fecha de apertura, solo en las condiciones de RN-33; si no, 409 `FIELD_NOT_EDITABLE`.
4. Eliminar una cuenta referenciada: 409 `ACCOUNT_IN_USE`.
5. Pantalla: lista agrupada por moneda (con el saldo de HU-08) y formulario de alta y edición. Los campos no editables se ven deshabilitados con el motivo.

Reglas: RN-33.

### HU-08 · Ver el saldo actual de cada cuenta

**Como** usuario **quiero** ver cuánto tengo en cada cuenta **para** saber con qué cuento hoy.

1. La lista de cuentas muestra el saldo a hoy de cada una.
2. Muestra un subtotal por moneda y nunca un total que mezcle ARS y USD.
3. Ejemplo: saldo inicial 100.000,00 + ingreso 50.000,00 − gasto 20.000,00 − transferencia saliente 30.000,00 = 100.000,00.
4. Un sueldo cobrado por adelantado suma en el saldo desde la fecha del cobro, aunque su partida sea del mes siguiente.

Reglas: RN-04, RN-35.

### HU-09 · Administrar categorías

**Como** usuario **quiero** agrupar Conceptos y partidas en categorías **para** ordenar y filtrar.

1. Alta, cambio de nombre y eliminación.
2. Nombre repetido, sin distinguir mayúsculas: 409 `CATEGORY_NAME_TAKEN`.
3. Eliminar una categoría en uso: 409 `CATEGORY_IN_USE`.
4. La categoría es opcional en Conceptos y partidas puntuales.

Reglas: RN-34.

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

### HU-11 · Crear un Concepto en cuotas

**Como** usuario **quiero** cargar un plan de cuotas **para** ver "cuota x de n" y que deje de aparecer al terminar.

1. Además de los datos de HU-10: total de cuotas (≥ 1) y número de la primera cuota (entre 1 y el total; por defecto 1). El período de fin no se ingresa: se calcula.
2. Cada partida muestra "cuota x de n".
3. Ejemplo: Heladera, 12 cuotas, primera cuota 4, inicio 2026-10 → cuotas 4 a 12 en 2026-10 a 2027-06.
4. Aunque el horizonte avance, no se generan partidas después de la última cuota.

Reglas: RN-13, RN-14.

### HU-12 · Mantener el horizonte de 24 meses

**Como** usuario **quiero** tener siempre 24 meses hacia adelante **para** planificar sin cargar nada a mano.

1. Al iniciar sesión y al crear o editar un Concepto se asegura el horizonte.
2. Ejemplo: con horizonte en 2028-10, al iniciar sesión en noviembre de 2026 se crea el período 2028-11 y las partidas que correspondan, con el monto vigente de cada Concepto.
3. Una partida eliminada con "Solo este mes" no vuelve a aparecer.
4. Ejecutarlo dos veces seguidas no crea nada nuevo.

Reglas: RN-06, RN-07, RN-13.

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
