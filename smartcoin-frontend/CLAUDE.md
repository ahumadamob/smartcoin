# Frontend

Aplicación Angular para escritorio. Leer primero el `CLAUDE.md` de la raíz.

## Stack

- Angular 22 (la versión estable más reciente al crear el proyecto con Angular CLI) y Angular Material 22. TypeScript en modo estricto (`strict: true` explícito en `tsconfig.json`).
- Componentes standalone, signals para el estado y formularios reactivos tipados.
- Angular Material para los componentes de interfaz.
- Cliente HTTP generado desde `../docs/openapi.json` con OpenAPI Generator 7.25.0 (`typescript-angular`, versión fijada en `openapitools.json`; soporta Angular 22 por defecto).
- Tests unitarios con Vitest (runner por defecto de `ng test` desde Angular 21), con jsdom.
- Playwright para los tests de punta a punta.

## Comandos

| Qué | Comando |
|---|---|
| Levantar | `npm start` (`ng serve --proxy-config proxy.conf.json`: `/api` va a `http://localhost:8080`) |
| Tests unitarios | `npm test` (Vitest vía `ng test`; en una terminal interactiva queda en modo watch, con `-- --watch=false` corre una vez) |
| Regenerar el cliente | `npm run generate:api` |
| Tests de punta a punta | `npm run e2e` o `npx playwright test` (levanta el frontend si no está corriendo; el backend, cuando un test lo necesite, hay que levantarlo aparte) |
| Build | `npm run build` |

En `package.json`, con `@openapitools/openapi-generator-cli` como dependencia de desarrollo (necesita Java, que ya está por el backend):

```json
"generate:api": "openapi-generator-cli generate -i ../docs/openapi.json -g typescript-angular -o src/app/api"
```

## Estructura

```
src/app/
├── api/            generado; nunca se edita a mano
├── core/           autenticación (servicio, interceptor, guards), layout, manejo de errores, mensajes, locale, título de pestaña
├── shared/         pipes (`money`, `period`), `amount` (montos con coma decimal a número), componentes reutilizables (`page-placeholder` para las pantallas aún vacías)
└── features/
    ├── auth/           login, cambio de contraseña
    ├── budget/         vista del mes, partidas, movimientos, consolidación
    ├── budget-items/   Conceptos
    ├── accounts/
    ├── categories/
    ├── transfers/
    ├── closing/        asistente de cierre
    └── reports/        flujo de caja, proyección, vista de varios meses
```

## Pantallas

| Pantalla | Ruta | Historias |
|---|---|---|
| Login | `/login` | HU-03 |
| Cambio de contraseña | `/cambiar-contrasena` (obligatorio, fuera del layout) o dentro del layout (voluntario) | HU-04, HU-05 |
| Presupuesto del mes | `/presupuesto/:period` (sin período, el actual, que decide el backend). Las dos URL son una sola ruta (`budgetMonthMatcher`), para no recrear la pantalla al cambiar de mes | HU-15 a HU-26 |
| Conceptos (lista) | `/conceptos` | HU-14 |
| Nuevo Concepto | `/conceptos/nuevo` | HU-10, HU-11 |
| Editar un Concepto | `/conceptos/:id/editar` | HU-13 |
| Cuentas | `/cuentas` | HU-07, HU-08 |
| Categorías | `/categorias` | HU-09 |
| Transferencias | `/transferencias` | HU-27 a HU-29 |
| Cierre de mes | `/cierre/:period` | HU-30 a HU-33 |
| Flujo de caja | `/flujo-de-caja` | HU-34 |
| Proyección | `/proyeccion` | HU-35 |
| Varios meses | `/planificacion` | HU-36 |

Las rutas están en español porque el usuario las ve; el código, en inglés.

El login y el cambio de contraseña obligatorio van fuera del layout; el resto, incluido el cambio de contraseña voluntario (HU-05), va dentro, con menú lateral. El pie del menú lateral tiene el menú de usuario (`UserMenu`): muestra el email del usuario (`AuthService.email`, que sale de `GET /api/auth/me` y no se guarda en el almacenamiento) y ofrece "Cambiar contraseña" y "Cerrar sesión". El menú lista Presupuesto, Conceptos, Cuentas, Categorías, Transferencias, Flujo de caja, Proyección y Varios meses; el cierre de mes se abre desde la pantalla del mes. Mientras una historia no implementa su pantalla, la ruta carga `PagePlaceholder`.

## Convenciones

