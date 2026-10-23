# Decisiones

Registro del porqué de cada decisión. Cada entrada indica su estado:

- **Confirmada**: la definió Mario.
- **Supuesto**: se definió al redactar la documentación para cerrar un hueco. Vale hasta que Mario la confirme o la cambie.
- **Reemplazada**: quedó sin efecto; se conserva para entender la historia.

Cuando una decisión cambia, se actualiza acá y en el documento afectado en el mismo commit. Los IDs no se reutilizan.

## Producto

**D-01. Períodos mensuales con horizonte rodante.** Se presupuesta por mes calendario. La aplicación muestra el mes actual y los 24 siguientes, y el horizonte avanza solo. *Confirmada.*

**D-02. Cada usuario ve solo lo suyo.** No hay presupuestos compartidos entre usuarios. *Confirmada.*

**D-03. Web de escritorio primero.** La versión para celular viene después. *Confirmada.*

**D-04. Tarjeta de crédito como un Concepto de gasto.** Se presupuesta el resumen ("Resumen Visa") y se edita mes a mes. No se registran las compras individuales ni sus cuotas. *Confirmada.*

**D-05. Dos monedas, sin conversión.** Cada cuenta es en ARS o en USD. Los totales se muestran separados por moneda y nunca se suman entre sí. *Confirmada.*

**D-06. Comprar o vender dólares es una transferencia.** Se registra entre una cuenta en pesos y una en dólares, con los dos montos. El tipo de cambio se calcula dividiendo uno por otro y no se guarda. *Confirmada.*

**D-07. Alta de usuarios solo por endpoint de administración.** No hay registro público. El endpoint se protege con una clave especial enviada en un header y el usuario nuevo debe cambiar la contraseña en su primer ingreso. *Confirmada.*

## Modelo y reglas

**D-08. Concepto y Partida separados.** El Concepto es la regla que se repite; la Partida es lo que pasa en un mes. Sin esa separación, copiar hacia adelante genera duplicados difíciles de mantener. *Confirmada.*

**D-09. Partidas materializadas.** Las partidas de los 24 meses existen como filas. Son más simples de consultar y de editar mes a mes. El Concepto recuerda hasta qué período ya generó (`generated_until`), así una partida eliminada no vuelve a aparecer. *Confirmada.*

**D-10. Regla de estimación por Concepto.** Cada Concepto elige entre copiar el último valor consolidado o promediar los últimos 3. Los sueldos y la mayoría de los gastos usan el último valor; los servicios que varían por consumo, el promedio. No hay ajuste automático por inflación. *Confirmada.*

**D-11. Editar no propaga; consolidar sí.** Cambiar el monto de una partida afecta solo a esa partida. Lo único que actualiza los meses siguientes es consolidar. *Confirmada.*

**D-12. Elección ante partidas editadas.** Si al consolidar hay partidas futuras editadas a mano, el usuario elige respetarlas o pisarlas, con una sola elección para todas. *Confirmada.*

**D-13. Consolidar es una acción explícita.** Registrar movimientos no consolida, porque el sistema no puede saber si falta cobrar o pagar algo más. *Confirmada.*

**D-14. Periodicidades.** Mensual, bimestral, trimestral, semestral y anual. *Confirmada.*

**D-15. Vencimiento por día fijo.** Si el día no existe en el mes (29, 30 o 31), se usa el último día del mes. No se consideran días hábiles ni feriados. *Confirmada.*

**D-16. Desfase de mes en el vencimiento.** Un Concepto puede vencer el mes anterior al período al que pertenece. Caso real: los dos sueldos de diciembre se cobran el 25/11 y el 30/11, pero se computan en diciembre. *Confirmada.*

**D-17. Ventana de anticipación de 10 días.** Un movimiento puede tener fecha hasta 10 días antes del inicio del período de su partida. El valor es global y configurable. *Confirmada para ingresos. Que valga también para gastos es un supuesto (S-01).*

**D-18. Cuotas.** El monto es inicialmente fijo, pero se ajusta como el de cualquier Concepto. Las partidas se generan hasta la última cuota y después se dejan de generar. *Confirmada.*

**D-19. Baja con alcance.** Al eliminar la partida de un Concepto, el sistema pregunta si es solo para ese mes o para ese mes y los siguientes. *Confirmada.*

**D-20. Transferencias.** Restan en la cuenta de origen y suman en la de destino. No son ingreso ni gasto. *Confirmada.*

**D-21. Cierre de mes.** Para cerrar, todas las partidas del mes deben quedar consolidadas. Las que siguen pendientes se resuelven una por una, eligiendo entre postergar el saldo al mes siguiente o cerrar con lo registrado. *Confirmada.*

**D-22. Conciliación de saldos al cerrar.** Por cada cuenta se compara el saldo calculado con el real. Cada diferencia genera, en el mes siguiente, una partida presupuestada "Diferencia de cierre" que se consolida después. *Confirmada.* Consecuencia: el saldo inicial del mes siguiente es el calculado, no el real; si fuera el real, la diferencia se contaría dos veces.

**D-23. Un período cerrado no se reabre.** *Confirmada.*

**D-24. Una referencia inválida en el cuerpo responde 400, no 404.** Si un pedido nombra en su cuerpo un recurso que no existe o es de otro usuario (la cuenta por defecto o la categoría de un Concepto), responde `VALIDATION_ERROR` con el error en ese campo. El 404 de RN-01 queda para el recurso de la ruta. En los dos casos, uno ajeno y uno inexistente responden igual. *Confirmada* (HU-10).

**D-25. Un plan tiene como máximo 360 cuotas.** El tope de `SMALLINT` (32.767) no alcanza como límite: un plan anual con muchas cuotas terminaría después del año 9999 y su fin no entraría en `end_period CHAR(7)`. 360 cubre un crédito hipotecario a 30 años en cuotas mensuales, y el fin de un plan anual de 360 cuotas cae cerca del año 2385. Más cuotas responden 400 `VALIDATION_ERROR` en `installmentsTotal`. El tope lo valida la API; la base solo exige `installments_total >= 1`. *Confirmada* (HU-11).

**D-26. Los errores de cuotas se informan en el campo que corresponde.** Primera cuota sin total: `installmentsTotal`. Primera cuota mayor que el total: `firstInstallmentNumber`. Cuotas junto con un período de fin: `endPeriod`, aunque coincida con el calculado (el fin de un plan no se ingresa). Los tres, con la forma de D-24. *Confirmada* (HU-11).

**D-27. El estado de un Concepto es `ACTIVE`, `FINISHED` o `SCHEDULED`, y las cuotas son datos aparte.** Con el período actual C (del `Clock`): `FINISHED` si el fin es anterior a C; `SCHEDULED` si el inicio es posterior a C; `ACTIVE` en los demás casos. En un Concepto en cuotas, el fin guardado es el calculado (RN-14). Si está `ACTIVE`, trae `currentInstallment` (primera cuota + pasos transcurridos desde el inicio, según la periodicidad) e `installmentsRemaining` (total menos cuota actual): en la última cuota quedan 0. Finalizado y Por comenzar no los traen. El cálculo es por calendario y no mira las partidas: así sirve con un plan cortado por el horizonte, antes de poder consolidar (HU-23) y con partidas eliminadas con «Solo este mes» (HU-18). **Plan recortado (HU-18)**: «Este mes y los siguientes» fija un fin anterior al calculado; entonces las cuotas que quedan llegan hasta la última que sigue en el plan (`primera + (fin − inicio) / paso`) y no hasta el total, que no cambia (es no editable, RN-15). Sin recortar, las dos fórmulas dan lo mismo (RN-14). Ejemplo: Heladera recortada desde 2027-01 (fin 2026-12) es «Cuota 4 de 12, quedan 2» en octubre de 2026 y «Cuota 6 de 12, última» en diciembre. Ejemplo: Heladera (12 cuotas, primera 4, inicio 2026-10) es "Cuota 4 de 12, quedan 8" en octubre de 2026 y "Cuota 5 de 12, quedan 7" en noviembre. *Confirmada* (HU-14).

**D-28. La lista de Conceptos y sus filtros.** Sin paginación ni totales de montos (periodicidades y monedas distintas). Orden: los Finalizados al final y, dentro de cada grupo, por nombre sin distinguir mayúsculas. Filtros opcionales `kind`, y `categoryId` o `withoutCategory=true` (juntos, 400 `VALIDATION_ERROR`). Una categoría inexistente o de otro usuario en el filtro responde 404 `NOT_FOUND` (RN-01); D-24 solo rige para referencias del cuerpo. Los filtros se aplican en el servicio sobre los Conceptos del usuario. *Confirmada* (HU-14).

**D-29. La vista del mes.** *Confirmada* (HU-15).

- **Endpoints**: `GET /api/periods/{period}` y `GET /api/periods/current`, que devuelve la misma vista para el período actual según el `Clock` (T-13); así el frontend no decide cuál es el mes actual. La respuesta trae el período inicial, el actual y el horizonte, con los que la pantalla arma los límites de la navegación. `GET /api/periods?from=&to=` con totales por moneda no se implementa en HU-15: lo necesita la vista de varios meses (HU-36).
- **Rango según lo que existe**: un período sin fila responde 404, y el horizonte informado es el último período existente del usuario, no `actual + 24` calculado. Solo difieren si el mes cambia con la sesión abierta, hasta el próximo inicio de sesión (RN-07). Una lectura no crea períodos.
- **Orden**: vencimiento, nombre sin distinguir mayúsculas (como D-28) y, como los nombres pueden repetirse (S-19), orden de creación. Las monedas se mezclan dentro de cada sección.
- **Totales**: solo las monedas con partidas en el período. Con un solo lado, el otro vale 0: el pie de la sección sin partidas de esa moneda no muestra su fila, y el bloque de resultado muestra todas las monedas del período. Un mes vacío no muestra totales ni resultado, para no inventar una moneda.
- **Vencida** (RN-20): se decide por el estado pendiente, sin mirar el monto. Una partida con pendiente 0 sin consolidar figura vencida; la marca empuja a consolidar.

**D-30. Partidas sin Concepto: alta y edición.** *Confirmada* (HU-16).

