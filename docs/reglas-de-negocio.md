# Reglas de negocio

Cada regla tiene un ID estable (RN-xx) que citan las historias de usuario y los tests. Si una regla cambia, se edita acá y se registra en `decisiones.md`. Los IDs no se reutilizan.

## Parámetros

| Parámetro | Valor | Propiedad |
|---|---|---|
| Meses de horizonte | 24 | `app.budget.horizon-months` |
| Ventana de anticipación | 10 días | `app.budget.early-days` |
| Zona horaria de "hoy" | `America/Argentina/Mendoza` | `app.zone` |
| Vigencia del token | 8 horas | `app.security.jwt-expiration` |
| Largo mínimo de contraseña | 10 caracteres | `app.security.password-min-length` |

## 1. Generales

**RN-01. Aislamiento.** Toda lectura y escritura se limita al usuario autenticado, que se toma del token y nunca del cuerpo, la ruta ni la consulta. Un recurso de otro usuario responde 404, igual que uno inexistente. Cuando el recurso ajeno o inexistente no es el de la ruta sino una referencia dentro del cuerpo (por ejemplo, la cuenta por defecto de un Concepto), responde 400 `VALIDATION_ERROR` con el error en ese campo (D-24); tampoco así se distingue uno ajeno de uno inexistente. Los listados y totales incluyen solo datos del usuario.

**RN-02. Hoy.** "Hoy" es la fecha actual en la zona `America/Argentina/Mendoza`.

**RN-03. Montos.** Todos los montos tienen 2 decimales. Presupuestados y monto vigente: ≥ 0. Movimientos y transferencias: > 0. Saldos iniciales y reales: pueden ser negativos. El único cálculo que redondea es el promedio (RN-26), a 2 decimales con `HALF_UP`.

**RN-04. Monedas.** La moneda de una partida es la de su cuenta, y la de un Concepto, la de su cuenta por defecto. Montos de distinta moneda nunca se suman: todo total se informa por moneda.

## 2. Períodos y horizonte

**RN-05. Período.** Un período es un mes calendario, identificado como `YYYY-MM`. El período actual es el mes de hoy.

**RN-06. Rango de períodos.** Existen los períodos desde el período inicial del usuario hasta el horizonte (período actual + 24). Consultar un período fuera de ese rango responde 404. Usarlo como dato (por ejemplo, como inicio de un Concepto) responde 409 `PERIOD_NOT_AVAILABLE`.

**RN-07. Asegurar el horizonte.** Operación idempotente que crea, abiertos, los períodos que falten hasta el horizonte y luego ejecuta la generación (RN-13) de cada Concepto del usuario. Se ejecuta al crear el usuario, al iniciar sesión y al crear o editar un Concepto. Ejecutarla dos veces seguidas no cambia nada.

**RN-08. Primer período abierto.** Como los períodos se cierran en orden (RN-38), todos los posteriores al último cerrado están abiertos. El primer período abierto es el siguiente al último cerrado, o el período inicial si no hay ninguno cerrado.

**RN-09. Período cerrado.** Un período cerrado no admite cambios: no se crean, editan ni eliminan sus partidas, y no se crean, editan ni eliminan movimientos o transferencias con fecha dentro de ese mes. Cualquier intento responde 409 `PERIOD_CLOSED`. Un período cerrado no se reabre.

## 3. Conceptos

**RN-10. Creación de un Concepto.** Datos obligatorios: nombre, tipo, cuenta por defecto, periodicidad, día de vencimiento (1 a 31), desfase de mes (0 o −1), período de inicio, regla de estimación y monto vigente. Opcionales: categoría, período de fin (≥ inicio) y cuotas (RN-14; con cuotas el fin no se informa). El período de inicio debe estar entre el primer período abierto y el horizonte (si no, 409 `PERIOD_NOT_AVAILABLE`); el de fin puede superar el horizonte. La cuenta por defecto y la categoría van en el cuerpo: si no existen o son de otro usuario, responde 400 `VALIDATION_ERROR` con el error en su campo (D-24), igual que un fin anterior al inicio. El nombre puede repetirse. Al crearlo se asegura el horizonte (RN-07) y se generan sus partidas (RN-13).

