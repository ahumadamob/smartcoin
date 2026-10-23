# Migración a PrimeNG con el layout de Sakai (T-19)

Prueba piloto en la rama `sakai-theme`. La decisión es T-19 en `docs/decisiones.md`, como *Supuesto* hasta que se apruebe. Si no se aprueba, la rama se descarta.

En esta primera sesión se migraron el layout (menú lateral, barra superior y menú de usuario) y la vista del mes (`/presupuesto`), con su tabla de partidas, sus acciones y tres diálogos: registrar movimiento, eliminar movimiento (se abre desde el anterior) y consolidar. El resto sigue con Angular Material, y las dos bibliotecas conviven.

## Configuración

| Qué | Cómo |
|---|---|
| Paquetes | `primeng` 22.1.4, `@primeuix/themes` 3.1.0 y `primeicons` 8.0.2. PrimeNG trae `@primeicons/angular` y `@primeui/license-manager`. Angular CDK ya estaba. |
| Tema | `core/primeng.ts`: preset **Nora** tal cual, con su primario esmeralda. Las esquinas son de 2 px en campos, botones, tablas y diálogos y de 4 px en tarjetas, y el foco es de 2 px. Para subir todo a 4 px, se ponen en `{border.radius.sm}` los tokens `semantic.formField.borderRadius`, `semantic.content.borderRadius` y `semantic.overlay.modal.borderRadius` con `definePreset`. |
| Modo oscuro | Desactivado (`darkModeSelector: false` y `color-scheme: light`). |
| Capas CSS | PrimeNG va en la capa `primeng`. Los estilos sin capa (Material y los de cada componente) le ganan sin pelear por especificidad. Material y PrimeNG no comparten clases (`.mat-*`/`.mdc-*` contra `.p-*`) ni variables (`--mat-sys-*` contra `--p-*`). |
| Idioma | `core/primeng-es.ts`, pasado como `translation`. Solo cubre los textos propios de PrimeNG (cerrar, listas vacías, etiquetas `aria`). Montos, fechas y períodos se siguen mostrando con `money`, `DATE_FORMAT` y `period`. Las fechas se cargan con `<input type="date">` nativo con `pInputText`: no se usa `p-datepicker`. |
| Licencia | `providePrimeNG({ license })`. La clave sale de la variable de entorno `PRIMEUI_LICENSE_KEY` (en `~/.bashrc`, como `APP_ADMIN_KEY`). `scripts/primeui-license.mjs` la escribe en `src/app/core/primeui-license.generated.ts`, que está en `.gitignore`, y corre como `prestart`, `prebuild` y `pretest`. Sin la variable, la aplicación funciona, pero PrimeNG avisa en la consola y muestra el cartel «Invalid PrimeUI License». La verificación es local, sin conexión, y la clave queda dentro del JS compilado, porque se verifica en el navegador. |
| Letra | `html { font-size: 14px }`, como Sakai. Achica también, un poco, las pantallas que siguen con Material. La fuente sigue siendo Roboto/system-ui: Sakai usa Lato desde un CDN externo y no se trajo. |
| Tailwind | No se usa. Sakai solo lo necesita para el configurador de tema y las páginas de ejemplo, y su reset (preflight) desarmaría las pantallas de Material. |

### Qué se tomó de Sakai

De `primefaces/sakai-ng` (MIT, con el aviso de copyright en `src/styles/_sakai-layout.scss`):

- **Layout**: `app.layout`, `app.topbar`, `app.sidebar`, `app.menu` y `app.menuitem`, fusionados en `core/layout/layout.*`. El menú es plano: las mismas 8 entradas y rutas de antes, con íconos de PrimeIcons y `aria-current`.
- **Estilos** (submódulo `sakai-assets`): `_core`, `_main`, `_topbar`, `_menu`, `_mixins`, `_utils` y las variables, solo en su versión de escritorio con menú fijo, en `src/styles/_sakai-layout.scss`.