- **Endpoints**: `POST /api/periods/{period}/entries` crea una partida `ONE_OFF` y `PATCH /api/entries/{id}` edita una sin Concepto (también las de saldo postergado y diferencia de cierre, cuando existan). Los dos responden con la partida en la forma de la vista del mes. No agregan nada a otros períodos ni tocan Conceptos.
- **El vencimiento no se compara con la fecha de apertura de la cuenta**, igual que en los Conceptos (S-21, S-25): la apertura se controla al registrar cada movimiento (RN-21). El rango de RN-19 es el único límite.
- **Un vencimiento anterior a hoy es válido**: RN-19 solo fija el rango. La partida nace vencida (RN-20), como las recurrentes con desfase −1 (S-20).
- **Sin movimientos, la cuenta puede cambiar a una de otra moneda**: es la lectura literal de RN-19, que solo restringe cuando hay movimientos (los movimientos de una partida tienen su moneda, S-02). El monto se conserva como número, ahora en la otra moneda, y el diálogo lo avisa. Con movimientos: 409 `CURRENCY_MISMATCH`.
- **El tipo no se edita**, como en los Conceptos (RN-15). Un tipo distinto responde 409 `FIELD_NOT_EDITABLE`; enviar el mismo no es un cambio. Cambiarlo con movimientos invertiría su efecto en los saldos (RN-35), y en una diferencia de cierre cambiaría su sentido. Para cambiarlo se elimina la partida (HU-18) y se crea otra.
- **El período de una partida no se edita**: no está en RN-18. Para pasarla a otro mes se elimina y se crea otra.
- **El PATCH cambia solo lo que se envía**: un campo omitido o `null` significa «no cambia»; para vaciar la categoría se envía `clearCategory: true` (con `categoryId` a la vez, 400 en `clearCategory`). Se descartó `Optional` porque Jackson 3 deserializa igual un campo ausente y un `null` (verificado), y un envoltorio propio habría sacado el nombre y el monto de Bean Validation. Un cuerpo sin cambios, o con los mismos valores, responde 200 sin cambiar nada.
- **Una recurrente no se edita por este endpoint**: cualquier cambio responde 409 `FIELD_NOT_EDITABLE` («se editan en su Concepto»). HU-17 levantó esa restricción solo para `budgetedAmount`, que la marca editada, sin cambiar el contrato (D-31).
- **Orden de evaluación del alta**: token (401); formato del período y del cuerpo (400); período inexistente (404, RN-06); período cerrado (409 `PERIOD_CLOSED`); vencimiento fuera de rango (400 en `dueDate`); cuenta y categoría que no existen o son de otro usuario (400 en `accountId` y `categoryId`, D-24). **De la edición**: token; formato del cuerpo y categoría con `clearCategory` (400); partida inexistente o ajena (404); `PERIOD_CLOSED`; `ENTRY_NOT_PENDING`; dato no editable (409 `FIELD_NOT_EDITABLE`); vencimiento, cuenta y categoría (400); y, con movimientos, la moneda (409 `CURRENCY_MISMATCH`). `PERIOD_CLOSED` va antes de `ENTRY_NOT_PENDING` porque en un período cerrado todas las partidas están consolidadas y el motivo útil es el mes.
- **Un pedido rechazado no cambia nada.**

**D-31. Editar el monto de una partida recurrente.** *Confirmada* (HU-17).

- **Mismo monto, ningún cambio**: enviar el presupuestado que la partida ya tiene responde 200 sin marcarla (coherente con D-30: un pedido con los mismos valores no cambia nada).
- **La marca es «fijado a mano», no «distinto del vigente»**: una partida editada que vuelve a quedar en el monto vigente del Concepto sigue editada. No hay forma de quitar la marca salvo «Pisar» al consolidar (RN-26); un «Restablecer monto» sería una funcionalidad nueva, fuera de HU-17.
- **Cuerpo mixto**: el presupuestado junto con otro dato de una recurrente que cambia es 409 `FIELD_NOT_EDITABLE` y no cambia nada.
- **Parciales**: se editan, con el pendiente recalculado (S-11, RN-17). Sin aviso del backend si el monto queda por debajo de lo registrado; el diálogo lo avisa.
- **Sin contrato nuevo**: el cuerpo y la respuesta del PATCH no cambian; el frontend usa el mismo `PATCH /api/entries/{id}` con solo `budgetedAmount`.

**D-32. Eliminar una partida.** *Confirmada* (HU-18).

- **Alcance obligatorio y sin valor por defecto**: una recurrente sin `scope` es 400 `VALIDATION_ERROR` en `scope`; una partida sin Concepto con `scope` (cualquiera) también. Solo este mes parece inofensivo pero deja un hueco permanente, y D-19 pide que se pregunte. Un `scope` con un valor que no existe es 400 sin detalle por campo.
- **Solo este mes sobre la última partida**: el Concepto también se elimina si no le queda ninguna partida y no tiene nada por generar (fin definido y ya generado). Sin eso quedaría en la lista, sin forma de darlo de baja (no hay endpoint propio) y bloqueando su cuenta y su categoría (`ACCOUNT_IN_USE`, `CATEGORY_IN_USE`). Un Concepto que todavía puede generar sigue igual: «Solo este mes» no es una baja. Se descartó eliminarlo siempre que quede sin partidas, que convertiría «Solo este mes» en una baja total de un Concepto abierto.
- **Plan de cuotas recortado**: «Este mes y los siguientes» fija un fin anterior al calculado y el total no cambia. D-27 se ajusta: las cuotas que quedan llegan hasta la última que sigue en el plan, no hasta el total (ver D-27).
- **Código con consolidadas y movimientos a la vez**: `ENTRY_NOT_PENDING` si alguna del alcance está consolidada (siempre tiene movimientos, RN-25), si no `ENTRY_HAS_MOVEMENTS`. `entries` lista todas las que impiden. Coherente con la edición, que revisa primero la consolidación (D-30). `PERIOD_CLOSED` va antes.
- **Vista previa**: `GET /api/entries/{id}/deletion-preview`, de solo lectura, que usa la misma regla pura (`EntryDeletionPlanner`) que la eliminación. Así el diálogo muestra cuántas partidas se eliminan, desde qué mes y qué pasa con el Concepto, y lista los impedimentos por mes y motivo antes de confirmar; si el backend rechaza igual, vuelve a pedirla. El 409 sigue trayendo solo los ids en `entries`.
- **Partidas de saldo postergado y de diferencia de cierre** se eliminan como cualquier partida sin Concepto (RN-30 no las excluye). Eliminar un saldo postergado equivale a haber cerrado con lo registrado (la original ya está consolidada en un período cerrado y no cambia). Eliminar una diferencia de cierre no la pierde: los saldos salen de los movimientos (RN-35) y la conciliación del próximo cierre la vuelve a encontrar (RN-42).
- **Las Parciales ofrecen «Eliminar»**: «pendiente» incluye Parcial (S-11). El backend las rechaza por tener movimientos (RN-32) y el diálogo lo explica con la vista previa; el botón no se oculta para que el motivo sea visible.
- **Un solo borrado, en una transacción**: las partidas del alcance se eliminan con una sola sentencia y después, si corresponde, el Concepto (las claves foráneas son `RESTRICT`). `generated_until` no se toca nunca.
- **Riesgo conocido, sin resolver** (igual que HU-12): un movimiento registrado entre la lectura y el borrado chocaría con la clave foránea y daría error 500, sin cambiar nada. Se acepta por el uso de a un usuario por vez.

**D-33. Registrar un movimiento.** *Confirmada* (HU-19) en el orden de evaluación, el mes sin período y el aviso de pendiente 0, resueltos con el usuario antes de escribir código. El resto son decisiones de implementación que se presentaron en el plan y no tuvieron objeciones.

- **Endpoints**: `POST /api/entries/{id}/movements` responde 201 con `{movement, entry}`: el movimiento y la partida con sus valores derivados ya actualizados (real, pendiente, estimado y estado). Así el aviso de pendiente 0 sale del backend y el frontend no suma nada. `GET /api/entries/{id}/movements` lista los movimientos por fecha y orden de registro, con su cuenta; se pueden leer también los de una partida consolidada o de un período cerrado.
- **Orden de evaluación** (se informa el primero que falla): token (401); formato del cuerpo (400); partida inexistente o ajena (404); período de la partida cerrado (409 `PERIOD_CLOSED`); partida consolidada (409 `ENTRY_NOT_PENDING`); cuenta que no existe o es de otro usuario (400 en `accountId`, D-24); moneda (409 `CURRENCY_MISMATCH`); y la fecha: ventana, apertura y hoy (409 `DATE_OUT_OF_RANGE`) antes que el mes de la fecha (409 `PERIOD_CLOSED`). `PERIOD_CLOSED` va antes de `ENTRY_NOT_PENDING` por el mismo motivo que en D-30; en la fecha rige la letra de RN-21 (las tres primeras antes que la última).
- **Un mes sin período** (anterior al período inicial) responde `DATE_OUT_OF_RANGE`: no está cerrado, está fuera del rango de períodos que existen (RN-06). Por la API no se produce (la apertura de la cuenta lo cubre antes, RN-33), pero la regla pura `MovementDateValidator` lo decide.
- **Una partida lejana no admite movimientos**: si la ventana de su período abre después de hoy, ninguna fecha cumple la ventana y «no futura» a la vez (S-09). Responde 409 `DATE_OUT_OF_RANGE` con un `detail` que dice desde cuándo. La pantalla ofrece la acción igual: las reglas las decide el backend.
- **Se puede registrar con el pendiente en 0**: un pago que supera el presupuestado es válido (RN-17) y no cambia el presupuestado (RN-23). Un monto que no es mayor que 0 o tiene más de 2 decimales es `VALIDATION_ERROR` en `amount` (RN-03; RN-21 no le da código propio).
- **La nota** se guarda sin espacios en los extremos; en blanco, sin nota. Máximo 200 caracteres.
- **El aviso de «pendiente 0»** (criterio 6) es un texto informativo, sin botón, hasta que exista la consolidación (HU-23): «ya está cubierta: pendiente $ 0,00. Sigue Parcial hasta que se consolide». Sale al guardar y al abrir el diálogo de una Parcial con pendiente 0. HU-23 le suma la acción «Consolidar».
- **El diálogo** arranca con hoy según el backend (D-34; en HU-19 era la fecha del navegador, solo como sugerencia), la cuenta de la partida y, si queda pendiente, ese monto. Solo ofrece cuentas de la moneda de la partida (S-02).
- **Un pedido rechazado no guarda nada.**

**D-34. Fechas que admite una partida (cobros anticipados).** *Confirmada* (HU-20): las tres dudas se resolvieron con el usuario antes de escribir código, todas por la opción recomendada.

- **El backend informa el rango**: `GET /api/entries/{id}/movement-dates?accountId=` (`getMovementDates`) responde `{earliestDate, latestDate, earlyDays}`. `earliestDate` es la más tardía entre el primer día del período de la partida menos la ventana y la apertura de la cuenta, y sale de la misma regla pura que valida (`MovementDateValidator.earliestDate`, que comparte con `validate` el inicio de la ventana); `latestDate` es hoy según el `Clock`. Sin `accountId` se usa la cuenta prevista de la partida; con él, esa cuenta (S-02: otra cuenta de la misma moneda puede tener una apertura posterior). Si `earliestDate` es posterior a `latestDate`, la partida todavía no admite movimientos. Es solo lectura: no mira el estado de la partida ni de los meses, que decide el registro. Un `accountId` que no existe es 400 en ese campo (D-24).
- **Por qué un endpoint y no un campo de la partida**: la fecha más temprana depende de la cuenta elegida en el diálogo, y la ventana configurable no tenía lugar en la fila del mes sin agregar campos a toda la vista. No cambia ninguna respuesta existente.
- **El diálogo** sugiere `latestDate` (hoy del backend, no del navegador), explica en una línea la ventana con `earlyDays` y la fecha más temprana, y la vuelve a pedir al cambiar de cuenta. Si la ventana no abrió, avisa desde cuándo se admiten movimientos y deshabilita el botón (en HU-19 se dejaba enviar y fallaba). No repite ninguna regla: solo compara las dos fechas que informa el backend.
- **El mensaje de `DATE_OUT_OF_RANGE`** por la ventana nombra su primer día; si la cuenta se abrió después, agrega la fecha más temprana con esa cuenta, para que corregir la fecha no lleve a un segundo error. La validación no cambia.
- **La vista del mes no señala** las partidas del mes siguiente que ya se pueden cobrar o pagar por anticipado (decisión del usuario); el aviso vive solo en el diálogo.