**RN-11. Periodicidad.** El paso en meses es: Mensual 1, Bimestral 2, Trimestral 3, Semestral 6, Anual 12. Un Concepto tiene partida en los períodos `inicio + k × paso` (k = 0, 1, 2…) que no superen el período de fin.

> Ejemplo: un seguro semestral que empieza en 2026-11 tiene partidas en 2026-11, 2027-05, 2027-11, 2028-05…

**RN-12. Vencimiento.** Para una partida del período P, el mes de vencimiento es M = P + desfase, y el vencimiento es el día `min(día de vencimiento, último día de M)` del mes M.

| Concepto | Día | Desfase | Período | Vencimiento |
|---|---|---|---|---|
| Monotributo | 20 | 0 | 2026-10 | 2026-10-20 |
| Alquiler | 31 | 0 | 2026-11 | 2026-11-30 |
| Alquiler | 31 | 0 | 2027-02 | 2027-02-28 |
| Alquiler | 29 | 0 | 2028-02 | 2028-02-29 (bisiesto) |
| Sueldo A | 25 | −1 | 2026-12 | 2026-11-25 |
| Sueldo B | 30 | −1 | 2026-12 | 2026-11-30 |
| Sueldo B | 30 | −1 | 2027-03 | 2027-02-28 |
| Sueldo A | 25 | −1 | 2027-01 | 2026-12-25 (cambia el año) |

**RN-13. Generación.** Para cada período P tal que `generated_until < P ≤ min(horizonte, fin)` y que corresponda según la periodicidad (RN-11), se crea una partida con:

- origen `RECURRING`, tipo del Concepto y cuenta por defecto del Concepto;
- vencimiento según RN-12;
- presupuestado igual al monto vigente;
- no editada, pendiente;
- número de cuota, si el Concepto es en cuotas (RN-14).

Después, `generated_until = min(horizonte, fin)`. Con desfase −1, el vencimiento de la primera partida puede caer en un mes cerrado o anterior al período inicial: se permite, porque lo que un período cerrado bloquea son los movimientos con fecha en él (RN-09), no los vencimientos (S-20). Consecuencias:

- Nunca hay dos partidas del mismo Concepto en un período.
- Un período ya procesado no se vuelve a procesar, así que una partida eliminada no reaparece.
- Solo se generan partidas en períodos abiertos, porque el inicio es como mínimo el primer período abierto.

**RN-14. Cuotas.** Un Concepto en cuotas tiene un total n (≥ 1) y una primera cuota f (entre 1 y n; por defecto 1). La k-ésima partida (k = 0, 1, 2…) es la cuota f + k. El período de fin se calcula: `inicio + (n − f) × paso`. El monto de cada cuota es el monto vigente y se ajusta como en cualquier Concepto (RN-26).

> Ejemplo: "Heladera", 12 cuotas mensuales, primera cuota 4, inicio 2026-10. Genera las cuotas 4 a 12 en los períodos 2026-10 a 2027-06, y fin = 2027-06.

**RN-15. Edición de un Concepto.** Los cambios nunca tocan partidas consolidadas ni de períodos cerrados.

| Dato | ¿Editable? | Efecto |
|---|---|---|
| Nombre, categoría | Sí | Inmediato: las partidas recurrentes muestran los datos del Concepto. |
| Regla de estimación | Sí | Se aplica desde la próxima consolidación. |
| Día de vencimiento, desfase | Sí | Recalcula el vencimiento de las partidas pendientes de períodos abiertos. |
| Cuenta por defecto | Sí, a otra de la misma moneda | Cambia la cuenta de las partidas pendientes sin movimientos de períodos abiertos. |
| Monto vigente | Sí | Reemplaza el presupuestado de las partidas pendientes no editadas de períodos abiertos. Las editadas no cambian. |
| Tipo, periodicidad, inicio, fin, cuotas | No | 409 `FIELD_NOT_EDITABLE`. Para cambiarlos se da de baja (RN-31) y se crea otro Concepto. |