**Quedaron afuera**:
- Todas las páginas de ejemplo (landing, autenticación, CRUD, dashboards, demos de componentes, documentación).
- El configurador de tema y el pie.
- El menú superpuesto y la versión para celular.
- El modo oscuro.
- `_preloading`.
- `_typography`: cambia los `h1` a `h6` y los `p` de toda la aplicación, también los de Material. Los títulos se estilizan en cada pantalla.
- `chart.js`, `quill`, `primeclt` y Tailwind.

También se corrigió lo que tiene Sakai de accesibilidad: botones con solo un ícono y sin etiqueta, y un `tabindex` sobrante en los enlaces.

## Patrones

### Diálogos

- **Abrir**: `inject(Dialogs).open<Componente, Datos, Resultado>(Componente, datos, { width: '640px' })` (`core/dialogs.ts`). Devuelve un `Observable` que emite una vez **cuando el diálogo ya se cerró y el foco volvió**, como `afterClosed()` de Material: el resultado, o `undefined` si se canceló. Quien abrió el diálogo puede mover el foco después, por ejemplo cuando su botón ya no existe (HU-18).
- **Dentro del diálogo**:
  - Los datos se leen con `inject<DynamicDialogConfig<Datos>>(DynamicDialogConfig).data!`.
  - Se cierra con `inject(DynamicDialogRef).close(resultado satisfies Resultado)`.
  - El título es un `<h2 appDialogTitle>`, que le da nombre accesible al diálogo con `aria-labelledby`. PrimeNG solo lo pone cuando usa su propio encabezado, y acá el título cambia según lo que se hace.
  - Las acciones van en `<div class="dialog-actions">`, con la principal al final.
- **Lo que garantiza `Dialogs`**:
  - El diálogo es modal y atrapa el foco.
  - El foco entra al primer elemento enfocable.
  - Escape cierra solo el diálogo de arriba. Esto lo hace `Dialogs`, no `p-dialog`; ver «Trampas encontradas».
  - Al cerrar, el foco vuelve al botón que lo abrió.
  - Un diálogo puede abrir otro encima (eliminar un movimiento desde el de movimientos).
- **No mezclar**: un diálogo de PrimeNG no debe abrir uno de Material, porque la capa de Material (z-index 1000) queda debajo de la de PrimeNG (desde 1100) y los dos atrapan el foco. Por eso se migró `MovementDeleteDialog` junto con `MovementDialog`.

### Avisos

`inject(Notices).show(texto, { duration, kind?, action? })` (`core/notices.ts`) reemplaza a `MatSnackBar.open`.

- El aviso se dibuja en el `p-toast` del layout.
- Se anuncia como `status`, con `aria-live="polite"`, como el snackbar. PrimeNG usa `alert` por defecto y se cambió por pass-through.
- Las confirmaciones van con fondo oscuro neutro (`contrast`), como el snackbar, y los errores (`kind: 'error'`) en rojo.
- `action: { label, run }` agrega un botón, por ejemplo «Consolidar»: lo ejecuta y cierra el aviso.
- Mientras convivan, las pantallas de Material siguen con su snackbar.

### Campos y errores

```html
<div class="field">
  <span class="field-label">
    <label for="movement-amount">Monto</label><span class="required" aria-hidden="true">*</span>
  </span>
  <input pInputText id="movement-amount" formControlName="amount" required
    [invalid]="invalid('amount')" [attr.aria-invalid]="invalid('amount')"
    [attr.aria-describedby]="invalid('amount') ? 'movement-amount-error' : 'movement-amount-hint'" />
  @if (invalid('amount')) {
    <p-message id="movement-amount-error" severity="error" variant="simple" size="small">…</p-message>
  } @else {
    <small id="movement-amount-hint" class="hint">Con coma decimal, por ejemplo 1500,50.</small>
  }
</div>
```

- `invalid(campo)` es `control.invalid && control.touched`. `submit` marca todos los campos como tocados, así que el error aparece en el mismo momento que con `mat-error`.
- El asterisco de obligatorio va **fuera** de la `<label>`. Si queda adentro, el nombre del campo pasa a ser «Monto *» y `getByLabel('Monto', { exact: true })` deja de encontrarlo.
- Ante un error del backend en un campo, el foco va a ese campo por su `id` (`FIELD_IDS` en `MovementDialog`), no por `formcontrolname`: en `p-select` el elemento enfocable es el combobox interno.
- **`p-select`**:
  - `inputId` (el id del combobox) y `ariaLabelledBy` apuntando al `id` de la `<label>`.
  - La ayuda y el error se enlazan con `[pt]="{ label: { 'aria-describedby': … } }"`.
  - Las opciones se muestran ya formateadas, por ejemplo con `period`.
