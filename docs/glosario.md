# Glosario

Vocabulario obligatorio del proyecto. En la interfaz y en la documentación se usa el **término**; en el código (clases, campos, tablas, columnas, endpoints y valores de enum) se usa el **código**. Si hace falta un concepto que no está acá, primero se agrega al glosario.

## Entidades

| Término | Código | Definición |
|---|---|---|
| Usuario | `User` · `app_user` | Persona que inicia sesión. Es dueña de todos sus datos y no ve los de nadie más. |
| Cuenta | `Account` · `account` | Donde está la plata: un banco, una billetera virtual o efectivo. Tiene una sola moneda. |
| Categoría | `Category` · `category` | Agrupación opcional para ordenar y filtrar: Impuestos, Servicios, Hogar. |
| Concepto | `BudgetItem` · `budget_item` | La regla de algo que se repite: "Sueldo", "Monotributo", "Resumen Visa", "Heladera en cuotas". Define tipo, cuenta, periodicidad, vencimiento, regla de estimación y monto vigente. |
| Período | `BudgetPeriod` · `budget_period` | Un mes calendario, identificado como `YYYY-MM` (`2026-10`). Está abierto o cerrado. |
| Partida | `BudgetEntry` · `budget_entry` | Lo que se espera cobrar o pagar en un período. Puede venir de un Concepto (como máximo una por Concepto y período) o existir solo en ese mes. Es lo que se ve en la pantalla del mes. |
| Movimiento | `Movement` · `movement` | Un cobro o pago real, con fecha, monto y cuenta. Siempre pertenece a una partida, y una partida puede tener varios (pagos parciales). |
| Transferencia | `Transfer` · `transfer` | Paso de plata entre dos cuentas propias. No es ingreso ni gasto. Entre cuentas de distinta moneda es una compra o venta de dólares. |
| Cierre de cuenta | `AccountClosing` · `account_closing` | Registro, al cerrar un mes, del saldo calculado y el saldo real de una cuenta, y de su diferencia. |

## Montos y datos

| Término | Código | Definición |
|---|---|---|
| Monto presupuestado | `budgetedAmount` | Lo que se estima cobrar o pagar en una partida. |
| Monto real | `actualAmount` | Suma de los movimientos de la partida. Se calcula, no se guarda. |
| Monto consolidado | `consolidatedAmount` | Monto real fijado al consolidar. |
| Pendiente | `pendingAmount` | Lo que falta: presupuestado menos real, nunca negativo. En una partida consolidada es 0. |
| Estimado | `forecastAmount` | Cuánto se espera que termine siendo la partida: real más pendiente. En una consolidada, el monto consolidado. |
| Resultado | `result` | En la vista del mes, estimado de ingresos menos estimado de gastos de una moneda. Puede ser negativo. |
| Monto vigente | `currentAmount` | Monto del Concepto que se usa al generar partidas nuevas. Se actualiza al consolidar. |
| Vencimiento | `dueDate` | Fecha prevista de cobro o pago de una partida. |
| Vencida | `overdue` | Partida pendiente cuyo vencimiento es anterior a hoy. Es solo un indicador (RN-20). |
| Día de vencimiento | `dueDay` | Día del mes (1 a 31) en que vence cada partida de un Concepto. |
| Desfase de mes | `dueMonthOffset` | 0 si vence en el mismo mes del período; −1 si vence el mes anterior (el sueldo de diciembre que se cobra en noviembre). |
| Cuota x de n | `installmentNumber` · `installmentsTotal` | Número de cuota de la partida y total de cuotas del Concepto. |
| Cuota actual | `currentInstallment` | En un Concepto en cuotas, la última cuota cuyo período es el actual o anterior. Se calcula con el calendario, no mira las partidas. |
| Cuotas que quedan | `installmentsRemaining` | Cuotas del plan posteriores a la cuota actual y anteriores o iguales a la última que sigue en el plan: normalmente, total menos cuota actual; si el plan se recortó al eliminar (RN-31), hasta la última que quedó. |
| Primera cuota | `firstInstallmentNumber` | Número de cuota que corresponde al período de inicio del Concepto. Permite cargar planes ya empezados. |
| Partida editada | `manual` | Partida cuyo monto presupuestado cambió el usuario a mano. La consolidación no la pisa sin preguntar. |
| Saldo inicial | `initialBalance` | Saldo de la cuenta al comienzo de su fecha de apertura. |
| Fecha de apertura | `openingDate` | Desde cuándo la aplicación lleva la cuenta. No se aceptan movimientos anteriores. |
| Saldo calculado | `computedBalance` | Saldo que surge de los datos: saldo inicial más movimientos y transferencias. |
| Saldo real | `realBalance` | Saldo que informa el usuario al cerrar el mes, mirando el banco o la billetera. |
| Diferencia | `difference` | Saldo real menos saldo calculado. |
| Tipo de cambio | `exchangeRate` | Pesos por dólar de una compra o venta. Se calcula a partir de los dos montos y no se guarda. |
| Período inicial | `startPeriod` | Primer período del usuario. No existen períodos anteriores. |
| Horizonte | `horizon` | Último período que existe: el actual más 24 meses (configurable). |
| Ventana de anticipación | `earlyDays` | Cuántos días antes del inicio de su período puede tener fecha un movimiento (10, configurable). |