**D-35. Corregir o eliminar un movimiento.** *Confirmada* (HU-21): las cuatro dudas se resolvieron con el usuario antes de escribir código, todas por la opción recomendada.

- **Endpoints**: `PUT /api/movements/{id}` (`updateMovement`) responde 200 con `{movement, entry}`, igual que el alta, con la partida y sus valores derivados ya actualizados. `DELETE /api/movements/{id}` (`deleteMovement`) responde 204. Un movimiento de otro usuario o inexistente es 404 (RN-01). El cuerpo del `PUT` es el mismo `MovementRequest` del alta.
- **Orden de evaluación** (se informa el primero que falla): token (401); formato del cuerpo (400); movimiento inexistente o ajeno (404); período de la partida cerrado (409 `PERIOD_CLOSED`); partida consolidada (409 `ENTRY_NOT_PENDING`); mes de la fecha actual del movimiento cerrado (409 `PERIOD_CLOSED`); cuenta inexistente o ajena (400 en `accountId`, D-24); moneda (409 `CURRENCY_MISMATCH`); y la fecha nueva, con la misma regla pura del alta: ventana, apertura y hoy (409 `DATE_OUT_OF_RANGE`) antes que el mes (409 `PERIOD_CLOSED`). Primero el estado actual (RN-22) y después los valores nuevos (RN-21), como D-33.
- **Sin cambios** es válido: 200 con el mismo movimiento y sin guardar nada.
- **La fecha se revalida solo si cambian la fecha o la cuenta.** Con la ventana configurable (D-17), si se achica, un movimiento que era válido quedaría fuera de ella y no se podría corregir ni su nota. Con la fecha y la cuenta iguales, el monto y la nota se corrigen sin mirar la ventana. El monto sigue siendo mayor que 0 y con 2 decimales (formato, RN-03).
- **La partida nunca se modifica**: ni al editar ni al eliminar. Al quedar sin movimientos, vuelve a mostrarse Estimada porque ya no tiene (RN-16).
- **Movimiento congelado**: uno con fecha en un mes cerrado no se edita ni se elimina, aunque su partida siga pendiente en un período abierto. Es la lectura literal de RN-22. Consecuencia: esa partida no se puede eliminar (S-08) y solo sale consolidándola. No hay campo `editable` en la respuesta: el backend informa el motivo al intentarlo. Hoy no se puede producir de punta a punta, porque no hay cierre de mes hasta HU-33.
- **`GET /api/entries/{id}/movement-dates` sirve tal cual** al diálogo de edición: depende de la partida y de la cuenta elegida, no del movimiento.
- **RN-33** sigue bien: «tiene movimientos», la fecha del primer movimiento de una cuenta y los saldos se consultan en vivo en la tabla `movement`; no hay nada guardado que corregir al editar o eliminar.
- **Frontend**: el diálogo ofrece «Editar» y «Eliminar» por fila solo con la partida pendiente en un período abierto. Editar reutiliza el formulario del alta con los valores del movimiento. Eliminar pide confirmar mostrando fecha, monto y cuenta. Después de cada cambio se pide de nuevo la lista y la vista del mes.

**D-36. Consolidar una partida.** *Confirmada* (HU-23): las cuatro dudas se resolvieron con el usuario antes de escribir código, todas por la opción recomendada. Depende de **S-03 a S-06**, **sin confirmar**.

- **Alcance entre historias**: RN-26 es una sola regla y el backend la implementa completa en HU-23 (las dos reglas de estimación y la política para editadas), como RN-21 en HU-19. HU-24 y HU-25 completan sus criterios, su pantalla y su prueba de punta a punta. La pantalla de HU-23 cubre el caso sin editadas en el destino; la elección «Respetar» o «Pisar» la agregó HU-25.
- **Endpoints**: `GET /api/entries/{id}/consolidation-preview` (`getConsolidationPreview`) y `POST /api/entries/{id}/consolidate` (`consolidateEntry`, cuerpo opcional con `manualEntriesPolicy`), que responde 200 con `{entry, estimate, updatedEntries}`.
- **La vista previa de una partida que no se puede consolidar** responde 200 con `allowed: false`, `blockedBy` (`PERIOD_CLOSED`, `ENTRY_NOT_PENDING` o `CONSOLIDATION_REQUIRES_MOVEMENT`) y `blockedDetail`, como la vista previa de la eliminación (D-32). El `POST` responde el 409 correspondiente. Un id ajeno o inexistente es 404 en los dos.
- **Una sola fuente**: la vista previa y la consolidación salen de `EstimationCalculator`; la vista previa supone la partida ya consolidada con su real. No hay un cálculo aparte.
- **Si E es igual a lo que ya había**, el monto vigente se fija igual (no cambia nada guardado). La vista previa lista todo el destino y cada partida trae `changes`, calculado por el backend, para que el frontend no compare montos. Una editada cuyo monto ya es E igual exige política, porque Pisar le quita la marca (D-31); para ella `changes` vale solo con `OVERWRITE`.
- **Orden de evaluación** (se informa el primero que falla): token (401); formato del cuerpo (400, `manualEntriesPolicy` con un valor inválido); partida inexistente o ajena (404); período cerrado (409 `PERIOD_CLOSED`); partida consolidada (409 `ENTRY_NOT_PENDING`); sin movimientos (409 `CONSOLIDATION_REQUIRES_MOVEMENT`); y por último editadas en el destino sin política (409 `MANUAL_POLICY_REQUIRED`, con sus ids en `entries`), porque necesita el plan.
- **Otros huecos de RN-25 a RN-28**: consolidar un mes futuro con un cobro anticipado, o fuera de orden, está permitido y no tiene regla extra (RN-25 no exige fecha; S-05). La marca de editada de la partida que se consolida no se toca, así no se pierde información si HU-26 la desconsolida (D-38). Una política enviada cuando no hace falta (sin editadas en el destino, o partida sin Concepto) se ignora sin error (HU-25, criterio 5). La fecha de consolidación sale del `Clock` como `Instant`.
- **Concurrencia**: la consolidación toma un bloqueo de escritura del Concepto (`lockByUserIdAndEntryId`) como primera lectura, antes de la instantánea de la transacción, para que dos consolidaciones del mismo Concepto no se pisen. La partida, el Concepto y el destino cambian en una sola transacción.
- **Frontend**: «Consolidar» en las Parciales de un período abierto; el diálogo muestra la vista previa y avisa que se puede desconsolidar mientras el mes esté abierto (HU-26, D-38). El aviso de «cubierta» de HU-19 ofrece el botón, en el diálogo de movimientos y en el aviso emergente.
- **Elegir sobre las editadas** (HU-25, decidido con el usuario, sin cambios en la API): una editada cuyo monto ya es E sigue obligando a elegir, porque Pisar le quita la marca (D-31). El diálogo muestra la lista de editadas, una línea por opción y cuántas otras partidas cambian de monto, a partir de `manual` y `changes`. La elección es el componente `ManualPolicyChoice`, que reutilizará el pago rápido (HU-22).
- **La vista previa informa la base de la estimación** (HU-24, decidido con el usuario): `estimateBasis` lista las consolidaciones que entraron en E (período y monto), de la más reciente a la más antigua, para que el diálogo explique el promedio. `EstimationCalculator.Plan` expone `basis`; no cambia ninguna cuenta. El frontend solo nombra los meses.

**D-37. Pago rápido.** *Confirmada* (HU-22): las tres dudas (vista previa, orden de los errores y presupuestado 0) se resolvieron con el usuario antes de escribir código, todas por la opción recomendada. Depende de **S-01, S-02, S-03 y S-09**, y por la consolidación de **S-04 a S-06**, todos **sin confirmar**.

- **Endpoints**: `POST /api/entries/{id}/quick-settle` (`quickSettleEntry`, cuerpo opcional `{date, accountId, manualEntriesPolicy}`) responde 200 con `{movement, entry, estimate, updatedEntries}`: el movimiento creado y la forma de la respuesta de consolidar. Es una acción, como `consolidate`, por eso 200 y no 201. `GET /api/entries/{id}/quick-settle-preview` (`getQuickSettlePreview`) responde `{entryId, allowed, blockedBy, blockedDetail, amount, consolidation}`. Los dos van bajo el tag «Movimientos».
- **La vista previa es un endpoint propio**, no un parámetro de la de consolidación: sus bloqueos son otros (`NOTHING_PENDING` no impide consolidar) y `allowed` cambiaría de significado según el parámetro. `consolidation` es la misma `ConsolidationPreview` de D-36, calculada con el real de hoy más el pendiente por el mismo `EstimationCalculator`; no hay cálculo aparte ni cuentas en el frontend. No lleva fecha ni cuenta: E y el destino no dependen de ellas, y se validan al confirmar.
- **Orden de evaluación** (se informa el primero que falla): token (401); formato del cuerpo (400); partida inexistente o ajena (404); período cerrado (409 `PERIOD_CLOSED`); partida consolidada (409 `ENTRY_NOT_PENDING`); sin pendiente (409 `NOTHING_PENDING`); cuenta inexistente o ajena (400 en `accountId`, D-24); moneda (409 `CURRENCY_MISMATCH`); fecha (409 `DATE_OUT_OF_RANGE` y, por último, 409 `PERIOD_CLOSED` por el mes); y las editadas del destino sin política (409 `MANUAL_POLICY_REQUIRED`). Primero el estado de lo que existe, después lo que se pide (D-33, D-35), con la política al final porque necesita el plan (D-36). Una fecha inválida con editadas sin política informa la fecha.
- **Qué cambia en la consolidación porque el movimiento todavía no existe**: `CONSOLIDATION_REQUIRES_MOVEMENT` no puede ocurrir (siempre habrá un movimiento); el monto consolidado es real + pendiente, que con pendiente mayor que 0 es exactamente el presupuestado; y el período y el estado de la partida se evalúan una sola vez, al principio.
- **Todo se valida antes de guardar nada**: `MovementService` separa `validate` (RN-21: cuenta, moneda y fecha) de `save`, y `ConsolidationService` separa `prepare` (RN-25 y el plan de RN-26, con política) de `apply`. `QuickSettleService` valida las dos cosas y recién después guarda el movimiento y aplica la consolidación, en una sola transacción; el Concepto se bloquea como primera lectura. Un pedido rechazado no llega a guardar ni el movimiento, y la transacción queda como red de seguridad. `register` y `consolidate` siguen siendo validar + aplicar, sin cambio de comportamiento.
- **Partida Estimada con presupuestado 0**: no tiene pendiente (`NOTHING_PENDING`) ni movimientos (no se puede consolidar, S-03). La pantalla **no ofrece** «Pagar todo» ni «Cobrar todo» y no explica nada: la fila ya muestra pendiente $ 0,00 y sigue disponible «Registrar pago». Si el pedido llega igual (pantalla desactualizada), el `detail` es «No hay nada pendiente en esta partida. Si ya tiene movimientos, consolidala.», porque «corresponde consolidar» no vale para una Estimada en 0. Sus salidas siguen siendo registrar un movimiento mayor que 0 o eliminarla. Una Parcial cubierta (pendiente 0) tampoco la ofrece: ofrece «Consolidar».
- **El movimiento no lleva nota**, y por defecto la fecha es hoy según el `Clock` y la cuenta la de la partida. Con la ventana de la partida sin abrir (S-09) ninguna fecha sirve: el diálogo lo avisa y deja el botón deshabilitado, como el de registrar (D-33).
- **Frontend**: `QuickSettleDialog` con el movimiento (monto = pendiente que informa el backend), la fecha (sugerida por `movement-dates`, con la explicación de la ventana de `MovementDialog`), la cuenta (las de la moneda de la partida) y la vista previa de la consolidación. El bloque de la vista previa se extrajo de `ConsolidationDialog` como `ConsolidationOutcome`, que usan los dos diálogos, junto con `ManualPolicyChoice`.

