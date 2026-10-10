import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { API_URL, PASSWORD, startPeriod, startSession } from './support';

/**
 * Épica 4 · Partidas. HU-16 (agregar una partida puntual y editar las partidas sin Concepto), HU-17 (editar el monto
 * de una partida recurrente) y HU-18 (eliminar una partida, sola o de un Concepto con alcance). Cada describe usa un
 * usuario propio: lo que se elimina es siempre de un usuario de prueba, nunca del real.
 */

const MONTHS = [
  'enero',
  'febrero',
  'marzo',
  'abril',
  'mayo',
  'junio',
  'julio',
  'agosto',
  'septiembre',
  'octubre',
  'noviembre',
  'diciembre',
];

/** El día 1 del mes actual corrido `offset` meses. */
function month(offset: number): Date {
  const date = new Date();
  date.setDate(1);
  date.setMonth(date.getMonth() + offset);
  return date;
}

const two = (n: number) => String(n).padStart(2, '0');
/** `YYYY-MM-DD`, como lo espera el campo de fecha. */
const isoDate = (date: Date, day = date.getDate()) => `${date.getFullYear()}-${two(date.getMonth() + 1)}-${two(day)}`;
/** `dd/MM/yyyy`, como lo muestra la pantalla. */
const dateText = (date: Date, day = date.getDate()) => `${two(day)}/${two(date.getMonth() + 1)}/${date.getFullYear()}`;
const periodText = (date: Date) => `${MONTHS[date.getMonth()]} ${date.getFullYear()}`;
const lastDayOf = (date: Date) => new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate();

async function chooseOption(page: Page, label: string, option: string) {
  await page.getByLabel(label, { exact: true }).click();
  await page.getByRole('option', { name: option, exact: true }).click();
}

