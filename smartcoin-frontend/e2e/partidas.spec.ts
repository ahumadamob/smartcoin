import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { API_URL, startPeriod, startSession } from './support';

/**
 * Épica 4 · Partidas. HU-16 (agregar una partida puntual y editar las partidas sin Concepto). HU-17 y HU-18 suman sus
 * casos acá cuando se implementen. Cada corrida usa un usuario propio.
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

  test('el mes arranca solo con la partida recurrente, que no ofrece acciones', async () => {
    await expect(page.getByRole('heading', { level: 1, name: `Presupuesto de ${periodText(month(0))}` })).toBeVisible();
    await expect(addButton()).toBeVisible();
    await expect(names('Ingresos')).toHaveText(['Sueldo']);
    // Hoy ninguna partida del mes tiene acciones: no se dibuja una columna vacía.
    await expect(page.getByRole('columnheader', { name: 'Acciones' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^Editar/ })).toHaveCount(0);
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
      'Editar',
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