**D-38. Desconsolidar una partida.** *Confirmada* (HU-26): las dudas (marca de editada, texto de la pantalla y huecos de RN-29) se resolvieron con el usuario antes de escribir código, todas por la opción recomendada. Depende de **S-07**, **sin confirmar**.

- **Endpoint**: `POST /api/entries/{id}/unconsolidate` (`unconsolidateEntry`, tag «Consolidación»), sin cuerpo. Responde 200 con la partida directamente, con la forma de la vista del mes (no lleva envoltorio: no hay estimación ni partidas actualizadas, porque nada se propaga).
- **Código nuevo `ENTRY_NOT_CONSOLIDATED`** (409) cuando la partida está pendiente. `ENTRY_NOT_PENDING` significa lo contrario («ya no está pendiente») y habría sido engañoso en la API y en la pantalla.
- **Orden de evaluación** (se informa el primero que falla): token (401); partida inexistente o ajena (404); período cerrado (409 `PERIOD_CLOSED`, aunque la partida esté pendiente); partida no consolidada (409 `ENTRY_NOT_CONSOLIDATED`). Primero el estado de lo que existe (D-33, D-35, D-37).
- **La marca de editada se conserva**: no hay nada que restaurar. Consolidar nunca la toca en la propia partida (D-36) y mientras está consolidada no es destino ni se edita (RN-18).
- **Qué no se revierte** (RN-29, S-07): el monto vigente del Concepto y las partidas de los demás meses conservan lo que propagó la consolidación hasta la próxima consolidación del Concepto. El diálogo lo explica con texto, **sin mostrar el monto vigente**: no llega a la pantalla (la vista previa de una consolidada no trae plan), y mostrarlo exigiría un endpoint nuevo. Una partida sin Concepto dice que no afecta otros meses.
- **Concurrencia**: toma `lockByUserIdAndEntryId` como primera lectura, igual que la consolidación (D-36), y cambia todo en una sola transacción. Una partida sin Concepto no tiene a qué bloquearse: dos pedidos simultáneos sobre la misma partida (por ejemplo desconsolidar y pagar todo) no se serializan, como ya pasaba con la consolidación. No se cambió en esta historia.
- **`closing_resolution`** solo lo escribe HU-31 y en un período cerrado, que esta acción rechaza antes: no se lee ni se limpia.
- **Frontend**: «Desconsolidar» (`canUnconsolidate`: consolidada y período abierto) y `UnconsolidateDialog`. Al confirmar, `BudgetMonth` vuelve a pedir el mes y la partida se ve Parcial, con real, pendiente y sus acciones. Ante `PERIOD_CLOSED`, `ENTRY_NOT_CONSOLIDATED` o `NOT_FOUND` muestra el motivo y, al cancelar, avisa que la pantalla estaba desactualizada. El aviso de `ConsolidationOutcome` (que comparten `ConsolidationDialog` y `QuickSettleDialog`) pasó de «no se puede deshacer por ahora» a «Podés desconsolidarla mientras el mes esté abierto». Una fila consolidada de un período abierto ya no queda sin acciones.

**D-39. Transferencias entre cuentas.** *Confirmada* (HU-27): las dudas (rango por defecto de la lista, orden de los errores y huecos de RN-36) se resolvieron con el usuario antes de escribir código, todas por la opción recomendada. Depende de **S-09**, **sin confirmar**.

- **Endpoints**: `POST /api/transfers` (201) y `GET /api/transfers?from=&to=`, ambos con la forma de HU-27. Edición y eliminación, de HU-29 (D-41). El tipo de cambio se sumó en HU-28 (D-40).
- **Orden de evaluación del alta** (se informa el primero que falla): token (401); formato del cuerpo (400: faltantes, montos mayores que 0 con hasta 2 decimales, nota de hasta 200); origen igual a destino (400 `VALIDATION_ERROR` en `targetAccountId`); cuenta de origen y de destino inexistentes o ajenas (400 en `sourceAccountId` y `targetAccountId`, D-24, el origen primero); montos distintos con la misma moneda (409 `TRANSFER_AMOUNTS_MISMATCH`); y la fecha: posterior a hoy, anterior a la apertura del origen, anterior a la apertura del destino (409 `DATE_OUT_OF_RANGE`) y, por último, mes cerrado (409 `PERIOD_CLOSED`). Mismo criterio que D-37: primero lo que existe y las monedas, al final la fecha.
- **Un mes sin período** (anterior al período inicial) responde `DATE_OUT_OF_RANGE`, como en RN-21. Por la API no se produce, porque la apertura de una cuenta nunca es anterior al primer día del período inicial (RN-33).
- **No hay ventana de anticipación**: la fecha no puede ser posterior a hoy.
- **La lista**: de la más reciente a la más antigua y, a igual fecha, la última registrada primero. Sin `to`, hoy; sin `from`, el primer día del mes de `to`, de modo que sin ninguno es el mes actual. `from` posterior a `to`: 400 `VALIDATION_ERROR` en `from`. Una fecha mal formada también es 400. Sin paginación.
- **La nota** se guarda sin espacios en los extremos; en blanco, sin nota (como en los movimientos).
- **El alta responde solo la transferencia**, sin saldos: el frontend vuelve a pedir `/api/accounts`.
- **Monedas distintas**: se aceptan con los dos montos y sin relación entre ellos; el tipo de cambio es de HU-28 (RN-37, D-40).

**D-40. Tipo de cambio de una transferencia.** *Confirmada* (HU-28): el usuario aprobó el redondeo antes de escribir código; las dudas (vista previa, palabras «Compra» y «Venta» y huecos de RN-37) se resolvieron con él, todas por la opción recomendada. Complementa a **D-06**.

- **Redondeo**: el tipo de cambio es ARS ÷ USD a 2 decimales con `HALF_UP`, sin importar cuál es el origen. Es el segundo cálculo que redondea, junto con el promedio de RN-26 (RN-03, S-16).
- **Solo se muestra**: `exchangeRate` en las respuestas de `POST` y `GET /api/transfers`, nulo entre cuentas de la misma moneda. No hay columna ni se guarda ninguna cotización. Los saldos y subtotales siguen separados por moneda.
- **Sin vista previa**: el formulario no lo muestra antes de guardar (el frontend no hace cuentas con montos y un endpoint aparte no lo pide ningún criterio de HU-28). Se ve al guardar y en la lista.
- **Compra y venta**: textos de la interfaz derivados de las monedas, no un campo: de ARS a USD, «Compra de dólares»; de USD a ARS, «Venta de dólares».
- **Sin tope**: con montos de USD muy chicos el tipo de cambio puede ser muy alto (1.250,00 ARS por 0,01 USD dan 125.000,00); RN-36 no lo limita.
- **Cálculo con el redondeo**: el tipo de cambio mostrado puede no reproducir los montos cargados; los montos son los que valen para los saldos.

**D-41. Corregir o eliminar una transferencia.** *Confirmada* (HU-29): las cuatro dudas (orden de los errores, edición sin cambios, cambio de moneda al editar y cómo sabe la lista qué se puede editar) se resolvieron con el usuario antes de escribir código, todas por la opción recomendada. Depende de **S-09**, **sin confirmar**. Sigue a **D-35** y **D-39**.

- **Endpoints**: `PUT /api/transfers/{id}` (`updateTransfer`) responde 200 con la transferencia y su tipo de cambio recalculado; `DELETE /api/transfers/{id}` (`deleteTransfer`) responde 204. El cuerpo del `PUT` es el mismo `TransferRequest` del alta. De otro usuario o inexistente: 404 (RN-01).
- **Orden de evaluación del `PUT`** (se informa el primero que falla): token (401); formato del cuerpo (400); transferencia inexistente o ajena (404); mes de la fecha actual cerrado (409 `PERIOD_CLOSED`); y los valores nuevos con la misma regla y el mismo orden del alta (D-39): origen igual a destino, cuentas, `TRANSFER_AMOUNTS_MISMATCH`, fecha (`DATE_OUT_OF_RANGE` antes que `PERIOD_CLOSED`). Primero el estado de lo que existe y después lo que se pide: si fallan a la vez el mes actual y el de la fecha nueva, se informa el actual. El `DELETE` evalúa 404 y mes actual cerrado.
- **Sin cambios** es válido: 200 con la misma transferencia y sin guardar nada.
- **Se puede cambiar de misma moneda a monedas distintas y al revés.** RN-36 no lo prohíbe: los montos nuevos cumplen lo mismo que en el alta (iguales si las monedas coinciden; sin relación si difieren) y el tipo de cambio sale recalculado, o nulo si ahora son de la misma moneda.
- **Siempre se revalidan los valores nuevos.** A diferencia de D-35 no hay ventana configurable que pueda dejar fuera una transferencia válida: «hoy» no retrocede, RN-33 impide mover la apertura de una cuenta después de su primera transferencia y el mes de la fecha ya se verificó abierto. Con fecha y cuentas iguales la revalidación no puede fallar, y evita una rama especial.
- **`editable` en la respuesta** (`Transfer.editable`, en el alta, la edición y la lista): informa si el mes de la fecha es un período abierto, para que la pantalla ofrezca «Editar» y «Eliminar» solo donde corresponde. Es informativo: el backend decide al intentarlo y responde 409 `PERIOD_CLOSED` si el mes se cerró entre tanto. Se calcula con una consulta por mes distinto de la lista. Es el único cambio de contrato de la historia; para movimientos no hizo falta (D-35) porque la pantalla ya conoce el estado del período.
- **Mes cerrado**: una transferencia con fecha en un mes cerrado queda congelada: no se edita ni se elimina, y no hay forma de producirlo de punta a punta hasta HU-33.
- **RN-33 sigue bien sin cambios de código**: «referenciada», la fecha de la primera transferencia y los saldos (RN-35) se consultan en vivo en la tabla `transfer`; no hay nada guardado que corregir al editar o eliminar. Verificado con datos.
- **Frontend**: «Editar» reutiliza `TransferForm` con los valores cargados (uno o dos montos según las monedas, y cambia al cambiar la cuenta); «Eliminar» pide confirmar con fecha, cuentas, monto o montos y tipo de cambio. Después de cada cambio se pide de nuevo la lista y el foco queda en «Nueva transferencia».