- **Radios**: `p-radiobutton` con `inputId`, una `<label for>` al lado y un contenedor `role="radiogroup"` con `aria-labelledby`. La descripción de cada opción se enlaza con `[pt]="{ input: { 'aria-describedby': … } }"`.
- **Foco visible**: `src/styles/_patterns.scss` agrega a `.p-inputtext`, `.p-select` y `.p-radiobutton` el mismo anillo de 2 px que tienen los botones. Nora solo cambiaba el color del borde, y en `p-select` ni eso al llegar con el teclado.

### Tablas

- **Estructura**: `p-table` con plantillas `#header`, `#body` y `#footer`. Adentro, `<th scope="col">` y `<th scope="row">` como antes. El nombre accesible se pone con `[pt]="{ table: { 'aria-label': … } }"`.
- **`rowTrackBy`**: siempre por id (`trackById`). Si no, `p-table` compara por objeto, y como cada respuesta del backend trae objetos nuevos, recrea las filas y se pierde el foco.
- **Tipos**: la fila llega sin tipo (`let-row`); se recupera con `@let entry = asEntry(row)`.
- **Tamaño**: `size="small"`.
- **Tablas chicas de los diálogos**: siguen como `<table>` común con los tokens del tema.

### Botones

| Material | PrimeNG |
|---|---|
| `mat-flat-button` | `pButton` |
| `mat-stroked-button` | `pButton variant="outlined"` |
| `mat-button` | `pButton variant="text"` (en las filas de una tabla, también `size="small"`) |
| «Cancelar» | `pButton variant="text" severity="secondary"` |
| Confirmación destructiva | `pButton severity="danger"` |
| Enlace con aspecto de botón | `<a pButton …>` (sin subrayado, por `styles.scss`) |

Se usa la directiva `pButton` sobre `<button>` y `<a>` nativos, no el componente `p-button`: así se conservan `type`, `data-testid`, `aria-label` y `routerLink`.

### Menús emergentes

El menú de usuario usa el menú de Angular CDK (`cdkMenuTriggerFor`, `cdkMenu`, `cdkMenuItem`) con el aspecto del tema, y no `p-menu`:
- `p-menu` deja el foco en la lista (`aria-activedescendant`) y no marca ninguna opción al abrir.
- El de CDK mueve el foco real: al abrir con Enter va a la primera opción, las flechas recorren y Escape cierra devolviendo el foco, como el menú de Material.

### Íconos

PrimeIcons solo como decoración: siempre con `aria-hidden="true"` y al lado de un texto. Un botón con solo un ícono lleva `aria-label`.

### Tests

- **Unitarios de diálogos**: se proveen `{ provide: DynamicDialogConfig, useValue: { data } }` y `{ provide: DynamicDialogRef, useValue: { close: vi.fn() } }`. Si el diálogo abre otro, se simula `Dialogs`.
- **Unitarios de pantallas**: se simulan `Dialogs` (`open` devuelve `of(resultado)`) y `Notices` (`show: vi.fn()`).
- **Selectores**: `mat-form-field` pasa a ser `.field`, `mat-error` pasa a ser `p-message` y `mat-dialog-actions` pasa a ser `.dialog-actions`.
- **e2e**: buscan por rol, etiqueta y `data-testid`, y en general no cambian. El menú lateral ahora es una lista: un `getByRole('listitem')` sin acotar cuenta también sus 8 entradas, así que se acota a `main`.

## Trampas encontradas