## 4. Partidas

**RN-16. Estado.** En la base, una partida está `PENDING` o `CONSOLIDATED`. Hacia afuera:

| Estado | Condición |
|---|---|
| Estimada (`ESTIMATED`) | Pendiente y sin movimientos. |
| Parcial (`PARTIAL`) | Pendiente y con al menos un movimiento. |
| Consolidada (`CONSOLIDATED`) | Consolidada. |

**RN-17. Montos derivados.**

- Real = suma de sus movimientos.
- Pendiente = 0 si está consolidada; si no, `max(presupuestado − real, 0)`.
- Estimado = monto consolidado si está consolidada; si no, real + pendiente (es decir, el mayor entre presupuestado y real).

> Ejemplo: Luz presupuestada en 45.000,00 con un pago de 20.000,00 → real 20.000,00, pendiente 25.000,00, estimado 45.000,00. Si en cambio se pagaron 50.000,00 → pendiente 0, estimado 50.000,00.

**RN-18. Editar una partida.** Solo partidas pendientes de períodos abiertos.

- En una partida recurrente se edita solo el presupuestado (≥ 0), y queda marcada como editada.
- En una partida sin Concepto se editan nombre, categoría, cuenta, vencimiento y presupuestado (con los límites de RN-19). No se marca como editada porque no se propaga.
- Editar nunca cambia otras partidas ni el monto vigente del Concepto.

**RN-19. Partidas sin Concepto.**

- Las **puntuales** las crea el usuario en un período abierto, con nombre, tipo, cuenta, vencimiento, presupuestado y categoría opcional.
- Las de **saldo postergado** y **diferencia de cierre** las crea el cierre del mes (RN-40).
- El vencimiento debe estar entre el primer día del mes anterior al período y el último día del período.
- Si la partida tiene movimientos, su cuenta solo puede cambiarse por otra de la misma moneda.
- Ninguna se copia a otros períodos.

**RN-20. Partida vencida.** Una partida pendiente con vencimiento anterior a hoy está vencida. Es solo un indicador visual.

## 5. Movimientos

**RN-21. Registrar un movimiento.** Condiciones:

1. La partida está pendiente y su período, abierto. Si no: `ENTRY_NOT_PENDING` o `PERIOD_CLOSED`.
2. El monto es mayor que 0.
3. La cuenta es del usuario y tiene la misma moneda que la partida, aunque no sea la cuenta prevista. Si no: `CURRENCY_MISMATCH`.
4. La fecha:
   - es igual o posterior al primer día del período de la partida menos la ventana de anticipación;
   - es igual o posterior a la fecha de apertura de la cuenta;
   - no es posterior a hoy;
   - cae en un mes que es un período abierto.

   Si no cumple alguna de las tres primeras: `DATE_OUT_OF_RANGE`. Si no cumple la última: `PERIOD_CLOSED`.

> Ejemplo, con ventana de 10 días: para el sueldo de diciembre (período 2026-12), la fecha más temprana es el 21/11. El 25/11 y el 30/11 son válidos; el 20/11 se rechaza.

**RN-22. Editar o eliminar un movimiento.** La partida debe estar pendiente, y el mes de la fecha actual del movimiento debe ser un período abierto. Al editar, los valores nuevos cumplen RN-21. Si se elimina el último movimiento, la partida vuelve a Estimada.

**RN-23. Registrar no consolida.** La partida queda pendiente aunque el real alcance o supere al presupuestado. La pantalla puede sugerir consolidar cuando el pendiente llega a 0.

**RN-24. Pago rápido.** Si el pendiente es mayor que 0, registra un movimiento por el pendiente (fecha de hoy salvo que se indique otra, cuenta de la partida salvo que se indique otra; RN-21) y consolida (RN-25), todo en una sola transacción. Acepta la misma política para partidas editadas que la consolidación (RN-26). Si el pendiente es 0: 409 `NOTHING_PENDING`.