**D-42. Ver qué falta para cerrar el mes (simulación del cierre).** *Confirmada* (HU-30): las cuatro dudas se resolvieron con el usuario antes de escribir código, todas por la opción recomendada. Depende de **S-10**, **sin confirmar**.

- **Alcance entre historias** (decidido por el usuario): el cierre se construye en cuatro partes. HU-30 hace las precondiciones y el diagnóstico; HU-31, las resoluciones de pendientes; HU-32, la conciliación y las diferencias; HU-33, el cierre efectivo. `ClosingPlanner` y la respuesta de la simulación crecen con campos nuevos, sin cambiar los existentes.
- **Se informan todas las precondiciones que fallan**, en el orden de RN-38 (`PERIOD_CLOSED`, `PREVIOUS_PERIOD_OPEN`, `PERIOD_NOT_FINISHED`), cada una con su explicación. El caso común, un mes sin terminar con el anterior abierto, muestra los dos motivos. El cierre (HU-33) responderá el 409 de la primera. Como cerrar exige las otras dos, un período cerrado en la práctica solo falla por `PERIOD_CLOSED`.
- **Un período cerrado responde 200** con `PERIOD_CLOSED` y las listas vacías: no hay nada que resolver ni conciliar, y no se leen partidas ni saldos. Es la misma forma que las vistas previas de D-32 y D-36. **Fuera de rango, 404**, como la vista del mes (RN-06). Un período abierto pero bloqueado arma el diagnóstico igual: es lo que hay que resolver.
- **Sin cuerpo en HU-30**: si llega uno (con resoluciones o saldos reales), se ignora y la respuesta es el diagnóstico. HU-31 y HU-32 agregan un cuerpo opcional, lo que no rompe el contrato ni el cliente generado.
- **`preconditionsMet`** y no «se puede cerrar»: HU-31 y HU-32 sumarán «los datos que faltan» (RN-43). Si el campo dijera «se puede cerrar», tendría que cambiar de significado.
- **`firstOpenPeriod`** (RN-08) va en la respuesta. El enlace de «mes anterior abierto» lleva al primer período abierto y no al anterior, que también puede estar bloqueado.
- **Saldos al último día**: `AccountService.balancesAt` expone las cuatro sumas de HU-08 a una fecha. En un mes sin terminar, el saldo «al último día» coincide con el de hoy, porque no hay movimientos futuros (S-09).
- **«Cerrar mes» está en todo período abierto**, aunque todavía no se pueda cerrar: el asistente explica el motivo (criterio 2) sin cambiar el contrato de la vista del mes. No aparece en los cerrados.
- **Frontend**: los tres pasos del asistente se muestran como una lista; en esta historia solo el primero tiene contenido y los otros dos aparecen deshabilitados («Disponible más adelante»). El frontend no suma: agrupa las cuentas por moneda y nada más.

**D-43. Resolver partidas pendientes al cerrar (simulación).** *Confirmada* (HU-31): las cuatro dudas se resolvieron con el usuario antes de escribir código, todas por la opción recomendada. Depende de **S-04** y **S-10**, **sin confirmar**; usa también S-03 (que «Cerrar con lo registrado» consolide sin movimientos es una excepción explícita de RN-40) y S-20.

- **Alcance entre historias**: como acordó D-42, esta historia no guarda nada. Suma las resoluciones al `Input` y al `Plan` de `ClosingPlanner` y a la simulación, y el primer paso del asistente pasa a ser la elección. El 409 `UNRESOLVED_PENDING_ENTRIES`, las consolidaciones y las partidas de saldo postergado guardadas son del cierre efectivo (HU-33).
- **Nombre de más de 100 caracteres**: «Saldo pendiente: » tiene 17. Si el total supera los 100 de la columna `name`, el nombre original se recorta a 82 caracteres (sin espacios al final y sin partir un par sustituto) y se agrega «…», así el total es 100. Se cuenta en caracteres de Java, como la validación de los nombres. El original sigue disponible por `source_entry_id`. Se descartó ampliar la columna: la partida no se podría volver a guardar con su nombre al editarla (RN-19 limita a 100).
- **Elecciones en el asistente**: arrancan todas sin elegir, porque RN-39 pide una resolución explícita y una sugerida podría descartar pendientes sin querer. Dos botones, «Postergar saldo en todas» y «Cerrar con lo registrado en todas», completan todas las elecciones, que después se cambian de a una. Con pendiente 0 el asistente aclara que las dos opciones tienen el mismo efecto.
- **Una resolución que no corresponde no es un error de la simulación**: responde 200 con `invalidResolutions` (`entryId` y `reason`: `NOT_IN_PERIOD` para una partida de otro mes, de otro usuario o inexistente, que se informan igual como en D-24; `NOT_PENDING` para una consolidada del período) y no se aplica. El cierre efectivo (HU-33) responderá 400 `VALIDATION_ERROR` (RN-39). Así, una partida consolidada en otra pestaña no traba el asistente: la quita de la lista y lo avisa.
- **Otros huecos de RN-40, pasos 1 y 2**:
  - **Vencimiento**: original más un mes calendario (`plusMonths(1)`), también en las recurrentes; no se mira el día de vencimiento del Concepto. Un alquiler del día 31 con la partida de noviembre al 30/11 se posterga al 30/12.
  - **Período siguiente**: el del período que se cierra más uno, aunque el vencimiento sea de otro mes. Con desfase −1 la partida postergada vence dentro del mes que se cierra, así que nace vencida (RN-20, S-20); sigue dentro del rango de RN-19 del mes siguiente.
  - **Precondiciones sin cumplir**: el backend calcula los efectos igual; el asistente muestra las pendientes sin elección (el diagnóstico de HU-30), porque no se podría avanzar y lo elegido se perdería.
  - **Período cerrado**: las resoluciones recibidas se ignoran, sin informarlas como inválidas.
  - **Una partida con más de una resolución** en el cuerpo: 400 `VALIDATION_ERROR` en `resolutions`, antes de leer nada (es un pedido mal formado).
  - **Postergar un saldo postergado**: conserva su nombre y `source_entry_id` apunta a la partida inmediata, no a la primera de la cadena.
  - **Postergar una diferencia de cierre**: «Saldo pendiente: Diferencia de cierre: …», con origen `CARRIED_OVER` (RN-42: se resuelve como cualquier otra).
  - **Datos de la partida nueva**: tipo, cuenta y categoría de la original (en una recurrente, el nombre y la categoría del Concepto, los mismos que muestra la vista del mes), presupuestado igual al pendiente, pendiente y sin editar.
- **API**: el cuerpo de la simulación es opcional, `{resolutions: [{entryId, resolution}]}`; sin cuerpo, sin lista o con lista vacía, es el diagnóstico de HU-30. Los saldos reales siguen ignorándose hasta HU-32. Cada pendiente suma `resolution`, `consolidatedAmount` (su real) y `carriedOverEntry`, nulos sin resolución; la respuesta suma `newEntries` (HU-32 agregará las diferencias de cierre), `unresolvedEntryIds` e `invalidResolutions`. Lo de HU-30 no cambió.
- **Frontend**: cada elección vuelve a pedir la simulación con todas las elegidas (un pedido nuevo cancela el anterior) y el efecto de cada partida sale de la respuesta; mientras no llega, dice «Calculando…». Las elecciones viven en la pantalla y se pierden al recargar o cambiar de mes, porque no se guarda nada hasta HU-33. «Siguiente: Saldos» exige todas resueltas según el backend y sigue deshabilitado hasta HU-32.

**D-44. Conciliar saldos y registrar diferencias (simulación).** *Confirmada* (HU-32): las cuatro dudas (abreviaturas del mes, categoría de la diferencia, saldos que no corresponden y los huecos de RN-40 paso 3 y RN-42) se resolvieron con el usuario antes de escribir código, todas por la opción recomendada. Depende de **S-10**, **sin confirmar**, y de **S-09** (en un mes sin terminar, el saldo al último día es el de hoy); D-22 está confirmada.

- **Alcance entre historias**: como acordó D-42, esta historia no guarda nada. Suma los saldos reales al `Input` y al `Plan` de `ClosingPlanner` y a la simulación, y habilita el paso «Saldos» del asistente. El 409 `MISSING_REAL_BALANCE`, los cierres de cuenta, las partidas de diferencia guardadas y el cierre del período son del cierre efectivo (HU-33); en la simulación, las cuentas sin saldo real se informan en `missingRealBalanceAccountIds`, como `unresolvedEntryIds` con las resoluciones.
- **Meses abreviados**: tabla fija en el código, no el locale de Java (cuyas abreviaturas cambian entre versiones): `ene feb mar abr may jun jul ago sep oct nov dic`, tres letras, minúscula y sin punto. Septiembre es «sep», no «sept». Coincide con el ejemplo de RN-40 («oct 2026»).
- **Nombre**: «Diferencia de cierre: <cuenta> (<mes> <año>)», con el mes y el año del período que se cierra (diciembre de 2026 da «dic 2026» y la partida va a enero de 2027). «Diferencia de cierre: » tiene 22 caracteres y « (oct 2026)», 11: la cuenta (hasta 100 caracteres) entra completa hasta con 67. Con más, se recorta el nombre de la **cuenta** a 66, sin espacios al final ni partir un par sustituto, y se agrega «…» antes del mes, que queda intacto, así el total es 100. El recorte es el de D-43, pero sobre la cuenta y no sobre el final. La partida guarda el nombre completo de la cuenta en `accountName`.
- **Sin categoría**: la partida de diferencia no lleva categoría. La categoría es opcional (glosario), la cuenta no tiene una de la que heredarla y crearle una al usuario cambiaría las reglas de categorías (RN-34). El usuario puede asignarla al editar la partida.
- **Una diferencia es real menos calculado**, con 2 decimales. Distinta de 0: ingreso si es positiva y gasto si es negativa, por su valor absoluto, en la cuenta conciliada y su moneda, en el período siguiente, con vencimiento el día 1 (siempre dentro del rango de RN-19 de ese mes). Con 0 no se crea nada y la respuesta dice `difference: 0.00`.
- **Saldo real**: puede ser 0 o negativo. Hasta 17 enteros y 2 decimales (`DECIMAL(19,2)`); más decimales, sin monto o sin cuenta es `VALIDATION_ERROR` en `realBalances`, de formato (RN-03), antes de leer nada.
- **Un saldo que no corresponde no es un error de la simulación**, como en D-43: responde 200 con `invalidBalances` (`accountId` y `reason: NOT_RECONCILABLE`, una sola razón) y no se aplica. Cubre una cuenta abierta después del último día, de otro usuario o inexistente: los tres se informan igual (D-24). Una cuenta con dos saldos es un pedido mal formado: 400 `VALIDATION_ERROR` en `realBalances`, antes de leer nada. El cierre efectivo (HU-33) responderá 400 `VALIDATION_ERROR` por los inválidos (RN-39).
- **Otros huecos**:
  - **Precondiciones sin cumplir**: las diferencias se calculan igual (D-43). **Período cerrado**: los saldos se ignoran, sin informarlos como inválidos.
  - **Cuenta sin saldo real**: no tiene `realBalance`, `difference` ni partida (nulos) y su id va en `missingRealBalanceAccountIds`, en el orden de las cuentas. Las que sí tienen saldo muestran su diferencia igual. `preconditionsMet` no cambia.
  - **El saldo calculado no cambia con las resoluciones**: Postergar y Cerrar con lo registrado solo consolidan y no registran movimientos. Incluye los cobros anticipados de partidas del mes siguiente (RN-41) y deja afuera los movimientos con fecha posterior al último día, aunque ya estén cargados: el saldo real es el del último día del mes, no el de hoy.
  - **`newEntries`** junta las dos clases: primero las de saldo postergado, en el orden de las pendientes, y después las de diferencia, en el orden de las cuentas.
  - **Referencia al cierre de cuenta**: el cierre todavía no existe, así que cada diferencia lleva `sourceAccountId` (la cuenta conciliada) y no `sourceEntryId`; es la clave con la que HU-33 completa `source_closing_id`: `account_closing` es único por (período, cuenta), así que guarda primero los cierres de cuenta, busca cada uno por esa clave y la partida queda referida a él. Sin columna nueva ni migración. `sourceEntryId` pasa a ser nulo en las diferencias.
  - **RN-42**: no hay nada que implementar acá. Una diferencia que sigue pendiente al cerrar el mes siguiente se resuelve como cualquier otra, y con Cerrar con lo registrado vuelve a aparecer en la conciliación de ese mes porque el saldo calculado la sigue arrastrando; se verifica con datos en HU-33. El saldo inicial del mes siguiente sigue siendo el calculado (D-22).