test.describe('HU-16 · agregar una partida puntual', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let api: APIRequestContext;

  // La cuenta, las categorías y el Concepto del sueldo se cargan por la API: sus pantallas las cubren otras épicas.
  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    const { token } = await startSession(page, playwright);
    api = await playwright.request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${token}` },
    });
    const create = async (path: string, data: object) => {
      const response = await api.post(path, { data });
      expect(response.status(), `alta en ${path}: ${JSON.stringify(data)}`).toBe(201);
      return (await response.json()).id as number;
    };
    const account = (name: string, currency: string) =>
      create('/api/accounts', { name, type: 'BANK', currency, openingDate: `${startPeriod()}-01`, initialBalance: 0 });
    const pesos = await account('Banco Nación', 'ARS');
    await account('Caja en dólares', 'USD');
    await create('/api/categories', { name: 'Hogar' });
    await create('/api/categories', { name: 'Servicios' });
    await create('/api/budget-items', {
      name: 'Sueldo',
      kind: 'INCOME',
      defaultAccountId: pesos,
      periodicity: 'MONTHLY',
      dueDay: 5,
      dueMonthOffset: 0,
      startPeriod: `${month(0).getFullYear()}-${two(month(0).getMonth() + 1)}`,
      estimationRule: 'LAST_VALUE',
      currentAmount: 1200000,
    });
    await page.goto('/presupuesto');
  });

  test.afterAll(async () => {
    await api.dispose();
    await page.close();
  });

  const section = (name: string) => page.getByRole('region', { name, exact: true });
  const names = (name: string) => section(name).getByTestId('name');
  const row = (sectionName: string, entry: string) =>
    section(sectionName).locator('tbody tr').filter({ has: page.getByRole('rowheader', { name: entry, exact: true }) });
  const cells = (locator: ReturnType<typeof row>) => locator.locator('th, td');
  const totals = (name: string) => section(name).getByTestId('total');
  const results = () => section('Resultado').getByTestId('result');
  const dialog = () => page.getByRole('dialog');
  const addButton = () => page.getByRole('button', { name: 'Agregar partida' });

  async function fill(entry: {
    name: string;
    kind?: string;
    account?: string;
    category?: string;
    dueDate?: string;
    amount: string;
  }) {
    await dialog().getByLabel('Nombre', { exact: true }).fill(entry.name);
    if (entry.kind) {
      await chooseOption(page, 'Tipo', entry.kind);
    }
    if (entry.account) {
      await chooseOption(page, 'Cuenta', entry.account);
    }
    if (entry.category) {
      await chooseOption(page, 'Categoría', entry.category);
    }
    if (entry.dueDate) {
      await dialog().getByLabel('Vencimiento').fill(entry.dueDate);
    }
    await dialog().getByLabel('Presupuestado').fill(entry.amount);
  }

  test('el mes arranca solo con la partida recurrente, que solo ofrece «Editar monto»', async () => {
    await expect(page.getByRole('heading', { level: 1, name: `Presupuesto de ${periodText(month(0))}` })).toBeVisible();
    await expect(addButton()).toBeVisible();
    await expect(names('Ingresos')).toHaveText(['Sueldo']);
    // Una recurrente no se edita acá (RN-18, HU-16): solo ofrece cambiar su monto (HU-17).
    await expect(page.getByRole('button', { name: /^Editar/ })).toHaveCount(1);
    await expect(page.getByRole('button', { name: 'Editar monto de Sueldo' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Editar Sueldo', exact: true })).toHaveCount(0);
  });

  test('agregar un gasto puntual en el mes actual: aparece y cambian los totales', async () => {
    await addButton().click();

    await expect(dialog().getByRole('heading', { name: `Agregar partida de ${periodText(month(0))}` })).toBeVisible();
    // El vencimiento sugerido es hoy, dentro del período que se está viendo.
    await expect(dialog().getByLabel('Vencimiento')).toHaveValue(isoDate(new Date()));
    // Con el teclado: el primer campo ya tiene el foco.
    await expect(dialog().getByLabel('Nombre', { exact: true })).toBeFocused();

    await fill({
      name: 'Service del auto',
      kind: 'Gasto',
      account: 'Banco Nación · $ (ARS)',
      category: 'Hogar',
      amount: '85.000,50',
    });
    await dialog().getByRole('button', { name: 'Guardar' }).click();

    await expect(dialog()).toHaveCount(0);
    await expect(page.getByText('Partida «Service del auto» agregada.')).toBeVisible();
    await expect(names('Gastos')).toHaveText(['Service del auto']);
    await expect(cells(row('Gastos', 'Service del auto'))).toHaveText([
      new RegExp(`^\\s*${dateText(new Date())}\\s*$`),
      'Service del auto',
      'Hogar',
      'Banco Nación',
      '—',
      '$ 85.000,50',
      '$ 0,00',
      '$ 85.000,50',
      'Estimada',
      'Editar Eliminar',
    ]);
    // Los totales los calcula el backend: gastos 85.000,50 y resultado 1.200.000,00 − 85.000,50.
    await expect(cells(totals('Gastos'))).toHaveText([
      'Total en pesos',
      '$ 85.000,50',
      '$ 0,00',
      '$ 85.000,50',
      '',
      '',
    ]);
    await expect(cells(results())).toHaveText(['Pesos', '$ 1.200.000,00', '$ 85.000,50', '$ 1.114.999,50']);
    await expect(row('Gastos', 'Service del auto').getByText('Editada')).toHaveCount(0);
    // Al cerrar el diálogo el foco vuelve al botón con el que se abrió.
    await expect(addButton()).toBeFocused();
  });

  test('la partida puntual no aparece en el mes siguiente', async () => {
    await page.getByRole('button', { name: 'Mes siguiente' }).click();

    await expect(page.getByRole('heading', { level: 1, name: `Presupuesto de ${periodText(month(1))}` })).toBeVisible();
    await expect(names('Ingresos')).toHaveText(['Sueldo']);
    await expect(names('Gastos')).toHaveCount(0);
    await expect(section('Gastos')).toContainText('No hay gastos en este mes.');
    await expect(results()).toHaveCount(1);
    await expect(cells(results())).toHaveText(['Pesos', '$ 1.200.000,00', '$ 0,00', '$ 1.200.000,00']);

    await page.getByRole('link', { name: 'Hoy' }).click();
    await expect(page.getByRole('heading', { level: 1, name: `Presupuesto de ${periodText(month(0))}` })).toBeVisible();
    await expect(names('Gastos')).toHaveText(['Service del auto']);
  });

  test('editar la partida: nombre, categoría y monto; no queda marcada como editada y el foco vuelve a su botón', async () => {
    await page.getByRole('button', { name: 'Editar Service del auto' }).click();

    // Carga sus datos; el tipo no se edita.
    await expect(dialog().getByRole('heading', { name: 'Editar partida' })).toBeVisible();
    await expect(dialog().getByLabel('Nombre', { exact: true })).toHaveValue('Service del auto');
    await expect(dialog().getByLabel('Presupuestado')).toHaveValue('85000,50');
    await expect(dialog().getByLabel('Vencimiento')).toHaveValue(isoDate(new Date()));
    await expect(dialog().getByLabel('Tipo', { exact: true })).toHaveAttribute('aria-disabled', 'true');

    await dialog().getByLabel('Nombre', { exact: true }).fill('Cambio de aceite');
    await chooseOption(page, 'Categoría', 'Servicios');
    await dialog().getByLabel('Presupuestado').fill('90.000');
    await dialog().getByRole('button', { name: 'Guardar' }).click();

    await expect(dialog()).toHaveCount(0);
    await expect(page.getByText('Partida «Cambio de aceite» guardada.')).toBeVisible();
    await expect(names('Gastos')).toHaveText(['Cambio de aceite']);
    await expect(row('Gastos', 'Cambio de aceite').getByTestId('budgeted')).toHaveText('$ 90.000,00');
    await expect(cells(row('Gastos', 'Cambio de aceite')).nth(2)).toHaveText('Servicios');
    await expect(cells(totals('Gastos'))).toHaveText(['Total en pesos', '$ 90.000,00', '$ 0,00', '$ 90.000,00', '', '']);
    await expect(cells(results())).toHaveText(['Pesos', '$ 1.200.000,00', '$ 90.000,00', '$ 1.110.000,00']);
    // RN-18: una partida sin Concepto no se marca como editada.
    await expect(page.getByText('Editada', { exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Editar Cambio de aceite' })).toBeFocused();
  });

  test('vaciar la categoría la deja sin categoría', async () => {
    await page.getByRole('button', { name: 'Editar Cambio de aceite' }).click();
    await chooseOption(page, 'Categoría', 'Sin categoría');
    await dialog().getByRole('button', { name: 'Guardar' }).click();

    await expect(dialog()).toHaveCount(0);
    await expect(cells(row('Gastos', 'Cambio de aceite')).nth(2)).toHaveText('—');
  });

  test('un gasto en dólares: los totales y el resultado quedan separados por moneda', async () => {
    await addButton().click();
    await fill({
      name: 'Compra de bici',
      kind: 'Gasto',
      account: 'Caja en dólares · US$ (USD)',
      amount: '300,00',
    });
    await dialog().getByRole('button', { name: 'Guardar' }).click();

    await expect(dialog()).toHaveCount(0);
    await expect(cells(row('Gastos', 'Compra de bici')).nth(5)).toHaveText('US$ 300,00');
    await expect(totals('Gastos')).toHaveCount(2);
    await expect(cells(totals('Gastos').nth(0))).toHaveText(['Total en pesos', '$ 90.000,00', '$ 0,00', '$ 90.000,00', '', '']);
    await expect(cells(totals('Gastos').nth(1))).toHaveText(['Total en dólares', 'US$ 300,00', 'US$ 0,00', 'US$ 300,00', '', '']);
    await expect(results()).toHaveCount(2);
    await expect(cells(results().nth(0))).toHaveText(['Pesos', '$ 1.200.000,00', '$ 90.000,00', '$ 1.110.000,00']);
    await expect(cells(results().nth(1))).toHaveText(['Dólares', 'US$ 0,00', 'US$ 300,00', '-US$ 300,00']);
  });

  test('un vencimiento fuera de rango muestra el error del backend y conserva lo cargado', async () => {
    const outside = month(1); // el día 1 del mes siguiente: un día después del último día del período
    await addButton().click();
    await fill({
      name: 'Anticipo de vacaciones',
      kind: 'Gasto',
      account: 'Banco Nación · $ (ARS)',
      dueDate: isoDate(outside, 1),
      amount: '1.500,50',
    });
    await dialog().getByRole('button', { name: 'Guardar' }).click();

    // El diálogo sigue abierto, con el error del backend bajo el campo y todo lo cargado.
    await expect(dialog()).toBeVisible();
    const previous = month(-1);
    await expect(dialog()).toContainText(
      `El vencimiento debe estar entre el ${dateText(previous, 1)} y el ${dateText(month(0), lastDayOf(month(0)))}.`,
    );
    await expect(dialog().getByLabel('Nombre', { exact: true })).toHaveValue('Anticipo de vacaciones');
    await expect(dialog().getByLabel('Vencimiento')).toHaveValue(isoDate(outside, 1));
    await expect(dialog().getByLabel('Presupuestado')).toHaveValue('1.500,50');
    await expect(dialog().getByRole('button', { name: 'Guardar' })).toBeEnabled();

    // Corregir la fecha alcanza para guardar.
    await dialog().getByLabel('Vencimiento').fill(isoDate(previous, 1));
    await dialog().getByRole('button', { name: 'Guardar' }).click();

    await expect(dialog()).toHaveCount(0);
    // Vence el mes pasado: nace vencida (RN-20).
    await expect(row('Gastos', 'Anticipo de vacaciones').getByTestId('due')).toContainText('Vencida');
  });

  test('Cancelar y Escape cierran el diálogo sin guardar y devuelven el foco', async () => {
    const before = await names('Gastos').count();
    await addButton().click();
    await dialog().getByLabel('Nombre', { exact: true }).fill('No se guarda');
    await dialog().getByRole('button', { name: 'Cancelar' }).click();

    await expect(dialog()).toHaveCount(0);
    await expect(addButton()).toBeFocused();

    await addButton().click();
    await page.keyboard.press('Escape');
    await expect(dialog()).toHaveCount(0);
    await expect(addButton()).toBeFocused();
    await expect(names('Gastos')).toHaveCount(before);
  });

  test('validación de formato en el diálogo: no llama al backend', async () => {
    await addButton().click();
    await dialog().getByLabel('Presupuestado').fill('1.5');
    await dialog().getByRole('button', { name: 'Guardar' }).click();

    await expect(dialog()).toContainText('Ingresá el nombre de la partida.');
    await expect(dialog()).toContainText('Elegí la cuenta.');
    await expect(dialog()).toContainText('Escribilo con coma decimal, hasta 2 decimales y sin signo');
    await expect(dialog()).toBeVisible();
  });
});

test.describe('HU-17 · editar el monto de una partida recurrente', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let api: APIRequestContext;
  let itemId: number;

  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    const { token } = await startSession(page, playwright);
    api = await playwright.request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${token}` },
    });
    const account = await api.post('/api/accounts', {
      data: { name: 'Banco Nación', type: 'BANK', currency: 'ARS', openingDate: `${startPeriod()}-01`, initialBalance: 0 },
    });
    expect(account.status()).toBe(201);
    const item = await api.post('/api/budget-items', {
      data: {
        name: 'Resumen Visa',
        kind: 'EXPENSE',
        defaultAccountId: (await account.json()).id,
        periodicity: 'MONTHLY',
        dueDay: 10,
        dueMonthOffset: 0,
        startPeriod: `${month(0).getFullYear()}-${two(month(0).getMonth() + 1)}`,
        estimationRule: 'LAST_VALUE',
        currentAmount: 180000,
      },
    });
    expect(item.status()).toBe(201);
    itemId = (await item.json()).id;
    await page.goto('/presupuesto');
  });

  test.afterAll(async () => {
    await api.dispose();
    await page.close();
  });

  const section = (name: string) => page.getByRole('region', { name, exact: true });
  const row = (entry: string) =>
    section('Gastos').locator('tbody tr').filter({ has: page.getByRole('rowheader', { name: entry, exact: true }) });
  const budgeted = () => row('Resumen Visa').getByTestId('budgeted');
  const totals = () => section('Gastos').getByTestId('total');
  const results = () => section('Resultado').getByTestId('result');
  const dialog = () => page.getByRole('dialog');
  const editAmount = () => page.getByRole('button', { name: 'Editar monto de Resumen Visa' });
  const heading = (offset: number) =>
    page.getByRole('heading', { level: 1, name: `Presupuesto de ${periodText(month(offset))}` });

  test('el mes actual ofrece «Editar monto» y el diálogo muestra el Concepto, el mes y la aclaración', async () => {
    await expect(budgeted()).toHaveText('$ 180.000,00');
    await expect(row('Resumen Visa').getByText('Editada')).toHaveCount(0);

    await editAmount().click();

    await expect(dialog().getByRole('heading', { name: 'Editar monto' })).toBeVisible();
    await expect(dialog()).toContainText('Resumen Visa');
    await expect(dialog()).toContainText(periodText(month(0)));
    await expect(dialog()).toContainText('El cambio vale solo para este mes');
    // Solo el presupuestado: no hay nombre, cuenta ni vencimiento.
    await expect(dialog().getByLabel('Presupuestado')).toHaveValue('180000,00');
    await expect(dialog().getByLabel('Nombre', { exact: true })).toHaveCount(0);
    await expect(dialog().getByLabel('Vencimiento')).toHaveCount(0);
    await dialog().getByRole('button', { name: 'Cancelar' }).click();
    await expect(dialog()).toHaveCount(0);
    await expect(editAmount()).toBeFocused();
  });

  test('editar el monto del mes: queda «Editada», los totales los recalcula el backend y el foco vuelve al botón', async () => {
    await editAmount().click();
    await dialog().getByLabel('Presupuestado').fill('240.000');
    await dialog().getByRole('button', { name: 'Guardar' }).click();

    await expect(dialog()).toHaveCount(0);
    await expect(page.getByText('Partida «Resumen Visa» guardada.')).toBeVisible();
    await expect(budgeted()).toHaveText(/\$ 240\.000,00\s*Editada/);
    await expect(row('Resumen Visa').getByTestId('status')).toHaveText('Estimada');
    await expect(totals().locator('td').nth(0)).toHaveText('$ 240.000,00');
    await expect(results()).toHaveCount(1);
    await expect(editAmount()).toBeFocused();
    // Editar no toca el monto vigente del Concepto (RN-18).
    const item = await (await api.get(`/api/budget-items/${itemId}`)).json();
    expect(item.currentAmount).toBe(180000);
  });

  test('el mes siguiente no cambió', async () => {
    await page.getByRole('button', { name: 'Mes siguiente' }).click();

    await expect(heading(1)).toBeVisible();
    await expect(budgeted()).toHaveText('$ 180.000,00');
    await expect(row('Resumen Visa').getByText('Editada')).toHaveCount(0);

    await page.getByRole('link', { name: 'Hoy' }).click();
    await expect(heading(0)).toBeVisible();
    await expect(budgeted()).toHaveText(/\$ 240\.000,00\s*Editada/);
  });

  test('cambiar el monto vigente del Concepto respeta la partida editada, y el aviso cuenta 1 editada y 24 sin editar', async () => {
    await page.goto(`/conceptos/${itemId}/editar`);
    await page.getByLabel('Monto vigente').fill('200.000');
    await page.getByRole('button', { name: 'Guardar cambios' }).click();

    await expect(dialog()).toContainText('de $ 180.000,00 a $ 200.000,00');
    await expect(dialog()).toContainText('Se reemplazará el monto presupuestado de 24 partidas pendientes sin editar');
    await expect(dialog()).toContainText('La partida editada a mano no cambia.');
    await dialog().getByRole('button', { name: 'Cambiar monto' }).click();
    await expect(page.getByText('Concepto «Resumen Visa» guardado.')).toBeVisible();

    await page.goto('/presupuesto');
    await expect(budgeted()).toHaveText(/\$ 240\.000,00\s*Editada/);
    await page.getByRole('button', { name: 'Mes siguiente' }).click();
    await expect(heading(1)).toBeVisible();
    await expect(budgeted()).toHaveText('$ 200.000,00');
    await expect(row('Resumen Visa').getByText('Editada')).toHaveCount(0);
  });

  test('un presupuestado con formato inválido no llama al backend', async () => {
    await page.getByRole('link', { name: 'Hoy' }).click();
    await expect(heading(0)).toBeVisible();
    let patched = false;
    page.on('request', (r) => {
      patched ||= r.method() === 'PATCH';
    });

    await editAmount().click();
    await dialog().getByLabel('Presupuestado').fill('1.5');
    await dialog().getByRole('button', { name: 'Guardar' }).click();

    await expect(dialog()).toContainText('Escribilo con coma decimal, hasta 2 decimales y sin signo');
    await expect(dialog()).toBeVisible();
    expect(patched).toBe(false);
    await dialog().getByRole('button', { name: 'Cancelar' }).click();
  });

  test('caso de error: un monto que el backend rechaza se muestra bajo el campo, conserva lo cargado y no cambia nada', async () => {
    await page.getByRole('link', { name: 'Hoy' }).click();
    await editAmount().click();
    // Pasa el formato de la pantalla, pero el backend lo rechaza: tiene más de 17 dígitos enteros.
    await dialog().getByLabel('Presupuestado').fill('99999999999999999999');
    await dialog().getByRole('button', { name: 'Guardar' }).click();

    await expect(dialog()).toBeVisible();
    await expect(dialog().locator('mat-error')).toHaveCount(1);
    await expect(dialog().getByLabel('Presupuestado')).toHaveValue('99999999999999999999');
    await dialog().getByRole('button', { name: 'Cancelar' }).click();
    await expect(budgeted()).toHaveText(/\$ 240\.000,00\s*Editada/);
  });

  test('por la API: otro dato de una recurrente es 409 y no cambia nada, ni siquiera el monto', async () => {
    const view = await (await api.get(`/api/periods/${month(0).getFullYear()}-${two(month(0).getMonth() + 1)}`)).json();
    const entry = view.expenses[0];
    const response = await api.patch(`/api/entries/${entry.id}`, {
      data: { budgetedAmount: 1, dueDate: isoDate(month(0), 20) },
    });

    expect(response.status()).toBe(409);
    expect((await response.json()).code).toBe('FIELD_NOT_EDITABLE');
    const after = await (await api.get(`/api/periods/${month(0).getFullYear()}-${two(month(0).getMonth() + 1)}`)).json();
    expect(after.expenses[0].budgetedAmount).toBe(240000);
    expect(after.expenses[0].manual).toBe(true);
  });
});