## 6. Consolidación

**RN-25. Consolidar.** Condiciones: la partida está pendiente, su período está abierto y tiene al menos un movimiento (si no, `CONSOLIDATION_REQUIRES_MOVEMENT`). Efecto: monto consolidado = real, estado `CONSOLIDATED`, fecha de consolidación. Si la partida es recurrente, a continuación se propaga (RN-26).

**RN-26. Propagar.** Solo para partidas recurrentes. Pasos:

1. **Base**: partidas consolidadas del Concepto **sin resolución de cierre**, ordenadas por período de la más reciente a la más antigua.
2. **Estimación E**:
   - Último valor: el monto consolidado de la primera de la base.
   - Promedio de los últimos 3: el promedio de los montos consolidados de las 3 primeras de la base, o de las que haya si son menos. Se redondea a 2 decimales con `HALF_UP`.
3. **Monto vigente** del Concepto = E.
4. **Destino**: partidas del Concepto pendientes (Estimadas o Parciales), de períodos abiertos y con período posterior al de la primera de la base.
5. Las partidas de destino no editadas toman E como presupuestado. Para las editadas se aplica la política:
   - `KEEP` (Respetar): no cambian y siguen editadas.
   - `OVERWRITE` (Pisar): toman E y dejan de estar editadas.

   Si hay partidas editadas en el destino y no se indicó política, responde 409 `MANUAL_POLICY_REQUIRED` y no cambia nada (tampoco consolida).

Consecuencias:

- El resultado no depende del orden en que se consolidan las partidas.
- Las consolidaciones hechas durante el cierre del mes no cuentan como base (RN-40).
- En un Concepto en cuotas no hay partidas después de la última cuota, así que la propagación termina ahí.

> Ejemplo, último valor: Sueldo A del período 2026-12, presupuestado en 1.800.000,00, se cobra 1.850.000,00 el 25/11 y se consolida. El monto vigente pasa a 1.850.000,00, y todas las partidas pendientes no editadas desde 2027-01 hasta el horizonte pasan a 1.850.000,00.
>
> Ejemplo, promedio: Luz consolidada en 2026-07 por 50.000,00, en 2026-08 por 41.200,00, en 2026-09 por 38.750,00 y en 2026-10 por 45.310,00. E = (41.200,00 + 38.750,00 + 45.310,00) / 3 = 41.753,33. La de julio no entra.
>
> Ejemplo, partidas editadas: Resumen Visa con último valor. Diciembre fue editada a 300.000,00. Se consolida noviembre en 210.000,00. Con Respetar, diciembre queda en 300.000,00 y enero en adelante pasa a 210.000,00. Con Pisar, todas pasan a 210.000,00 y diciembre deja de estar editada.

**RN-27. Vista previa de la consolidación.** Sin modificar nada, informa: monto real, E, monto vigente actual, y cada partida de destino con su período, presupuestado actual, presupuestado nuevo y si está editada. Indica si hace falta elegir política.

**RN-28. Partidas sin Concepto.** Se consolidan con RN-25 y no propagan.

**RN-29. Desconsolidar.** Solo una partida consolidada de un período abierto. Vuelve a pendiente (queda Parcial, porque tiene movimientos). No revierte la propagación ni el monto vigente: la próxima consolidación del Concepto los vuelve a calcular.

## 7. Eliminación

**RN-30. Eliminar una partida sin Concepto.** Debe estar pendiente, sin movimientos y en un período abierto.

**RN-31. Eliminar una partida recurrente.** Se elige el alcance:

- **Solo este mes** (`ONLY_THIS`): la partida debe estar pendiente, sin movimientos y en un período abierto. Se elimina; el Concepto sigue igual y la partida no reaparece (RN-13).
- **Este mes y los siguientes** (`THIS_AND_FUTURE`), desde el período P de la partida: todas las partidas del Concepto con período ≥ P deben estar pendientes y sin movimientos. Si alguna no cumple, se rechaza todo con `ENTRY_NOT_PENDING` o `ENTRY_HAS_MOVEMENTS`, se informan las partidas que lo impiden y no se elimina nada. Si se cumple, se eliminan esas partidas y el período de fin del Concepto pasa a ser el mes anterior a P. Si el Concepto queda sin ninguna partida, también se elimina.