- **API**: el cuerpo de la simulación suma `realBalances: [{accountId, amount}]`, opcional, junto a `resolutions`; sin cuerpo, sin lista o con lista vacía, es el diagnóstico de HU-30. Cada cuenta de la respuesta suma `realBalance`, `difference` y `differenceEntry`, nulos sin saldo real (`differenceEntry` también con diferencia 0); la respuesta suma `missingRealBalanceAccountIds` e `invalidBalances`. Lo de HU-30 y HU-31 no cambió, salvo que `ClosingNewEntry` suma `sourceAccountId` y `sourceEntryId` pasa a nulable.
- **Frontend**: «Saldos» se habilita con todas las pendientes resueltas y muestra, por moneda, cada cuenta con su saldo calculado, un campo de saldo real (coma decimal, admite negativos) y la diferencia que informa el backend con su efecto («En noviembre aparece un gasto «…» por $ 2.500,00.» o «Coincide.»). Al salir de un campo se vuelve a pedir la simulación con las resoluciones y todos los saldos cargados; un campo vacío o con formato inválido cuenta como faltante. «Siguiente: Confirmación» exige todos los saldos y sigue deshabilitado hasta HU-33. Se puede volver a «Pendientes» sin perder lo cargado. Con el mes en condiciones de cerrarse, las cuentas se ven en «Saldos» y «Pendientes» ya no las lista; bloqueado por las precondiciones, «Pendientes» sigue mostrando el diagnóstico de HU-30 con las cuentas. Los saldos viven en la pantalla: se pierden al recargar o cambiar de mes.

**D-45. Cerrar el mes (cierre efectivo).** *Confirmada* (HU-33): las cuatro dudas (concurrencia, qué pide «Confirmación», qué responde el cierre y adónde lleva la pantalla, y los huecos de RN-39 a RN-43) se resolvieron con el usuario antes de escribir código, todas por la opción recomendada. Depende de **S-04** y **S-10**, **sin confirmar**; D-21 a D-23 están confirmadas.

- **Una sola fuente**: `POST /api/periods/{period}/close` (`closePeriod`) recibe el mismo cuerpo que la simulación y arma el mismo plan con `ClosingPlanner` (RN-43). Lo que la simulación informa como dato (resoluciones y saldos que no corresponden, pendientes sin resolver, cuentas sin saldo real), el cierre lo rechaza. No hay un cálculo aparte.
- **Orden de los errores** (se informa el primero que falla): una partida o una cuenta repetida en el cuerpo (400, antes de leer nada); el período (404, RN-06); la primera precondición de RN-38 (409 `PERIOD_CLOSED`, `PREVIOUS_PERIOD_OPEN` o `PERIOD_NOT_FINISHED`); las resoluciones que no corresponden (400 `VALIDATION_ERROR` en `resolutions`); los saldos reales que no corresponden (400 en `realBalances`); las pendientes sin resolución (409 `UNRESOLVED_PENDING_ENTRIES`, con sus ids en `entries`); y las cuentas sin saldo real (409 `MISSING_REAL_BALANCE`, con sus ids en `accounts`, un campo nuevo de Problem Details análogo a `entries`). Lo que vino mal en el pedido va antes que lo que falta: al corregirlo puede cambiar lo que falta (por ejemplo, una partida consolidada en otra pestaña).
- **Concurrencia**: el cierre toma un bloqueo de escritura de la fila del período (`lockByUserIdAndPeriodMonth`) como primera lectura, igual que la consolidación con el Concepto (D-36). Un segundo cierre del mismo período espera y lo ve cerrado (409 `PERIOD_CLOSED`). El cierre del mes siguiente lee el estado de este después de su propio bloqueo: si este no terminó de cerrarse, lo ve abierto (`PREVIOUS_PERIOD_OPEN`), así que nunca se cierra un mes sobre otro cerrado a medias. **Límite conocido, aceptado**: una operación hecha en el mismo instante desde otra pestaña sobre el mismo mes (registrar un movimiento, consolidar) lee el estado del período sin bloqueo; cubrirlo obligaría a que todos esos servicios tomen un bloqueo compartido del período. Se acepta por el uso de a un usuario por vez, como el riesgo de D-32.
- **Orden de guardado** (RN-40), en una sola transacción: consolidar cada pendiente con su real y su resolución; crear las partidas de saldo postergado (`source_entry_id` apunta a la original, que ya existe); guardar un cierre de cuenta por cada cuenta conciliada, también con diferencia 0; crear las partidas de diferencia con `source_closing_id` del cierre de su cuenta, tomado de los recién guardados en la misma transacción (sin otra consulta); y pasar el período a cerrado. Con claves `IDENTITY` los `INSERT` salen en ese orden, así que la clave foránea siempre encuentra su fila. Cualquier error deshace todo. Un período siguiente inexistente o cerrado es un error de programación (500) y tampoco cambia nada.
- **Un solo instante**: `consolidated_at` de las partidas consolidadas por el cierre es igual a `closed_at`, los dos del `Clock`.
- **Qué no se toca**: las consolidadas por el cierre conservan su presupuestado y su marca de editada (como D-36); no propagan ni cambian el Concepto ni su monto vigente (S-04). Las partidas nuevas quedan pendientes, sin editar, sin Concepto ni cuota.
- **Cuerpo opcional**, como en la simulación: un mes sin pendientes ni cuentas a conciliar se cierra sin cuerpo.
- **Lo que vio el usuario y lo que se aplica**: el cierre aplica los datos del momento. Si algo cambió desde la última simulación (otra pestaña), las nuevas pendientes dan 409 y un saldo calculado distinto da otra diferencia con el saldo real informado. Se acepta: el asistente pide la simulación al entrar a «Confirmación» y la respuesta del cierre informa lo aplicado. Se descartó mandar los saldos calculados vistos y rechazar si cambiaron.
- **Respuesta**: 200 con `ClosingResult`: `period`, `status` (`CLOSED`), `closedAt`, `nextPeriod`, `consolidatedEntries` (id, nombre, resolución y monto consolidado), `createdEntries` (id y los datos de `ClosingNewEntry`) y `accountClosings` (id, cuenta, saldo calculado, real, diferencia e id de la partida de diferencia, nulo con diferencia 0). Es lo aplicado, con ids.
- **Frontend**: «Confirmación» se habilita con todo resuelto y cargado según el backend, vuelve a pedir la simulación y muestra su resumen (qué se consolida y con qué monto, qué se crea en el mes siguiente y cada cuenta con su diferencia), avisa que el mes queda cerrado para siempre y pide una casilla («Entiendo que <mes> queda cerrado para siempre y no se puede reabrir.») para habilitar «Cerrar <mes>». No hay un diálogo aparte: el paso ya muestra exactamente qué va a pasar. Al cerrar lleva al presupuesto del mes cerrado, que se ve «Cerrado» y sin acciones, con un aviso «<Mes> quedó cerrado.» y la acción «Ir a <mes siguiente>». Ante un error, lo muestra, vuelve a pedir la simulación y vuelve al paso que corresponde sin perder lo cargado: «Pendientes» con las precondiciones, el mes ya cerrado, las pendientes sin resolver y las resoluciones inválidas; «Saldos» con los saldos faltantes o inválidos; sin conexión o con un error inesperado se queda en «Confirmación».

**D-46. Flujo de caja por fecha.** *Confirmada* (HU-34): las cinco dudas de RN-45 (rangos futuros, pendientes vencidos, cuentas que abren dentro del rango, orden dentro de un día y límites del rango) y una sexta (extremos omitidos) se resolvieron con el usuario antes de escribir código, todas por la opción recomendada. Depende de **S-09** (no hay movimientos ni transferencias futuros, así que lo real nunca pasa de hoy), **sin confirmar**, y de **S-13**.

