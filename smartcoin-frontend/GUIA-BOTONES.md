# Guía de botones

Línea gráfica de los botones de la aplicación (T-19, tema Aura con las medidas de Sakai). Se aplica por tandas; el
estado de cada una está al final. Los íconos son de **Material Symbols Outlined**; PrimeIcons queda solo para lo interno
de los componentes de PrimeNG (la flecha de un select, el cierre de un aviso).

## Reglas

**Color por significado** (severidades del tema):

| Severidad | Para qué |
|---|---|
| `primary` (verde esmeralda) | La acción principal de la vista: crear, guardar, confirmar, avanzar. |
| `info` (celeste) | Acción secundaria neutra: cancelar, volver, editar, consolidar, consultar. |
| `success` (verde claro) | Solo mover plata: «Pagar todo» y «Cobrar todo». Se parece al primario: no usarlo para otra cosa. |
| `warn` (naranja) | Reversible o de atención: desconsolidar, postergar, «Hoy». |
| `danger` (rojo) | Solo lo destructivo: eliminar. |
| `contrast` (oscuro) | El cierre de mes, que no se puede deshacer. |
| `secondary` (gris) | Poco peso, como botón de texto: «Cancelar corrección», «Quitar filtros», menú de usuario. |

**Versión según el lugar:**

| Lugar | Versión |
|---|---|
| Tabla | Solo ícono, relleno, de 35 px, con ayuda al pasar el mouse y el nombre en `aria-label`. Cada acción en su propio lugar de la fila, para que se alineen. |
| Fila de una lista (Cuentas, Categorías, Transferencias, movimientos) | Grupo de botones con ícono y texto, en una columna fija a la derecha. |
| Formulario o diálogo | Grupo con ícono y texto: **Cancelar a la izquierda y la acción principal a la derecha**, del mismo ancho. |
| Barra superior de la pantalla | Ícono y texto; relleno para lo principal, con borde para lo secundario. |
| Poco peso | Botón de texto, sin relleno. |

**Orden y nombres:**

1. Una sola acción principal (verde, con relleno) por vista o diálogo.
2. Un nombre por acción: «Crear» (alta), «Guardar» (cambio). Se mantiene un nombre propio donde el verbo informa: «Registrar cobro», «Transferir».
3. Los íconos van con `aria-hidden="true"`; el nombre accesible es el texto o el `aria-label`.

## Por función

| Función | Color | Ícono | Tabla | Fila/lista | Formulario/diálogo | Barra superior |
|---|---|---|---|---|---|---|
| Crear / Nuevo | primary | `add` | – | – | – | ícono + texto, relleno |
| Guardar / Crear | primary | `check` | – | – | ícono + texto | – |
| Transferir | primary | `swap_horiz` | – | – | ícono + texto | – |
| Registrar cobro / pago | primary | `attach_money` | solo ícono | – | ícono + texto | – |
| Pagar todo / Cobrar todo | success | `mintmark` | solo ícono | – | ícono + texto | – |
| Consolidar | info | `lock` | solo ícono | – | ícono + texto | – |
| Desconsolidar | warn | `lock_open_right` | solo ícono | – | ícono + texto | – |
| Editar / Renombrar | info | `edit` (monto: `edit_line`) | solo ícono | ícono + texto | – | – |
| Eliminar | danger | `delete_forever` | solo ícono | ícono + texto | ícono + texto | – |
| Cancelar | info | `close` | – | – | ícono + texto | – |
| Volver | info | `arrow_back` | – | – | – | ícono + texto |
| Anterior / Siguiente | primary | `arrow_back_ios` / `arrow_forward_ios` | – | – | – | ícono + texto |
| Hoy / Ir al mes actual | warn | `today` | – | – | – | ícono + texto |
| Ver (consulta) | info | `search` | – | – | – | ícono + texto, con borde |
| Quitar filtros | secondary | `filter_alt_off` | – | – | – | texto |
| Cerrar mes | contrast | `event_available` | – | – | – | ícono + texto |
| Pasos del cierre (Siguiente / Volver) | primary / info | `arrow_forward` / `arrow_back` | – | – | ícono + texto | – |
| Postergar saldo / Cerrar con lo registrado | warn / info | `redo` / `task_alt` | – | – | ícono + texto | – |
| Ingresar | primary | `login` | – | – | ícono + texto | – |
| Cerrar sesión / Cambiar contraseña | secondary | `logout` / `key` | – | – | – | texto (menú) |

## Cómo se usa un ícono

- La fuente se carga en `src/index.html` con la lista de nombres (`icon_names`, en orden alfabético): **al sumar un
  ícono nuevo hay que agregarlo ahí**. Sin eso se ve el nombre escrito.
- En la plantilla: `<span class="material-symbols-outlined" aria-hidden="true">lock</span>`. El tamaño (1,25 rem) lo
  da `styles.scss`.

## Decisiones tomadas por defecto (a confirmar)

- «Editar» y «Editar monto» en celeste (hoy «Editar» en la tabla es gris).
- `success` solo para «Pagar todo» y «Cobrar todo».
- «Cerrar mes» en `contrast`.

## Excepciones

- **«Consolidar» en el diálogo de movimientos** va en `primary` y no en `info`: se pidió expresamente, porque es la
  acción que sigue al aviso «ya está cubierta».
- **En los diálogos** la acción que confirma va en `primary` aunque su función tenga otro color (consolidar, pagar o cobrar todo); solo «Eliminar» confirma en `danger`.
- **Ancho:** 7,5 rem por botón en formularios y listas, 9 rem en el de transferencias y 10 rem en los diálogos (los textos son más largos).

## Estado de las tandas

| Tanda | Qué | Estado |
|---|---|---|
| 0 | Esta guía | hecha |
| 1 | Base: una sola familia de íconos y su tamaño | hecha |
| 2 | Tablas y listas de las pantallas migradas | hecha |
| 3 | Formularios y diálogos migrados | hecha |
| 4 | Pantallas con la apariencia anterior (login, cambio de contraseña, reportes, asistente de cierre, diálogos de desconsolidar, eliminar partida y confirmación) | pendiente |
| 5 | Tests que dependen de textos e íconos | pendiente (se hace cuando se pidan los tests) |