- **Escape en `p-dialog`**: solo cierra si su z-index es el último que anotó PrimeNG. Después de abrir y cerrar la lista de un `p-select` adentro, eso deja de cumplirse y Escape no hace nada. `Dialogs` maneja Escape con su propia pila de diálogos abiertos y respeta el Escape que ya consumió un `p-select` abierto.
- **Escape requiere `closable`**: PrimeNG solo escucha Escape si `closable` es verdadero. Con el manejo propio, se dejan `closable` y `closeOnEscape` en falso.
- **Sin encabezado de PrimeNG**:
  - El diálogo no tiene `aria-labelledby`; lo resuelve `appDialogTitle`.
  - El contenido no tiene relleno arriba; lo resuelve `.app-dialog .p-dialog-content` en `_patterns.scss`.
- **Disparador de `p-select`**: es un `role="button"` con la etiqueta en inglés «dropdown trigger». Se oculta a los lectores de pantalla con un pass-through global en `core/primeng.ts`.
- **Contraste del aviso**: el aviso `success` de Nora es verde lleno, y un botón `outlined` adentro casi no se ve. Por eso se usa `contrast` con el botón en `secondary`.
- **Tamaño del bundle**: el bundle inicial pasó de 384 kB (95 kB comprimido) en `main` a 776 kB (168 kB comprimido). El aumento está en `main.js`, que se carga al arrancar: el preset Nora completo (unos 124 kB sin comprimir, con los tokens de todos los componentes), el motor de estilos de PrimeNG, su configuración y la verificación de la licencia. Supera el aviso de 500 kB de `angular.json`, pero no el error de 1 MB. Sacar Material va a ahorrar algo; para bajarlo en serio hay que investigar si el tema se puede cargar solo con los componentes que se usan.

## Lo que falta migrar

Cada pantalla, en corte vertical: plantilla, estilos, spec y, si hace falta, el selector del e2e. Por pantalla, de menor a mayor esfuerzo:

| Pantalla | Archivos | Material que usa |
|---|---|---|
| Vista del mes (diálogos restantes) | `features/budget/entry-form-dialog.*`, `entry-delete-dialog.*`, `quick-settle-dialog.*`, `unconsolidate-dialog.*` y, en `budget-month.ts`, el `MatDialog` que los abre | dialog, button, form-field, input, select, radio |
| Confirmación genérica | `shared/confirm-dialog.ts` (la usan cuentas, categorías y transferencias) | dialog, button |
| Login | `features/auth/login/*` | card, form-field, input, button |
| Cambio de contraseña | `features/auth/change-password/*` | card, form-field, input, button, snack-bar |
| Categorías | `features/categories/*` | form-field, input, button, snack-bar, dialog |
| Cuentas | `features/accounts/*` (lista y `account-form`) | form-field, input, select, button, snack-bar, dialog, card |
| Transferencias | `features/transfers/*` (lista y `transfer-form`) | form-field, input, select, button, snack-bar, dialog |
| Conceptos | `features/budget-items/*` (lista, alta, edición y formulario) | form-field, input, select, radio, checkbox, button, snack-bar, dialog, card |
| Informes | `features/reports/cash-flow.ts`, `projection.ts`, `budget-grid.ts` (los filtros; las tablas son HTML común) | form-field, input, select, button |
| Cierre de mes | `features/closing/closing-wizard.*` | form-field, input, radio, checkbox, button, snack-bar, card |

Specs que hoy dependen de Material: `accounts`, `change-password`, `budget-month` (por los cuatro diálogos que quedan), `entry-delete-dialog`, `entry-form-dialog`, `budget-item-edit`, `budget-item-form-edit`, `quick-settle-dialog`, `unconsolidate-dialog`, `categories`, `closing-wizard` y `transfers`.

e2e con selectores de Material:
- `auth.spec.ts`: `mat-snack-bar-container`, dos veces.
- `cierre-de-mes.spec.ts`: `mat-error`.
- `partidas.spec.ts`: `mat-error`, en el diálogo de editar monto.

**Al terminar**:
- Sacar `@angular/material` de `package.json` y el `mat.theme` de `styles.scss`.
- Revisar el presupuesto de tamaño en `angular.json`.
- Actualizar el stack en `smartcoin-frontend/CLAUDE.md`.
- Pasar T-19 a *Confirmada* y ajustar T-10.