- **Fecha efectiva** de una partida pendiente: su vencimiento, o hoy si ya venció (RN-20). Nunca es anterior a hoy.
- **Saldo de arranque** = suma de los saldos reales (RN-35) al día anterior a `from` de las cuentas de esa moneda **abiertas antes de `from`**, más el pendiente (con signo) de las partidas pendientes cuya fecha efectiva es anterior a `from`. Si `from` es hoy o anterior, el segundo término es 0, porque la fecha efectiva siempre es ≥ hoy y RN-45 queda tal cual. Si `from` es posterior a hoy, el arranque es un saldo **proyectado** (`openingBalanceProjected = true`): sin esto, un mes futuro ignoraría lo que vence antes de empezar. Ejemplo: saldo hoy 100.000,00; Luz vencida 20.000,00 (va a hoy), Tarjeta 40.000,00 el 20/10 y Sueldo 300.000,00 el 30/10; para noviembre el arranque es 100.000,00 − 20.000,00 − 40.000,00 + 300.000,00 = 340.000,00, no 100.000,00.
- **Coherencia con RN-46**: el acumulado al final de un rango que llega a hoy o más allá es el saldo actual más los pendientes con fecha efectiva hasta `to`, es decir, la proyección de HU-35 para esa fecha, en una sola moneda.
- **Pendientes vencidos**: van en hoy. Si el rango termina antes de hoy no se listan (su fecha efectiva queda afuera): un rango totalmente pasado muestra solo hechos y su acumulado final es el saldo real al día `to`. Si el rango empieza después de hoy entran en el arranque proyectado.
- **Partidas que se listan**: las pendientes cuyo pendiente (RN-17) es mayor que 0 y cuya cuenta es de la moneda pedida. Una Parcial lista solo lo que falta; una pagada por completo pero sin consolidar no se lista (su pendiente es 0). El movimiento ya hecho se lista aparte, como real.
- **Cuentas que abren dentro del rango**: su saldo inicial no entra en el arranque; aparece como una fila real «Saldo inicial de <cuenta>» en su fecha de apertura (el saldo inicial es «al comienzo de la fecha de apertura»), con el signo del saldo. Un saldo inicial de 0 no se lista. Una cuenta que abre después de `to` no aparece.
- **Orden**: por fecha; dentro del día, saldo inicial de cuenta, real y prevista; dentro de cada grupo, ingresos antes que gastos (monto positivo antes que negativo); y después descripción sin distinguir mayúsculas y orden de creación. Los movimientos no tienen hora, así que dentro de un día el orden es una convención: lo real va antes porque ya ocurrió y lo previsto todavía no.
- **Transferencias**: se listan solo las de monedas distintas, con el signo de la moneda pedida: resta el monto de origen si la cuenta de origen es de esa moneda y suma el de destino si lo es la de destino. Descripción «Compra de dólares» / «Venta de dólares» y las dos cuentas.
- **Movimientos**: por la moneda de la cuenta del movimiento, con el signo del tipo de la partida (RN-35), también los cobros anticipados de partidas del mes siguiente (RN-41).
- **Límites del rango**: `from` no puede ser anterior al primer día del período inicial del usuario ni `to` posterior al último día del horizonte, y el rango no puede tener más de 366 días (ambos extremos incluidos). Fuera de eso, 400 `VALIDATION_ERROR` con el error en el campo. `from` posterior a `to`: 400 en `from`.
- **Extremos omitidos**: sin `from` ni `to`, el mes actual; sin `to`, el último día del mes de `from`; sin `from`, el primer día del mes de `to`. La moneda es obligatoria (400 si falta o no es `ARS` ni `USD`).
- **API**: `GET /api/cashflow?from=&to=&currency=` (`getCashFlow`) responde `from`, `to`, `currency`, `openingBalance`, `openingBalanceDate` (el día anterior a `from`), `openingBalanceProjected`, `rows` y `closingBalance`. Cada fila: `date`, `status` (`REAL` o `PLANNED`), `type` (`MOVEMENT`, `TRANSFER`, `PENDING_ENTRY`, `ACCOUNT_OPENING`), `description`, `amount` con signo y `balance` acumulado; las previstas traen además `dueDate` y `overdue`. Todos los montos los calcula el backend; nunca se suman ARS y USD.

**D-47. Proyección de saldos por cuenta.** *Confirmada* (HU-35): las tres dudas se resolvieron con el usuario antes de escribir código, todas por la opción recomendada. Sigue a RN-46 y reutiliza la fecha efectiva y el pendiente de D-46.

- **Presentación**: meses en filas (25, desde el actual hasta el horizonte) y cuentas en columnas, agrupadas por moneda (ARS, USD) y cada grupo con su subtotal. Encabezado y columna del mes fijos al recorrer la tabla.
- **Coincidencia con el flujo de caja**: la proyección del mes actual coincide, en el subtotal de cada moneda, con el saldo final del flujo de caja del mes actual en esa moneda. Es así por construcción (las cuentas no abren en el futuro, RN-33, y no hay movimientos futuros, S-09). Lo que **no** coincide es la atribución por cuenta cuando un pago salió de otra cuenta de la misma moneda: el pendiente sigue proyectado en la cuenta prevista de la partida (RN-46), aunque el pago ya bajó el saldo actual de la otra. El subtotal en esa moneda es el mismo.
- **Saldo negativo**: una cuenta o un subtotal con saldo proyectado por debajo de 0 lleva el signo menos y la marca «Faltante» en el texto (no solo el color), como el flujo de caja.
- **Regla compartida**: la fecha efectiva (el vencimiento, u hoy si ya venció), el pendiente con signo y «si cuenta» (pendiente mayor que 0) viven en `PendingEntryRule` (`report/domain`); la usan `CashFlowCalculator` y `ProjectionCalculator`.
- **API**: `GET /api/projection?until=YYYY-MM` (`getProjection`). `until` es opcional (por defecto, el horizonte); anterior al mes actual o posterior al horizonte es 400 `VALIDATION_ERROR` en el campo `until`. Responde `from`, `until`, `accounts` (id, nombre, moneda; el orden de las columnas) y `months`: cada uno con `month`, `balances` (uno por cuenta, en el orden de `accounts`) y `subtotals` (uno por moneda con cuentas, ARS antes que USD).
- **Consultas**: períodos del usuario (horizonte), `AccountService.balancesAt(hoy)`, las partidas pendientes del usuario de todas las monedas con vencimiento hasta el último día proyectado y la suma de los movimientos de esas partidas. Cuatro consultas fijas, sin importar cuántas cuentas, meses o partidas haya.
- **No incluye**: transferencias futuras (no existen, S-09), ni lo que no es una partida pendiente.

**D-48. Vista de varios meses.** *Confirmada* (HU-36): las cuatro dudas (totales, partidas sin Concepto, qué filas aparecen y rango) se resolvieron con el usuario antes de escribir código, todas por la opción recomendada. Sigue a RN-17 y RN-44 y no agrega supuestos.

- **Totales por mes y moneda**: estimado de ingresos, estimado de gastos y resultado, como en la vista del mes (RN-44). Se calculan en el backend con `MonthTotals` sobre las mismas filas que la vista del mes; el frontend no suma. Hay un total por cada moneda que tiene partidas en ese mes; un mes sin partidas de una moneda no la informa y la pantalla muestra «—».
- **Filas**: dos secciones, Ingresos y Gastos. En cada una, primero los Conceptos (por nombre sin distinguir mayúsculas y luego por id) y debajo las partidas sin Concepto agrupadas en **una fila por origen, tipo y moneda** («Puntuales», «Saldos postergados», «Diferencias de cierre»), con el estimado sumado de cada mes y la cantidad de partidas. Un clic abre el mes, donde está el detalle. Solo aparecen las filas con al menos una partida en el rango: un Concepto finalizado o por comenzar sin partidas en el rango no se muestra. No hace falta leer los Conceptos aparte.
- **Celdas**: un Concepto tiene como máximo una partida por período (RN-13), así que su celda trae esa partida: id, estimado (RN-17), estado mostrado (RN-16), si está editada y si está consolidada, calculados con la misma fila que la vista del mes (`PeriodViewService.row`). Sin partida, la celda es `null` y la pantalla muestra «—», nunca 0. En una celda agrupada, `entryId` y `status` son `null`, `manual` vale si alguna está editada y `consolidated` si todas están consolidadas. La moneda de cada celda es la de la cuenta de su partida; la de una fila de Concepto, la de su cuenta por defecto.
- **Rango**: `from` y `to` (`YYYY-MM`). Sin ninguno, 12 meses desde el actual; solo `from`, 12 meses desde ahí sin pasar del horizonte; solo `to`, 12 meses hasta ahí sin bajar del período inicial. Máximo 24 meses, entre el período inicial y el horizonte; si no, 400 `VALIDATION_ERROR` en `from` (invertido o anterior al período inicial) o en `to` (posterior al horizonte o más de 24 meses). Se responde 400, y no 404, porque son parámetros de consulta y no un recurso (RN-06). Un mes cerrado lleva «Cerrado» en el encabezado, como texto.
- **Consultas**: períodos del usuario (período inicial y horizonte), los períodos del rango (su estado), las partidas del rango con cuenta, categoría, Concepto, categoría y cuenta por defecto del Concepto, y las sumas de movimientos de todas ellas. Cuatro consultas fijas, sin importar cuántos Conceptos, meses o partidas haya; todas con `userId`.

## Supuestos tomados al redactar

**S-01. La ventana de anticipación vale para ingresos y gastos.** Pagar el alquiler de noviembre el 28 de octubre es tan común como cobrar antes. Si se prefiere solo para ingresos, se cambia RN-21.

**S-02. Un movimiento puede salir de otra cuenta que la de la partida, siempre que sea de la misma moneda.** La cuenta del Concepto es solo la sugerida.

**S-03. Consolidar exige al menos un movimiento.** Así se evita propagar un 0 por error a 24 meses. Para que un mes quede en 0 se elimina la partida de ese mes o se resuelve en el cierre.

**S-04. Las consolidaciones hechas durante el cierre no cuentan para la estimación ni propagan.** Una luz pagada a medias o dejada en 0 llevaría un valor irreal a todos los meses siguientes.

**S-05. La estimación usa las consolidaciones más recientes por período, no la última hecha en el tiempo.** El resultado no depende del orden en que se consolida, y se aplica solo a las partidas posteriores al último período consolidado.

**S-06. La propagación alcanza partidas pendientes con o sin movimientos.** El presupuestado de una partida parcial sigue siendo una estimación.

**S-07. Se puede desconsolidar mientras el período esté abierto.** No se revierte lo propagado: la próxima consolidación vuelve a calcular.

**S-08. Una partida con movimientos no se elimina.** Primero se borran los movimientos o se consolida. Reemplaza la idea inicial de "anular" partidas, que dejaba plata real fuera del presupuesto.

**S-09. Movimientos y transferencias no pueden tener fecha futura.** Representan cosas que ya pasaron.

**S-10. Un mes se cierra a partir de su último día y solo si el anterior está cerrado.**

**S-11. Cambiar el monto vigente de un Concepto actualiza sus partidas pendientes no editadas.** Es una corrección del Concepto (por ejemplo, un error de tipeo al crearlo) y no contradice D-11, que se refiere a editar partidas. «Pendientes» incluye las Parciales (RN-16): reciben el monto nuevo y su pendiente se recalcula (RN-17). Aclarado en HU-13.

**S-12. Tipo, periodicidad, período de inicio, período de fin y cuotas de un Concepto no se editan** (RN-15). Para cambiarlos se da de baja y se crea otro. En una baja el fin se fija (HU-18, D-32), pero eso no es editarlo.

**S-13. La cuenta tiene fecha de apertura y saldo inicial a esa fecha.** No se aceptan movimientos anteriores. Por defecto, la fecha de apertura es el primer día del período inicial del usuario. La fecha de apertura debe estar entre el primer día del período inicial y hoy, ambos inclusive: no puede ser futura. *Confirmada.*

**S-14. El usuario tiene un período inicial**, que se define al crearlo (por defecto, el mes actual). No existen períodos anteriores.

**S-15. El administrador puede restablecer contraseñas** con la misma clave especial del alta. Sin envío de emails, es la única forma de recuperar el acceso.

**S-16. Montos con 2 decimales y redondeo `HALF_UP`.** Solo el promedio (RN-26) y el tipo de cambio (RN-37, D-40) necesitan redondeo.