/** `YYYY-MM` del mes actual corrido `offset` meses. */
const periodId = (offset: number) => `${month(offset).getFullYear()}-${two(month(offset).getMonth() + 1)}`;

test.describe('HU-18 · eliminar una partida puntual', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let api: APIRequestContext;

  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    const { token } = await startSession(page, playwright);
    api = await playwright.request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${token}` },
    });
    const account = await api.post('/api/accounts', {
      data: { name: 'Banco Nación', type: 'BANK', currency: 'ARS', openingDate: `${startPeriod()}-01`, initialBalance: 0 },
    });
    expect(account.status()).toBe(201);
    const entry = await api.post(`/api/periods/${periodId(0)}/entries`, {
      data: {
        name: 'Service del auto',
        kind: 'EXPENSE',
        accountId: (await account.json()).id,
        dueDate: isoDate(month(0), 15),
        budgetedAmount: 85000,
      },
    });
    expect(entry.status()).toBe(201);
    await page.goto('/presupuesto');
  });

  test.afterAll(async () => {
    await api.dispose();
    await page.close();
  });

  const section = (name: string) => page.getByRole('region', { name, exact: true });
  const row = () =>
    section('Gastos').locator('tbody tr').filter({ has: page.getByRole('rowheader', { name: 'Service del auto' }) });
  const dialog = () => page.getByRole('dialog');
  const remove = () => page.getByRole('button', { name: 'Eliminar Service del auto' });

  test('pide una confirmación simple con el nombre y el monto, y «Cancelar» no elimina nada', async () => {
    await expect(row()).toHaveCount(1);

    await remove().click();

    await expect(dialog().getByRole('heading', { name: 'Eliminar partida' })).toBeVisible();
    await expect(dialog()).toContainText('Vas a eliminar «Service del auto»');
    await expect(dialog()).toContainText('$ 85.000,00');
    // Una partida sin Concepto no pregunta alcance.
    await expect(dialog().getByRole('radio')).toHaveCount(0);
    await dialog().getByRole('button', { name: 'Cancelar' }).click();
    await expect(dialog()).toHaveCount(0);
    await expect(row()).toHaveCount(1);
    await expect(remove()).toBeFocused();
  });

  test('eliminarla la saca de la vista con los totales del backend y deja el foco en la sección', async () => {
    await remove().click();
    await dialog().getByRole('button', { name: 'Eliminar partida' }).click();

    await expect(dialog()).toHaveCount(0);
    await expect(page.getByText('Partida «Service del auto» eliminada.')).toBeVisible();
    await expect(row()).toHaveCount(0);
    await expect(section('Gastos')).toContainText('No hay gastos en este mes.');
    await expect(section('Gastos').getByTestId('total')).toHaveCount(0);
    // La fila que tenía el foco ya no existe: queda en el encabezado de la sección.
    await expect(section('Gastos').getByRole('heading', { name: 'Gastos' })).toBeFocused();
    const view = await (await api.get(`/api/periods/${periodId(0)}`)).json();
    expect(view.expenses).toHaveLength(0);
    expect(view.totals).toHaveLength(0);
  });
});

test.describe('HU-18 · eliminar la partida de un Concepto', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let api: APIRequestContext;
  let email: string;
  let lightId: number;
  let gasId: number;
  let fridgeId: number;
  let boxAccountId: number;
  let homeCategoryId: number;
  let otherApi: APIRequestContext;
  let otherPage: Page;

  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    const session = await startSession(page, playwright);
    email = session.email;
    api = await playwright.request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${session.token}` },
    });
    const bank = await api.post('/api/accounts', {
      data: { name: 'Banco Nación', type: 'BANK', currency: 'ARS', openingDate: `${startPeriod()}-01`, initialBalance: 0 },
    });
    const box = await api.post('/api/accounts', {
      data: { name: 'Caja', type: 'CASH', currency: 'ARS', openingDate: `${startPeriod()}-01`, initialBalance: 0 },
    });
    const home = await api.post('/api/categories', { data: { name: 'Hogar' } });
    expect(bank.status()).toBe(201);
    expect(box.status()).toBe(201);
    expect(home.status()).toBe(201);
    boxAccountId = (await box.json()).id;
    homeCategoryId = (await home.json()).id;
    const create = async (data: object) => {
      const response = await api.post('/api/budget-items', {
        data: {
          kind: 'EXPENSE',
          periodicity: 'MONTHLY',
          dueMonthOffset: 0,
          startPeriod: periodId(0),
          estimationRule: 'LAST_VALUE',
          ...data,
        },
      });
      expect(response.status()).toBe(201);
      return (await response.json()).id as number;
    };
    const bankId = (await bank.json()).id;
    lightId = await create({ name: 'Luz', defaultAccountId: bankId, dueDay: 10, currentAmount: 45000 });
    gasId = await create({
      name: 'Gas',
      defaultAccountId: boxAccountId,
      categoryId: homeCategoryId,
      dueDay: 20,
      currentAmount: 30000,
    });
    fridgeId = await create({
      name: 'Heladera',
      defaultAccountId: bankId,
      dueDay: 28,
      currentAmount: 90000,
      installmentsTotal: 12,
      firstInstallmentNumber: 1,
    });

    // Otro usuario, para comprobar que no se puede eliminar lo de otro.
    otherPage = await browser.newPage();
    const other = await startSession(otherPage, playwright);
    otherApi = await playwright.request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${other.token}` },
    });
  });

  test.afterAll(async () => {
    await otherApi.dispose();
    await otherPage.close();
    await api.dispose();
    await page.close();
  });

  const section = (name: string) => page.getByRole('region', { name, exact: true });
  const row = (entry: string) =>
    section('Gastos').locator('tbody tr').filter({ has: page.getByRole('rowheader', { name: entry, exact: true }) });
  const dialog = () => page.getByRole('dialog');
  const remove = (entry: string) => page.getByRole('button', { name: `Eliminar ${entry}` });
  const goToMonth = async (offset: number) => {
    await page.goto(`/presupuesto/${periodId(offset)}`);
    await expect(page.getByRole('heading', { level: 1, name: `Presupuesto de ${periodText(month(offset))}` })).toBeVisible();
  };
  const entryIdIn = async (offset: number, name: string) => {
    const view = await (await api.get(`/api/periods/${periodId(offset)}`)).json();
    return view.expenses.find((e: { name: string }) => e.name === name)?.id as number | undefined;
  };

  test('el diálogo pregunta el alcance: ninguna opción viene elegida y el botón no confirma', async () => {
    await goToMonth(1);

    await remove('Luz').click();

    await expect(dialog().getByRole('heading', { name: `Eliminar «Luz» de ${periodText(month(1))}` })).toBeVisible();
    await expect(dialog().getByRole('radio', { name: 'Solo este mes' })).not.toBeChecked();
    await expect(dialog().getByRole('radio', { name: 'Este mes y los siguientes' })).not.toBeChecked();
    await expect(dialog().getByRole('button', { name: 'Eliminar', exact: true })).toBeDisabled();
    // Explica en palabras qué hace cada una, con las cifras del backend: 24 partidas desde el mes siguiente.
    await expect(dialog()).toContainText(`Se elimina solo la partida de ${periodText(month(1))}.`);
    await expect(dialog()).toContainText(`Se eliminan las 24 partidas de «Luz», de ${periodText(month(1))} a ${periodText(month(24))}.`);
    await expect(dialog()).toContainText(`termina en ${periodText(month(0))} y no genera más partidas`);
    await dialog().getByRole('button', { name: 'Cancelar' }).click();
    await expect(remove('Luz')).toBeFocused();
  });

  test('«Solo este mes» elimina solo esa partida y el foco pasa a la fila que ocupa su lugar', async () => {
    await remove('Luz').click();
    await dialog().getByRole('radio', { name: 'Solo este mes' }).check();
    await expect(dialog().getByRole('button', { name: 'Eliminar solo este mes' })).toBeEnabled();
    await dialog().getByRole('button', { name: 'Eliminar solo este mes' }).click();

    await expect(dialog()).toHaveCount(0);
    await expect(page.getByText(`Se eliminó «Luz» de ${periodText(month(1))}.`)).toBeVisible();
    await expect(row('Luz')).toHaveCount(0);
    await expect(row('Gas')).toHaveCount(1);
    await expect(page.getByRole('button', { name: 'Editar monto de Gas' })).toBeFocused();
    // Los totales los calcula el backend: Gas 30.000 + Heladera 90.000 = 120.000 presupuestado.
    await expect(section('Gastos').getByTestId('total').locator('td').nth(0)).toHaveText('$ 120.000,00');
  });

  test('el mes anterior y el siguiente conservan la suya, y no reaparece al iniciar sesión de nuevo (HU-12, criterio 3)', async () => {
    await goToMonth(0);
    await expect(row('Luz')).toHaveCount(1);
    await goToMonth(2);
    await expect(row('Luz')).toHaveCount(1);

    // Iniciar sesión asegura el horizonte (RN-07): la partida eliminada no se vuelve a generar.
    const login = await api.post('/api/auth/login', { data: { email, password: PASSWORD } });
    expect(login.status()).toBe(200);
    await goToMonth(1);
    await expect(row('Luz')).toHaveCount(0);
    expect(await entryIdIn(1, 'Luz')).toBeUndefined();
    // El Concepto no cambió: sigue sin fin y con el mismo monto vigente.
    const item = await (await api.get(`/api/budget-items/${lightId}`)).json();
    expect(item.endPeriod).toBeNull();
    expect(item.currentAmount).toBe(45000);
  });

  test('«Este mes y los siguientes» desde un mes futuro: los anteriores siguen, los posteriores ya no la tienen', async () => {
    await goToMonth(3);
    await remove('Luz').click();
    await dialog().getByRole('radio', { name: 'Este mes y los siguientes' }).check();
    // 22 partidas: de +3 a +24, porque la de +1 ya se había eliminado.
    await expect(dialog()).toContainText(
      `Se eliminan las 22 partidas de «Luz», de ${periodText(month(3))} a ${periodText(month(24))}.`,
    );
    await expect(dialog()).toContainText(`termina en ${periodText(month(2))} y no genera más partidas`);
    await dialog().getByRole('button', { name: 'Eliminar este mes y los siguientes' }).click();

    await expect(dialog()).toHaveCount(0);
    await expect(
      page.getByText(`Se eliminaron 22 partidas de «Luz». El Concepto termina en ${periodText(month(2))}.`),
    ).toBeVisible();
    await expect(row('Luz')).toHaveCount(0);
    for (const offset of [0, 2]) {
      await goToMonth(offset);
      await expect(row('Luz')).toHaveCount(1);
    }
    for (const offset of [3, 10, 24]) {
      await goToMonth(offset);
      await expect(row('Luz')).toHaveCount(0);
      // Las de otros Conceptos no se tocan.
      await expect(row('Gas')).toHaveCount(1);
    }
  });

  test('el Concepto figura en la lista con su fin nuevo y el horizonte no le genera más', async () => {
    await page.goto('/conceptos');
    const luz = page.getByRole('row').filter({ hasText: 'Luz' });
    await expect(luz).toContainText(`termina en ${periodText(month(2))}`);

    await api.post('/api/auth/login', { data: { email, password: PASSWORD } });
    const item = await (await api.get(`/api/budget-items/${lightId}`)).json();
    expect(item.endPeriod).toBe(periodId(2));
    expect(await entryIdIn(3, 'Luz')).toBeUndefined();
  });

  test('un plan de cuotas recortado cuenta las cuotas que quedan hasta la última que sigue', async () => {
    await goToMonth(6);
    await remove('Heladera').click();
    await dialog().getByRole('radio', { name: 'Este mes y los siguientes' }).check();
    await dialog().getByRole('button', { name: 'Eliminar este mes y los siguientes' }).click();
    await expect(dialog()).toHaveCount(0);

    await page.goto('/conceptos');
    // 12 cuotas desde este mes; se eliminan de +6 en adelante: quedan las cuotas 1 a 6, y hoy es la 1.
    const fridge = page.getByRole('row').filter({ hasText: 'Heladera' });
    await expect(fridge).toContainText('Cuota 1 de 12, quedan 5');
    await expect(fridge).toContainText(`termina en ${periodText(month(5))}`);
    const item = await (await api.get(`/api/budget-items/${fridgeId}`)).json();
    expect(item.endPeriod).toBe(periodId(5));
    expect(item.installmentsTotal).toBe(12);
  });

  test('desde el primer mes el Concepto desaparece, y su cuenta y su categoría dejan de estar en uso', async () => {
    // Mientras existe, la cuenta y la categoría están en uso.
    expect((await api.delete(`/api/accounts/${boxAccountId}`)).status()).toBe(409);
    expect((await api.delete(`/api/categories/${homeCategoryId}`)).status()).toBe(409);

    await goToMonth(0);
    await remove('Gas').click();
    await dialog().getByRole('radio', { name: 'Este mes y los siguientes' }).check();
    await expect(dialog()).toContainText('No queda ninguna partida, así que el Concepto «Gas» también se elimina.');
    await dialog().getByRole('button', { name: 'Eliminar este mes y los siguientes' }).click();

    await expect(dialog()).toHaveCount(0);
    await expect(
      page.getByText('Se eliminaron 25 partidas de «Gas» y el Concepto, que no tenía más partidas.'),
    ).toBeVisible();
    await expect(row('Gas')).toHaveCount(0);
    await page.goto('/conceptos');
    // «Gas» solo, no «Gasto» (el tipo de cada fila).
    await expect(page.getByRole('row').filter({ has: page.getByText('Gas', { exact: true }) })).toHaveCount(0);
    expect((await api.get(`/api/budget-items/${gasId}`)).status()).toBe(404);
    await goToMonth(5);
    await expect(row('Gas')).toHaveCount(0);

    expect((await api.delete(`/api/accounts/${boxAccountId}`)).status()).toBe(204);
    expect((await api.delete(`/api/categories/${homeCategoryId}`)).status()).toBe(204);
  });

  test('caso de error: si la partida ya no existe, el diálogo lo dice, no deja confirmar y al cancelar recarga el mes', async () => {
    await goToMonth(2);
    await remove('Luz').click();
    await dialog().getByRole('radio', { name: 'Solo este mes' }).check();
    // Otra pestaña la elimina antes de confirmar.
    const id = await entryIdIn(2, 'Luz');
    expect((await api.delete(`/api/entries/${id}?scope=ONLY_THIS`)).status()).toBe(204);

    await dialog().getByRole('button', { name: 'Eliminar solo este mes' }).click();

    await expect(dialog().getByRole('alert')).toContainText('La partida no existe.');
    await expect(dialog().getByRole('button', { name: 'Eliminar solo este mes' })).toBeDisabled();
    await dialog().getByRole('button', { name: 'Cancelar' }).click();
    await expect(dialog()).toHaveCount(0);
    await expect(row('Luz')).toHaveCount(0);
  });

  test('por la API: alcance faltante o sobrante es 400, un id inexistente o de otro usuario es 404, y no cambia nada', async () => {
    const oneOff = await api.post(`/api/periods/${periodId(0)}/entries`, {
      data: {
        name: 'Puntual',
        kind: 'EXPENSE',
        accountId: (await (await api.get('/api/accounts')).json()).accounts[0].id,
        dueDate: isoDate(month(0), 20),
        budgetedAmount: 10,
      },
    });
    expect(oneOff.status()).toBe(201);
    const oneOffId = (await oneOff.json()).id;
    const heladeraId = (await api.get(`/api/periods/${periodId(0)}`).then((r) => r.json())).expenses.find(
      (e: { name: string }) => e.name === 'Heladera',
    ).id;

    // Una recurrente sin alcance.
    const missing = await api.delete(`/api/entries/${heladeraId}`);
    expect(missing.status()).toBe(400);
    const missingBody = await missing.json();
    expect(missingBody.code).toBe('VALIDATION_ERROR');
    expect(missingBody.errors[0].field).toBe('scope');
    // Una sin Concepto con alcance.
    const extra = await api.delete(`/api/entries/${oneOffId}?scope=ONLY_THIS`);
    expect(extra.status()).toBe(400);
    expect((await extra.json()).errors[0].field).toBe('scope');
    // Un alcance que no existe.
    expect((await api.delete(`/api/entries/${heladeraId}?scope=ALL`)).status()).toBe(400);
    // Inexistente.
    expect((await api.delete('/api/entries/999999999')).status()).toBe(404);
    expect((await api.get('/api/entries/999999999/deletion-preview')).status()).toBe(404);
    // De otro usuario: 404 y sigue existiendo para su dueño.
    expect((await otherApi.delete(`/api/entries/${oneOffId}`)).status()).toBe(404);
    expect((await otherApi.get(`/api/entries/${oneOffId}/deletion-preview`)).status()).toBe(404);
    const view = await (await api.get(`/api/periods/${periodId(0)}`)).json();
    expect(view.expenses.map((e: { name: string }) => e.name)).toContain('Puntual');
    expect(view.expenses.map((e: { name: string }) => e.name)).toContain('Heladera');
  });

  test('por la API: la vista previa dice lo mismo que hace la eliminación', async () => {
    const id = await entryIdIn(0, 'Luz');
    const preview = await (await api.get(`/api/entries/${id}/deletion-preview`)).json();

    expect(preview.recurring).toBe(true);
    // A Luz le queda solo la partida de este mes, y su fin ya está generado: eliminarla, con cualquier alcance, la
    // deja sin partidas y sin nada por generar, así que el Concepto desaparece.
    expect(preview.onlyThis).toMatchObject({ allowed: true, entryCount: 1, itemOutcome: 'REMOVES_ITEM' });
    expect(preview.thisAndFuture).toMatchObject({ allowed: true, itemOutcome: 'REMOVES_ITEM', blockers: [] });
    expect(preview.thisAndFuture.entryCount).toBe(1);
  });
});