**RN-32. Lo consolidado no se elimina.** Ni las partidas consolidadas ni sus movimientos. Una partida con movimientos tampoco: primero se eliminan los movimientos o se consolida (`ENTRY_HAS_MOVEMENTS`).

## 8. Cuentas, categorías y transferencias

**RN-33. Cuentas.**

- Nombre único por usuario, sin distinguir mayúsculas (`ACCOUNT_NAME_TAKEN`).
- Fecha de apertura entre el primer día del período inicial del usuario y hoy, ambos inclusive; no puede ser futura. Fuera de ese rango: `VALIDATION_ERROR`. La pantalla sugiere el primer día del período inicial.
- Nombre y tipo se editan siempre.
- La moneda se edita solo si la cuenta no está referenciada por Conceptos, partidas, movimientos, transferencias ni cierres.
- El saldo inicial y la fecha de apertura se editan solo si la cuenta no tiene cierres, y la fecha no puede quedar después de su primer movimiento o transferencia.
- Se elimina solo si no está referenciada (`ACCOUNT_IN_USE`).
- Lo no editable responde `FIELD_NOT_EDITABLE`.

**RN-34. Categorías.** Nombre único por usuario, sin distinguir mayúsculas (`CATEGORY_NAME_TAKEN`). Se renombran libremente. Se eliminan solo si ningún Concepto ni partida las usa (`CATEGORY_IN_USE`).

**RN-35. Saldo de una cuenta.** El saldo al día D (D ≥ fecha de apertura) es:

```
saldo inicial
+ movimientos de partidas de ingreso con fecha ≤ D
− movimientos de partidas de gasto con fecha ≤ D
+ monto de destino de transferencias entrantes con fecha ≤ D
− monto de origen de transferencias salientes con fecha ≤ D
```

Cuenta la **fecha del movimiento**, no el período de su partida. El saldo actual es el saldo a hoy.

**RN-36. Transferencias.**

- Origen y destino son cuentas distintas del usuario (si son la misma: `VALIDATION_ERROR`).
- Ambos montos son mayores que 0. Si las dos cuentas tienen la misma moneda, los montos deben ser iguales (`TRANSFER_AMOUNTS_MISMATCH`).
- La fecha no es posterior a hoy, es igual o posterior a la apertura de ambas cuentas (`DATE_OUT_OF_RANGE`) y cae en un período abierto (`PERIOD_CLOSED`).
- Se editan o eliminan si el mes de su fecha actual es un período abierto; los valores nuevos cumplen las mismas condiciones.
- No son ingreso ni gasto: no aparecen en la vista del mes ni en sus totales.

**RN-37. Compra y venta de dólares.** Es una transferencia entre una cuenta en ARS y una en USD. El tipo de cambio es el monto en ARS dividido por el monto en USD, con 2 decimales, y solo se muestra.

> Compra: del banco en pesos salen 1.250.000,00 ARS y a la cuenta en dólares entran 1.000,00 USD → tipo de cambio 1.250,00.
> Venta: de la cuenta en dólares salen 500,00 USD y al banco entran 610.000,00 ARS → tipo de cambio 1.220,00.

## 9. Cierre de mes

**RN-38. Precondiciones.** Para cerrar el período P:

- P está abierto (`PERIOD_CLOSED`).
- El período anterior está cerrado, o P es el período inicial (`PREVIOUS_PERIOD_OPEN`).
- Hoy es igual o posterior al último día de P (`PERIOD_NOT_FINISHED`).

El período siguiente siempre existe, porque el horizonte lo garantiza.

**RN-39. Datos requeridos.**

