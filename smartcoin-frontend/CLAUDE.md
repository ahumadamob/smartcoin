# Frontend

Aplicación Angular para escritorio. Leer primero el `CLAUDE.md` de la raíz.

## Stack

- Angular, versión estable más reciente al crear el proyecto con Angular CLI. TypeScript en modo estricto.
- Componentes standalone, signals para el estado y formularios reactivos tipados.
- Angular Material para los componentes de interfaz.
- Cliente HTTP generado desde `../docs/openapi.json` con OpenAPI Generator (`typescript-angular`).
- Playwright para los tests de punta a punta.

## Comandos

| Qué | Comando |
|---|---|
| Levantar | `npm start` (`ng serve` con proxy de `/api` a `http://localhost:8080`, definido en `proxy.conf.json`) |
| Tests unitarios | `npm test` (el runner que trae el Angular CLI instalado) |
| Regenerar el cliente | `npm run generate:api` |
| Tests de punta a punta | `npx playwright test` (con backend y frontend levantados) |
| Build | `npm run build` |

En `package.json`, con `@openapitools/openapi-generator-cli` como dependencia de desarrollo (necesita Java, que ya está por el backend):

```json
"generate:api": "openapi-generator-cli generate -i ../docs/openapi.json -g typescript-angular -o src/app/api"
```

## Estructura

```
src/app/
├── api/            generado; nunca se edita a mano
├── core/           autenticación (servicio, interceptor, guards), layout, manejo de errores, mensajes
├── shared/         pipes (moneda, período), componentes reutilizables
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
| Cambio de contraseña | `/cambiar-contrasena` | HU-04, HU-05 |
| Presupuesto del mes | `/presupuesto/:period` (sin período, el actual) | HU-15 a HU-26 |
| Conceptos | `/conceptos` | HU-10 a HU-14 |
| Cuentas | `/cuentas` | HU-07, HU-08 |
| Categorías | `/categorias` | HU-09 |
| Transferencias | `/transferencias` | HU-27 a HU-29 |
| Cierre de mes | `/cierre/:period` | HU-30 a HU-33 |
| Flujo de caja | `/flujo-de-caja` | HU-34 |
| Proyección | `/proyeccion` | HU-35 |
| Varios meses | `/planificacion` | HU-36 |

Las rutas están en español porque el usuario las ve; el código, en inglés.

## Convenciones

- **Autenticación**: el token se guarda en `sessionStorage`. Un interceptor agrega `Authorization: Bearer`. Ante un 401, se descarta el token y se va al login. Ante un 403 `PASSWORD_CHANGE_REQUIRED`, se va al cambio de contraseña.
- **Guards**: sin token, solo `/login`. Con cambio de contraseña pendiente, solo `/cambiar-contrasena`.
- **Locale `es-AR`**: montos con el pipe de moneda (`$ 1.234,50` y `US$ 1.234,50`), fechas `dd/MM/yyyy`, períodos como "noviembre 2026".
- **Dinero**: el frontend nunca suma, resta ni promedia montos. Todo total, pendiente o diferencia viene calculado del backend. Los campos de monto aceptan coma decimal y se convierten a número antes de enviar.
- **Reglas de negocio**: el frontend valida formato (obligatorios, mayor que 0, fechas válidas). Las reglas (ventana de anticipación, monedas, cierres, alcances) las decide el backend y el frontend muestra su error.
- **Errores**: un único archivo de mensajes traduce cada `code` de Problem Details a un texto en español. Si un código no está, se muestra el `detail` del backend.
- **Confirmaciones explícitas**: eliminar con alcance, consolidar con partidas editadas en el destino, pago rápido y cerrar el mes usan diálogos que muestran exactamente qué va a pasar (con la vista previa o la simulación del backend cuando existe).
- **Textos**: interfaz en español con el vocabulario del glosario (Concepto, Partida, Movimiento, Consolidar, Cerrar el mes). En el código, los nombres en inglés del glosario.
- **Accesibilidad básica**: todo campo con etiqueta, navegación con teclado y foco visible.

## Tests de punta a punta

- En `e2e/`, un archivo por épica.
- Nunca usan el usuario real. Cada corrida crea un usuario propio con el endpoint de administración (`APP_ADMIN_KEY` leída de una variable de entorno), con un email único como `e2e-<timestamp>@prueba.local` y un período inicial dos meses antes del actual, para poder probar cierres.
- Como los datos se aíslan por usuario, no tocan los datos reales. Esos usuarios quedan en la base; limpiarlos es una tarea pendiente para cuando haya Docker.
- Claude Code puede ejecutarlos y revisar capturas y trazas cuando un test falla.