**S-17. Categorías opcionales**, sin tipo (sirven para ingresos y gastos).

**S-18. Flujo de caja, proyección de saldos y vista de varios meses (HU-34 a HU-36) se confirmaron antes de implementarlas.** Las tres historias de la Épica 9 se confirmaron: *HU-34* (D-46), *HU-35* (D-47) y *HU-36* (D-48).

**S-19. El nombre de un Concepto puede repetirse.** El modelo no tiene un índice único en `budget_item` y ninguna regla lo pide; dos Conceptos "Seguro" con distinta cuenta son razonables. Agregado en HU-10.

**S-20. El vencimiento de una partida puede caer en un mes cerrado o anterior al período inicial.** Pasa con desfase −1 cuando el Concepto empieza en el primer período abierto. No se rechaza: el período de la partida está abierto, y RN-09 bloquea movimientos, no vencimientos. Agregado en HU-10.

**S-21. Al crear un Concepto no se compara su período de inicio con la fecha de apertura de la cuenta.** RN-10 no lo pide; la fecha de apertura se controla al registrar cada movimiento (RN-21). Agregado en HU-10.

**S-22. Un plan de cuotas que termina después del horizonte se completa al avanzar el horizonte.** El alta genera solo las cuotas que entran (RN-13) y el resumen lo avisa: indica hasta qué cuota se generó y cuándo termina el plan. Desde HU-12, asegurar el horizonte (RN-07) genera las que faltan, con el número que les toca: sale del índice desde el inicio (RN-14), no de cuántas partidas existen, y nunca se genera después de la última cuota. *Confirmada* (HU-11). *Resuelta* en HU-12; falta verificarla con datos reales cuando cambie el mes.

**S-23. Un Concepto finalizado (su fin es anterior al período actual) se puede editar igual.** RN-15 no lo excluye, y puede tener partidas pendientes en meses pasados que todavía no se cerraron. Los cambios alcanzan solo a las partidas que existan y pasen los filtros de RN-15. *Confirmada* (HU-13).

**S-24. «Período abierto», en RN-15, es todo período no cerrado, incluidos los meses pasados que todavía no se cerraron.** Es la lectura literal de RN-08 y RN-09: lo único que congela un período es cerrarlo. Una partida pendiente de un mes pasado sin cerrar cambia con el Concepto. *Confirmada* (HU-13).

**S-25. Al cambiar la cuenta por defecto de un Concepto no se compara con la fecha de apertura de la cuenta nueva.** Es la misma razón que S-21: la fecha de apertura se controla al registrar cada movimiento (RN-21). *Sin confirmar* (agregado en HU-13).

## Técnicas

**T-01. Un repositorio con `docs`, `smartcoin-backend` y `smartcoin-frontend`, desarrollado con Claude Code.** *Confirmada.*

**T-02. Backend en Spring Boot.** Java LTS y Maven (Maven es supuesto). *Confirmada.*

**T-03. MySQL 8 local, con un solo esquema `smartcoin`.** PostgreSQL más adelante. *Confirmada.* Para facilitar la migración: migraciones Flyway en `db/migration/mysql` (con el comodín `{vendor}`), SQL estándar, enumeraciones como `VARCHAR` con `CHECK` y nombres que no son palabras reservadas en ninguno de los dos motores.

**T-04. Sin Docker ni Testcontainers por ahora.** *Confirmada.*

**T-05. Tests sin base de datos.** Las reglas de negocio se prueban con tests unitarios puros, los servicios con repositorios simulados y los controladores con `@WebMvcTest`. No hay tests de integración contra MySQL hasta tener Docker, porque la única base tiene datos reales. *Confirmada como consecuencia de T-03 y T-04.*

**T-06. Prohibido `flyway clean` y cualquier borrado automático de tablas.** *Confirmada.*

**T-07. Spring Security con JWT y BCrypt.** El token lleva el id del usuario y una versión de credenciales; cambiar o restablecer la contraseña incrementa la versión e invalida los tokens anteriores. Vigencia de 8 horas, sin refresh token. *Confirmada (la versión de credenciales y la vigencia son supuestos).*

**T-08. Clave de administración en variable de entorno.** Header `X-Admin-Key`, comparado en tiempo constante con `APP_ADMIN_KEY`. Sin la variable, el endpoint queda deshabilitado. *Confirmada.*

**T-09. El contrato manda.** springdoc genera el OpenAPI, se guarda en `docs/openapi.json` y el frontend genera su cliente desde ahí. *Confirmada.*

**T-10. Frontend en Angular**, con Angular Material como biblioteca de componentes (supuesto) y Playwright para los tests de punta a punta. *Confirmada.* La biblioteca de componentes está en revisión: ver T-19.

**T-11. Identificadores en inglés; interfaz y documentación en español.** El glosario mapea cada término. *Supuesto.*

**T-12. Dinero como `BigDecimal` y `DECIMAL(19,2)`.** En JSON viaja como número con 2 decimales y el frontend no hace cuentas con montos: todos los totales los calcula el backend. *Supuesto.*

**T-13. Fechas de negocio sin hora.** `LocalDate` y `YearMonth`. "Hoy" sale de un `Clock` con zona `America/Argentina/Mendoza`. Las fechas técnicas se guardan en UTC. *Supuesto.*

**T-14. Errores en formato Problem Details (RFC 9457)** con un campo `code` estable que el frontend traduce a un mensaje. *Supuesto.* Los errores que no vienen de una regla también llevan `code`: `INTERNAL_ERROR` (500), `METHOD_NOT_ALLOWED` (405) y `UNSUPPORTED_MEDIA_TYPE` (415). *Confirmada.*

**T-15. El proyecto se llama Smartcoin.** Paquete base y grupo Maven `com.smartcoin`; base de datos y usuario MySQL `smartcoin`. Reemplaza los nombres iniciales `ar.presupuesto` y `presupuesto`. *Confirmada.*

**T-16. Las contraseñas tienen un máximo de 72 bytes en UTF-8.** BCrypt solo usa los primeros 72 bytes y Spring Security rechaza las más largas con una excepción, que sería un 500. Se valida junto con el mínimo (RN-51) y responde `VALIDATION_ERROR`. *Supuesto* (agregado en HU-01).

**T-17. Las partidas generadas se guardan con un solo `saveAll`.** Las claves son `IDENTITY`, y con eso Hibernate no agrupa los `INSERT` por JDBC: hay una consulta para todos los períodos destino y un `INSERT` por partida (25 como máximo por Concepto), en la misma transacción. Se prefirió a un `INSERT` por JDBC en lote, que saltea JPA y las fechas de auditoría. *Confirmada* (HU-10).

**T-18. El pago rápido (HU-22) se implementa después de HU-25.** Registra un movimiento y consolida en un solo paso (RN-24), así que depende de la consolidación (HU-23) y de la elección sobre partidas editadas (HU-25). La iteración 5 queda con HU-19 a HU-21 y la 6 con HU-23, HU-24, HU-25, HU-22 y HU-26. Las reglas no cambian. *Confirmada.* (HU-22 se implementó en ese orden: D-37.)

**T-19. PrimeNG con el layout de Sakai reemplaza a Angular Material como biblioteca visual.** Prueba piloto en la rama `sakai-theme`: Angular Material (T-10) no terminó de convencer, como tampoco ng-zorro ni ArchitectUI en otras ramas. Se usa PrimeNG 22 con el preset Nora de `@primeuix/themes` (esquinas de 2 px en campos, botones, tablas y diálogos, 4 px en tarjetas, y foco de 2 px), el primario esmeralda de Nora y sin modo oscuro. Del template Sakai (MIT, `primefaces/sakai-ng`) se toman solo el layout (menú lateral, barra superior con el menú de usuario a la derecha) y sus estilos, sin las páginas de ejemplo, el configurador de tema ni Tailwind. El menú de usuario usa el menú de Angular CDK y no `p-menu`, para conservar el manejo de teclado (el foco pasa a la primera opción al abrir). Montos, fechas y períodos se siguen mostrando con los pipes propios; PrimeNG recibe una traducción al español solo para sus textos. La clave de licencia de PrimeUI (Community License, gratuita para personas) se lee de la variable de entorno `PRIMEUI_LICENSE_KEY` y se escribe en un archivo ignorado por git antes de levantar, compilar o probar. Mientras dure la migración conviven las dos bibliotecas: el layout y la vista del mes con PrimeNG y el resto con Material; los patrones y lo que falta están en `smartcoin-frontend/MIGRACION-PRIMENG.md`. *Supuesto* hasta que se apruebe la prueba; si no se aprueba, la rama se descarta.

## Cambios respecto de lo conversado

Ajustes hechos al pasar la conversación a documentos, para que Mario los revise:

| Antes | Ahora | Motivo |
|---|---|---|
| Tabla `installment_plan` | Columnas `installments_total` y `first_installment_number` en `budget_item` | Era una relación 1 a 1 opcional; así es más simple. Se agrega la primera cuota para cargar planes ya empezados. |
| `DECIMAL(19,4)` | `DECIMAL(19,2)` | Todos los montos son en pesos o dólares con centavos; el tipo de cambio no se guarda. |
| `Transaction` | `Movement` | Evita el choque con `@Transactional` y con la palabra clave de SQL. |
| Valores de enum en español (`INGRESO`, `ESTIMADA`) | En inglés (`INCOME`, `ESTIMATED`) | Coherencia con el resto de los identificadores (T-11). |
| Tablas `period` y `transaction` | `budget_period` y `movement` | Evitan palabras reservadas. |
| Estado guardado `ESTIMADA`/`PARCIAL`/`CONSOLIDADA` | Se guarda `PENDING`/`CONSOLIDATED` (`StoredEntryStatus`); Estimada y Parcial se derivan | Un estado que depende de si hay movimientos no conviene duplicarlo. |
| Colación `utf8mb4_0900_ai_ci` | `utf8mb4_0900_as_ci` | Con `ai` "Año" y "Ano" chocaban en los nombres únicos; `as` distingue acentos y sigue ignorando mayúsculas (HT-02). |
| Anular partidas con movimientos | No se eliminan (S-08) | Anular dejaba plata real fuera del presupuesto. |
| Dejar en 0 al cerrar | Cerrar con lo registrado (`CLOSE_AS_IS`) | Si la partida tenía pagos parciales, no queda en 0 sino en lo pagado. |
| `AGENTS.md` para Codex | No se usa | El proyecto se desarrolla solo con Claude Code. |

## Fuera de alcance por ahora

- Docker, Testcontainers y migración a PostgreSQL.
- Versión para celular.
- Compras individuales con tarjeta y sus cuotas.
- Días hábiles y feriados.
- Presupuestos compartidos entre usuarios.
- Conversión entre monedas, cotizaciones y totales en una sola moneda.
- Ajuste automático por inflación.
- Presupuestar transferencias (por ejemplo, una compra de dólares planificada).
- Editar "esta partida y las siguientes" en un solo paso.
- Archivar cuentas.
- Recuperación de contraseña por email, refresh tokens y bloqueo por intentos fallidos.