- Una resolución (`CARRY_OVER` o `CLOSE_AS_IS`) para **cada** partida pendiente de P (`UNRESOLVED_PENDING_ENTRIES`).
- Un saldo real para **cada** cuenta con fecha de apertura ≤ último día de P (`MISSING_REAL_BALANCE`).
- Resoluciones para partidas que no son de P o que no están pendientes, o saldos para cuentas que no corresponden: `VALIDATION_ERROR`.

**RN-40. Efectos.** Todo ocurre en una sola transacción de base de datos:

1. **Postergar saldo** (`CARRY_OVER`): la partida se consolida con su real y queda con esa resolución. Si su pendiente era mayor que 0, se crea en el período siguiente una partida de saldo postergado con:
   - nombre "Saldo pendiente: <nombre>" (si ya era un saldo postergado, conserva su nombre);
   - tipo, cuenta y categoría de la original (en una recurrente, la categoría del Concepto);
   - vencimiento = vencimiento original + 1 mes, ajustado al último día del mes si hace falta;
   - presupuestado = pendiente;
   - referencia a la partida original.
2. **Cerrar con lo registrado** (`CLOSE_AS_IS`): la partida se consolida con su real (0 si no tuvo movimientos) y queda con esa resolución. El pendiente se descarta.
3. **Conciliación**: para cada cuenta se calcula el saldo al último día de P (RN-35) y se guarda un cierre de cuenta con saldo calculado, saldo real y diferencia (real − calculado). Si la diferencia no es 0, se crea en el período siguiente una partida de diferencia de cierre con:
   - nombre "Diferencia de cierre: <cuenta> (<mes abreviado> <año>)", por ejemplo "Diferencia de cierre: Banco (oct 2026)";
   - tipo ingreso si la diferencia es positiva y gasto si es negativa;
   - presupuestado = valor absoluto de la diferencia;
   - la cuenta conciliada, vencimiento el día 1 del período siguiente y referencia al cierre de cuenta.
4. El período pasa a cerrado, con su fecha de cierre.

Las consolidaciones de los pasos 1 y 2 **no propagan ni cuentan como base de estimación** (RN-26): una partida pagada a medias o dejada sin pagar no representa el valor normal del Concepto.

> Ejemplo: Luz de 2026-10 presupuestada en 45.000,00, con vencimiento el 18/10 y 20.000,00 pagados. Con Postergar saldo, queda consolidada en 20.000,00 y en 2026-11 aparece "Saldo pendiente: Luz" por 25.000,00 con vencimiento el 18/11. La estimación de la luz no cambia.
>
> Ejemplo: Banco con saldo calculado 512.300,00 y real 509.800,00 → diferencia −2.500,00 → en 2026-11 aparece un gasto "Diferencia de cierre: Banco (oct 2026)" por 2.500,00 con vencimiento el 01/11.

**RN-41. Saldos que cruzan meses.** El saldo calculado suma los movimientos por su fecha, sin importar el período de su partida. Los dos sueldos de diciembre cobrados el 25/11 y el 30/11 forman parte del saldo de fin de noviembre, que es lo que muestra el banco. Cerrar noviembre no exige consolidar las partidas de diciembre, pero sí bloquea los movimientos con fecha en noviembre, también los de esas partidas. Por eso los cobros anticipados tienen que cargarse antes de cerrar el mes en que ocurren.

**RN-42. Diferencia de cierre.** La partida de diferencia es una partida presupuestada más. Mientras no se consolide, el saldo calculado sigue arrastrando la diferencia; para corregirlo se registra un movimiento por su monto y se consolida. Si al cerrar el mes siguiente sigue pendiente, se resuelve como cualquier otra: con Cerrar con lo registrado, la diferencia vuelve a aparecer en la conciliación de ese mes. El saldo inicial del mes siguiente es el saldo calculado, no el real, para no contar la diferencia dos veces.

**RN-43. Simulación del cierre.** Sin modificar nada, devuelve:

- si se cumplen las precondiciones y, si no, cuál falla;
- las partidas pendientes, con real y pendiente;
- las cuentas que hay que conciliar, con su saldo calculado;
- si se enviaron resoluciones y saldos reales: las diferencias, las partidas que se crearían y los datos que faltan.