- **Autenticación**: el token se guarda en `sessionStorage`. Un interceptor agrega `Authorization: Bearer`. Ante un 401, se descarta el token y se va al login. Ante un 403 `PASSWORD_CHANGE_REQUIRED`, se va al cambio de contraseña.
- **Guards**: sin token, solo `/login`. Con cambio de contraseña pendiente, solo `/cambiar-contrasena`. El estado pendiente viaja en la sesión guardada (`mustChangePassword`), que el login, el cambio de contraseña y el 403 `PASSWORD_CHANGE_REQUIRED` actualizan. La URL `/cambiar-contrasena` tiene dos rutas (`app.routes.ts`): la obligatoria, fuera del layout, que solo coincide (`canMatch: mandatoryPasswordChange`) mientras el cambio está pendiente, y la voluntaria, dentro del layout, que sirve a quien ya tiene sesión sin cambio pendiente. El modo llega a `ChangePassword` por `data.mandatory`. Voluntario: "Cancelar" y, al terminar, vuelven a donde estaba el usuario (`returnUrl` en el estado de la navegación, que pasa el menú; sin él, `/presupuesto`). Obligatorio: sin salida salvo cerrar sesión; al terminar va a `/presupuesto`. En los dos modos se confirma con un snackbar "Contraseña cambiada.". El formulario es el componente `ChangePasswordForm`, que emite `changed` al terminar.
- **Locale `es-AR`**: registrado en `core/locale.ts`. Montos con el pipe `money` (`$ 1.234,50` y `US$ 1.234,50`, con espacio común), fechas con la constante `DATE_FORMAT` (`dd/MM/yyyy`), períodos con el pipe `period` ("noviembre 2026").
- **Dinero**: el frontend nunca suma, resta ni promedia montos. Todo total, pendiente o diferencia viene calculado del backend. Los campos de monto aceptan coma decimal y se convierten a número antes de enviar.
- **Reglas de negocio**: el frontend valida formato (obligatorios, mayor que 0, fechas válidas). Las reglas (ventana de anticipación, monedas, cierres, alcances) las decide el backend y el frontend muestra su error.
- **Errores**: un único archivo de mensajes traduce cada `code` de Problem Details a un texto en español. Si un código no está, se muestra el `detail` del backend. `VALIDATION_ERROR` muestra el primer error por campo o, si no hay, el `detail`, porque ahí está la causa (por ejemplo, "la contraseña nueva debe ser distinta de la actual").
- **Confirmaciones explícitas**: eliminar con alcance, consolidar con partidas editadas en el destino, pago rápido y cerrar el mes usan diálogos que muestran exactamente qué va a pasar (con la vista previa o la simulación del backend cuando existe).
- **Textos**: interfaz en español con el vocabulario del glosario (Concepto, Partida, Movimiento, Consolidar, Cerrar el mes). En el código, los nombres en inglés del glosario.
- **Accesibilidad básica**: todo campo con etiqueta, navegación con teclado y foco visible. Las marcas de estado (Vencida, Editada, Cerrado) son texto, no solo color.
- **Vista del mes**: las acciones del período van en la cabecera de `BudgetMonth` y las de cada partida en la última columna de `EntryTable`; las dos dependen de `readonly` (período cerrado). La columna «Acciones» solo existe si alguna partida de la sección tiene una («Registrar pago» en los gastos y «Registrar cobro» en los ingresos pendientes, `canRegisterMovement`, HU-19; «Editar» en las partidas sin Concepto y no consolidadas, `canEditEntry`; «Editar monto» en las recurrentes pendientes, `canEditAmount`; «Eliminar» en las pendientes, `canDeleteEntry`): no se dibuja vacía. Después de guardar, la vista se vuelve a pedir al backend sin desmontar las tablas, para no perder el foco, y el foco se devuelve al botón de la partida (la acción de registrar, o cualquiera de las dos de edición, según la que se usó). El diálogo de registrar (`MovementDialog`) muestra presupuestado, real y pendiente tal como vienen en la fila, lista los movimientos con `GET /api/entries/{id}/movements` y, al guardar, el mensaje y el aviso de «pendiente 0» salen de la partida que devuelve el backend. Después de eliminar, la fila ya no existe: el foco pasa a la acción de la fila que ocupa su lugar, si no a la anterior y, si la sección quedó vacía, a su encabezado (HU-18). El diálogo de eliminar pide la vista previa al backend (`GET /api/entries/{id}/deletion-preview`) y muestra exactamente qué va a pasar; no repite reglas.

## Tests de punta a punta

- En `e2e/`, un archivo por épica.
- Nunca usan el usuario real. Cada corrida crea un usuario propio con el endpoint de administración (`APP_ADMIN_KEY` leída de una variable de entorno), con un email único como `e2e-<timestamp>@prueba.local` y un período inicial dos meses antes del actual, para poder probar cierres.
- Como los datos se aíslan por usuario, no tocan los datos reales. Esos usuarios quedan en la base; limpiarlos es una tarea pendiente para cuando haya Docker.
- Los helpers para crear el usuario de prueba y dejar la sesión guardada están en `e2e/support.ts`.
- Los tests de `base-tecnica.spec.ts` usan un token falso: toda pantalla que llame a la API desde su historia suma ahí su respuesta simulada (`page.route`), si no el 401 los manda al login.
- Claude Code puede ejecutarlos y revisar capturas y trazas cuando un test falla.
