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

**S-12. Tipo, periodicidad, período de inicio, período de fin y cuotas de un Concepto no se editan** (RN-15). Para cambiarlos se da de baja y se crea otro. En una baja el fin se fija (HU-18), pero eso no es editarlo.

**S-13. La cuenta tiene fecha de apertura y saldo inicial a esa fecha.** No se aceptan movimientos anteriores. Por defecto, la fecha de apertura es el primer día del período inicial del usuario. La fecha de apertura debe estar entre el primer día del período inicial y hoy, ambos inclusive: no puede ser futura. *Confirmada.*

**S-14. El usuario tiene un período inicial**, que se define al crearlo (por defecto, el mes actual). No existen períodos anteriores.

**S-15. El administrador puede restablecer contraseñas** con la misma clave especial del alta. Sin envío de emails, es la única forma de recuperar el acceso.

**S-16. Montos con 2 decimales y redondeo `HALF_UP`.** Solo el promedio necesita redondeo.

**S-17. Categorías opcionales**, sin tipo (sirven para ingresos y gastos).

**S-18. Flujo de caja, proyección de saldos y vista de varios meses (HU-34 a HU-36) son propuestas.** Se confirman antes de implementarlas.

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

**T-10. Frontend en Angular**, con Angular Material como biblioteca de componentes (supuesto) y Playwright para los tests de punta a punta. *Confirmada.*

**T-11. Identificadores en inglés; interfaz y documentación en español.** El glosario mapea cada término. *Supuesto.*

**T-12. Dinero como `BigDecimal` y `DECIMAL(19,2)`.** En JSON viaja como número con 2 decimales y el frontend no hace cuentas con montos: todos los totales los calcula el backend. *Supuesto.*

**T-13. Fechas de negocio sin hora.** `LocalDate` y `YearMonth`. "Hoy" sale de un `Clock` con zona `America/Argentina/Mendoza`. Las fechas técnicas se guardan en UTC. *Supuesto.*

**T-14. Errores en formato Problem Details (RFC 9457)** con un campo `code` estable que el frontend traduce a un mensaje. *Supuesto.* Los errores que no vienen de una regla también llevan `code`: `INTERNAL_ERROR` (500), `METHOD_NOT_ALLOWED` (405) y `UNSUPPORTED_MEDIA_TYPE` (415). *Confirmada.*

**T-15. El proyecto se llama Smartcoin.** Paquete base y grupo Maven `com.smartcoin`; base de datos y usuario MySQL `smartcoin`. Reemplaza los nombres iniciales `ar.presupuesto` y `presupuesto`. *Confirmada.*

**T-16. Las contraseñas tienen un máximo de 72 bytes en UTF-8.** BCrypt solo usa los primeros 72 bytes y Spring Security rechaza las más largas con una excepción, que sería un 500. Se valida junto con el mínimo (RN-51) y responde `VALIDATION_ERROR`. *Supuesto* (agregado en HU-01).

**T-17. Las partidas generadas se guardan con un solo `saveAll`.** Las claves son `IDENTITY`, y con eso Hibernate no agrupa los `INSERT` por JDBC: hay una consulta para todos los períodos destino y un `INSERT` por partida (25 como máximo por Concepto), en la misma transacción. Se prefirió a un `INSERT` por JDBC en lote, que saltea JPA y las fechas de auditoría. *Confirmada* (HU-10).

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