El cierre efectivo vuelve a hacer todas las validaciones.

## 10. Vistas

**RN-44. Vista del mes.** Muestra las partidas del período separadas en ingresos y gastos, ordenadas por vencimiento y nombre. Para cada partida: nombre, categoría, cuenta, vencimiento, cuota x de n, presupuestado, real, pendiente, estimado, estado, si está editada y si está vencida. Totales por moneda, para ingresos y para gastos: presupuestado, real, pendiente y estimado. Resultado por moneda = estimado de ingresos − estimado de gastos. Las transferencias no aparecen.

**RN-45. Flujo de caja.** Para un rango de fechas y una moneda, lista en orden cronológico:

- los movimientos de cuentas de esa moneda, por su fecha, con signo según el tipo de la partida;
- las transferencias que cambian el total de esa moneda (las de ARS a USD y viceversa); las transferencias entre cuentas de la misma moneda no cambian el total y no se listan;
- el pendiente de cada partida pendiente cuya cuenta es de esa moneda, en su vencimiento, o en hoy si ya venció.

Arranca con la suma de los saldos de las cuentas de esa moneda al día anterior al rango y muestra el saldo acumulado en cada fila.

**RN-46. Proyección de saldos.** Para cada mes M desde el actual hasta el horizonte, y para cada cuenta, el saldo proyectado al último día de M es:

```
saldo actual
+ pendiente de las partidas de ingreso pendientes de esa cuenta con fecha efectiva ≤ último día de M
− pendiente de las partidas de gasto pendientes de esa cuenta con fecha efectiva ≤ último día de M
```

La fecha efectiva es el vencimiento, o hoy si ya venció. Se informan subtotales por moneda. La proyección supone que cada pendiente se cobra o paga en la cuenta prevista de su partida.

## 11. Usuarios y acceso

**RN-47. Alta de usuario.** `POST /api/admin/users` con el header `X-Admin-Key`. La clave se compara en tiempo constante con la variable `APP_ADMIN_KEY`; si la variable no existe o está vacía, el endpoint rechaza siempre. No hay clave por defecto. Clave ausente o incorrecta: 401. Datos: email (se guarda en minúsculas y es único: `EMAIL_ALREADY_EXISTS`), contraseña inicial (RN-51) y período inicial (por defecto, el actual; no puede ser posterior al actual). El usuario queda habilitado, con cambio de contraseña obligatorio y con sus períodos creados (RN-07).

**RN-48. Restablecer contraseña.** `POST /api/admin/users/password-reset`, con la misma clave y las mismas respuestas ante una clave inválida. Recibe email y contraseña temporal; email inexistente: 404. Deja el cambio de contraseña obligatorio e invalida los tokens anteriores (RN-50).

**RN-49. Inicio de sesión.** Email (sin distinguir mayúsculas) y contraseña. Si son válidos y el usuario está habilitado, devuelve un JWT con el id del usuario y su versión de credenciales, su vencimiento (8 horas) y si debe cambiar la contraseña. Ante usuario inexistente, contraseña incorrecta o usuario deshabilitado responde 401 con el mismo mensaje genérico, sin revelar cuál fue la causa. Al iniciar sesión se asegura el horizonte (RN-07).

**RN-50. Sesión y cambio obligatorio.** En cada pedido autenticado se carga el usuario del token y se verifica que esté habilitado y que su versión de credenciales coincida con la del token; si no, 401. Si el usuario debe cambiar la contraseña, solo se permiten `POST /api/auth/change-password` y `GET /api/auth/me`; el resto responde 403 `PASSWORD_CHANGE_REQUIRED`. Cambiar o restablecer la contraseña incrementa la versión de credenciales, con lo que los tokens anteriores dejan de servir. El cambio de contraseña devuelve un token nuevo.