## Tipos y estados

| Término | Código | Valores (código → pantalla) |
|---|---|---|
| Tipo de cuenta | `AccountType` | `BANK` → Banco · `DIGITAL_WALLET` → Billetera virtual · `CASH` → Efectivo |
| Moneda | `Currency` | `ARS` → $ · `USD` → US$ |
| Tipo | `EntryKind` | `INCOME` → Ingreso · `EXPENSE` → Gasto |
| Periodicidad | `Periodicity` | `MONTHLY` → Mensual · `BIMONTHLY` → Bimestral · `QUARTERLY` → Trimestral · `SEMIANNUAL` → Semestral · `ANNUAL` → Anual |
| Regla de estimación | `EstimationRule` | `LAST_VALUE` → Último valor · `AVERAGE_LAST_3` → Promedio de los últimos 3 |
| Origen de la partida | `EntryOrigin` | `RECURRING` → Recurrente · `ONE_OFF` → Puntual · `CARRIED_OVER` → Saldo postergado · `CLOSING_DIFFERENCE` → Diferencia de cierre |
| Estado de la partida | `EntryStatus` | `ESTIMATED` → Estimada · `PARTIAL` → Parcial · `CONSOLIDATED` → Consolidada |
| Estado guardado de la partida | `StoredEntryStatus` | `PENDING` → se muestra como Estimada o Parcial · `CONSOLIDATED` → Consolidada |
| Estado del Concepto | `BudgetItemStatus` | `ACTIVE` → Activo · `FINISHED` → Finalizado · `SCHEDULED` → Por comenzar |
| Estado del período | `PeriodStatus` | `OPEN` → Abierto · `CLOSED` → Cerrado |
| Resolución de cierre | `ClosingResolution` | `CARRY_OVER` → Postergar saldo · `CLOSE_AS_IS` → Cerrar con lo registrado |
| Partidas editadas al consolidar | `ManualEntriesPolicy` | `KEEP` → Respetar · `OVERWRITE` → Pisar |
| Alcance de la eliminación | `DeletionScope` | `ONLY_THIS` → Solo este mes · `THIS_AND_FUTURE` → Este mes y los siguientes |

En la base, el estado de la partida se guarda solo como `PENDING` o `CONSOLIDATED` (`StoredEntryStatus`). Estimada y Parcial se distinguen según tenga o no movimientos (RN-16).

## Acciones

| Término | Código | Qué hace |
|---|---|---|
| Generar | `generate` | Crea las partidas de un Concepto hasta el horizonte. |
| Registrar movimiento | `registerMovement` | Agrega un cobro o pago a una partida. No consolida. |
| Pago rápido | `quickSettle` | Registra un movimiento por todo el pendiente y consolida, en un solo paso. |
| Editar partida | `updateEntry` | Cambia los datos de una sola partida. Nunca se propaga. |
| Consolidar | `consolidate` | Da por terminada una partida con su monto real y propaga la estimación a las siguientes. Se aplica a partidas. |
| Desconsolidar | `unconsolidate` | Vuelve una partida consolidada a pendiente. |
| Propagar | `propagate` | Recalcula el presupuestado de las partidas futuras de un Concepto según su regla de estimación. |
| Postergar | `carryOver` | Al cerrar el mes, pasa el pendiente de una partida al mes siguiente. |
| Cerrar el mes | `closePeriod` | Verifica todo, compara saldos y bloquea el período para siempre. Se aplica a períodos. |
| Transferir | `transfer` | Mueve plata entre dos cuentas propias. |
| Eliminar partida | `deleteEntry` | Borra una partida. Si viene de un Concepto, pregunta el alcance. |

## Términos a evitar

| No usar | Usar | Motivo |
|---|---|---|
| Transacción · `Transaction` | Movimiento · `Movement` | `Transaction` choca con `@Transactional` y es palabra clave en SQL. |
| Ítem, rubro, gasto fijo | Concepto | Un Concepto puede ser ingreso o gasto, fijo o variable. |
| Línea, registro, renglón | Partida | |
| Cerrar una partida | Consolidar | "Cerrar" se reserva para el mes. |
| Consolidar el mes | Cerrar el mes | "Consolidar" se reserva para la partida. |
| Saldo de una partida | Pendiente | "Saldo" se reserva para las cuentas. |
| Cotización | Tipo de cambio | No se guarda ninguna cotización externa. |
| Billetera real | Efectivo | |