**RN-51. Contraseñas.** Se guardan con BCrypt. Mínimo 10 caracteres y máximo 72 bytes en UTF-8 (límite de BCrypt; más largas dan `VALIDATION_ERROR`). Para cambiarla se exige la contraseña actual (`INVALID_CURRENT_PASSWORD`) y la nueva debe ser distinta de la actual (`VALIDATION_ERROR`). Las contraseñas, los tokens y la clave de administración nunca se registran en logs ni se devuelven en respuestas.

## Códigos de error

Todas las respuestas de error usan el formato Problem Details (RFC 9457) con un campo `code` adicional. Cuando el error se debe a otras partidas, se agrega `entries` con sus ids; cuando es de validación de campos, `errors` con el detalle por campo.

```json
{
  "type": "about:blank",
  "title": "Período cerrado",
  "status": 409,
  "detail": "El período 2026-10 está cerrado y no admite cambios.",
  "code": "PERIOD_CLOSED"
}
```

| Código | HTTP | Cuándo |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Formato inválido, campos faltantes o fuera de rango, combinaciones imposibles, o una referencia del cuerpo a un recurso que no existe para el usuario. |
| `INVALID_CURRENT_PASSWORD` | 400 | La contraseña actual no coincide al cambiarla. |
| `UNAUTHORIZED` | 401 | Credenciales inválidas; token ausente, vencido o revocado; clave de administración inválida o no configurada. |
| `PASSWORD_CHANGE_REQUIRED` | 403 | El usuario debe cambiar la contraseña antes de seguir. |
| `NOT_FOUND` | 404 | El recurso no existe o es de otro usuario. |
| `METHOD_NOT_ALLOWED` | 405 | La ruta existe pero no admite ese método HTTP. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | El cuerpo no se envió como JSON. |
| `EMAIL_ALREADY_EXISTS` | 409 | Alta de usuario con un email ya registrado. |
| `ACCOUNT_NAME_TAKEN` | 409 | Ya existe una cuenta con ese nombre. |
| `CATEGORY_NAME_TAKEN` | 409 | Ya existe una categoría con ese nombre. |
| `ACCOUNT_IN_USE` | 409 | Eliminar una cuenta referenciada. |
| `CATEGORY_IN_USE` | 409 | Eliminar una categoría en uso. |
| `FIELD_NOT_EDITABLE` | 409 | Cambiar un dato que no se puede editar en ese estado. |
| `PERIOD_NOT_AVAILABLE` | 409 | Usar un período anterior al primer período abierto o posterior al horizonte. |
| `PERIOD_CLOSED` | 409 | Modificar algo de un período cerrado o con fecha en él. |
| `ENTRY_NOT_PENDING` | 409 | Operar como pendiente sobre una partida consolidada. |
| `ENTRY_HAS_MOVEMENTS` | 409 | Eliminar partidas que tienen movimientos. |
| `CURRENCY_MISMATCH` | 409 | Cuenta de distinta moneda que la partida o el Concepto. |
| `DATE_OUT_OF_RANGE` | 409 | Fecha antes de la ventana, antes de la apertura de la cuenta o futura. |
| `CONSOLIDATION_REQUIRES_MOVEMENT` | 409 | Consolidar una partida sin movimientos. |
| `MANUAL_POLICY_REQUIRED` | 409 | Consolidar con partidas editadas en el destino sin indicar política. |
| `NOTHING_PENDING` | 409 | Pago rápido sin pendiente. |
| `TRANSFER_AMOUNTS_MISMATCH` | 409 | Montos distintos en una transferencia de la misma moneda. |
| `PREVIOUS_PERIOD_OPEN` | 409 | Cerrar un mes con el anterior abierto. |
| `PERIOD_NOT_FINISHED` | 409 | Cerrar un mes antes de su último día. |
| `UNRESOLVED_PENDING_ENTRIES` | 409 | Cerrar sin resolver todas las partidas pendientes. |
| `MISSING_REAL_BALANCE` | 409 | Cerrar sin el saldo real de todas las cuentas. |
| `INTERNAL_ERROR` | 500 | Error inesperado. El `detail` es genérico y no expone datos internos. |
