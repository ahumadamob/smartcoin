import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { API_URL, startPeriod, startSession } from './support';

/**
 * Épica 5 · Movimientos. HU-19 (registrar cobros y pagos, en partes y desde otra cuenta de la misma moneda). Un
 * usuario propio para todo el archivo: los movimientos se registran siempre con un usuario de prueba, nunca con el
 * real. Las cuentas y las partidas se cargan por la API: sus pantallas las cubren otras épicas.
 */

const two = (n: number) => String(n).padStart(2, '0');
/** `YYYY-MM-DD`, como lo espera el campo de fecha. */
const isoDate = (date: Date) => `${date.getFullYear()}-${two(date.getMonth() + 1)}-${two(date.getDate())}`;
const periodOf = (date: Date) => `${date.getFullYear()}-${two(date.getMonth() + 1)}`;

const today = new Date();
const inTwoDays = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 2);

test.describe('HU-19 · registrar cobros y pagos', () => {
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
    const create = async (path: string, data: object) => {
      const response = await api.post(path, { data });
      expect(response.status(), `alta en ${path}: ${JSON.stringify(data)}`).toBe(201);
      return (await response.json()).id as number;
    };
    const account = (name: string, currency: string) =>
      create('/api/accounts', { name, type: 'BANK', currency, openingDate: `${startPeriod()}-01`, initialBalance: 0 });
    const bank = await account('Banco Nación', 'ARS');
    await account('Billetera', 'ARS');
    const dollars = await account('Caja en dólares', 'USD');
    await account('Ahorro en dólares', 'USD');

    const period = periodOf(today);
    const oneOff = (name: string, kind: string, accountId: number, budgetedAmount: number) =>
      create(`/api/periods/${period}/entries`, {
        name,
        kind,
        accountId,
        dueDate: isoDate(today),
        budgetedAmount,
      });
    await oneOff('Expensas', 'EXPENSE', bank, 120000);
    await oneOff('Patente', 'EXPENSE', bank, 30000);
    await oneOff('Alquiler cobrado', 'INCOME', dollars, 500);
    await page.goto('/presupuesto');
  });

  test.afterAll(async () => {
    await api.dispose();
    await page.close();
  });

  const section = (name: string) => page.getByRole('region', { name, exact: true });
  const row = (sectionName: string, entry: string) =>
    section(sectionName).locator('tbody tr').filter({ has: page.getByRole('rowheader', { name: entry, exact: true }) });
  const cells = (locator: ReturnType<typeof row>) => locator.locator('th, td');
  const dialog = () => page.getByRole('dialog');

  async function chooseAccount(name: string) {
    await dialog().getByLabel('Cuenta', { exact: true }).click();
    await page.getByRole('option', { name, exact: true }).click();
  }

  test('un pago parcial deja la partida Parcial con real y pendiente del backend', async () => {
    await expect(row('Gastos', 'Expensas')).toBeVisible();
    await expect(cells(row('Gastos', 'Expensas')).nth(8)).toHaveText('Estimada');

    await row('Gastos', 'Expensas').getByRole('button', { name: 'Registrar pago de Expensas' }).click();

    await expect(dialog().getByRole('heading', { name: 'Registrar pago · Expensas' })).toBeVisible();
    await expect(dialog().getByTestId('budgeted')).toHaveText('$ 120.000,00');
    await expect(dialog().getByTestId('actual')).toHaveText('$ 0,00');
    await expect(dialog().getByTestId('pending')).toHaveText('$ 120.000,00');
    await expect(dialog().getByTestId('no-movements')).toBeVisible();
    // La fecha arranca en hoy, la cuenta es la de la partida y el monto sugiere el pendiente.
    await expect(dialog().getByLabel('Fecha')).toHaveValue(isoDate(today));
    await expect(dialog().getByLabel('Cuenta', { exact: true })).toContainText('Banco Nación');
    await expect(dialog().getByLabel('Monto')).toHaveValue('120000,00');

    await dialog().getByLabel('Monto').fill('70.000,00');
    await dialog().getByLabel('Nota').fill('Primera parte');
    await dialog().getByRole('button', { name: 'Registrar pago', exact: true }).click();

    await expect(dialog()).toBeHidden();
    const entry = cells(row('Gastos', 'Expensas'));
    await expect(entry.nth(5)).toContainText('$ 120.000,00');
    await expect(entry.nth(6)).toHaveText('$ 70.000,00');
    await expect(entry.nth(7)).toHaveText('$ 50.000,00');
    await expect(entry.nth(8)).toHaveText('Parcial');
    await expect(page.getByText('Pago de $ 70.000,00 registrado en «Expensas».')).toBeVisible();
    // El foco vuelve a la acción de la partida.
    await expect(row('Gastos', 'Expensas').getByRole('button', { name: 'Registrar pago de Expensas' })).toBeFocused();
    // Los totales de pesos suman las dos partidas y los de dólares no se mezclan.
    const total = section('Gastos').getByTestId('total');
    await expect(total).toHaveCount(1);
    await expect(total).toContainText('$ 150.000,00');
    await expect(total).toContainText('$ 70.000,00');
    await expect(total).toContainText('$ 80.000,00');
  });

  test('completar el pago deja el pendiente en 0 sin consolidar, y avisa que está cubierta', async () => {
    await row('Gastos', 'Expensas').getByRole('button', { name: 'Registrar pago de Expensas' }).click();

    // El diálogo muestra lo ya registrado.
    await expect(dialog().getByTestId('actual')).toHaveText('$ 70.000,00');
    await expect(dialog().getByTestId('pending')).toHaveText('$ 50.000,00');
    await expect(dialog().getByTestId('movement-row')).toHaveCount(1);
    await expect(dialog().getByTestId('movement-row')).toContainText('Banco Nación');
    await expect(dialog().getByTestId('movement-row')).toContainText('$ 70.000,00');
    await expect(dialog().getByTestId('movement-row')).toContainText('Primera parte');
    await expect(dialog().getByLabel('Monto')).toHaveValue('50000,00');

    // La segunda parte sale de otra cuenta en pesos (S-02): la cuenta de la partida es solo la sugerida.
    await chooseAccount('Billetera');
    await dialog().getByRole('button', { name: 'Registrar pago', exact: true }).click();

    await expect(dialog()).toBeHidden();
    const entry = cells(row('Gastos', 'Expensas'));
    await expect(entry.nth(6)).toHaveText('$ 120.000,00');
    await expect(entry.nth(7)).toHaveText('$ 0,00');
    // Sigue Parcial: registrar no consolida (RN-23).
    await expect(entry.nth(8)).toHaveText('Parcial');
    await expect(page.getByText('«Expensas» ya está cubierta: pendiente $ 0,00.')).toBeVisible();

    // Al reabrirlo, el aviso sigue ahí y se listan los dos movimientos, por fecha.
    await row('Gastos', 'Expensas').getByRole('button', { name: 'Registrar pago de Expensas' }).click();
    await expect(dialog().getByTestId('covered-notice')).toContainText('Sigue Parcial hasta que se consolide');
    await expect(dialog().getByTestId('movement-row')).toHaveCount(2);
    await expect(dialog().getByTestId('movement-row').nth(1)).toContainText('Billetera');
    await dialog().getByRole('button', { name: 'Cancelar' }).click();
    await expect(dialog()).toBeHidden();
  });

  test('un cobro en dólares puede salir de otra cuenta en dólares, y solo se ofrecen cuentas de esa moneda', async () => {
    await row('Ingresos', 'Alquiler cobrado')
      .getByRole('button', { name: 'Registrar cobro de Alquiler cobrado' })
      .click();

    await expect(dialog().getByRole('heading', { name: 'Registrar cobro · Alquiler cobrado' })).toBeVisible();
    await dialog().getByLabel('Cuenta', { exact: true }).click();
    await expect(page.getByRole('option')).toHaveText(['Ahorro en dólares', 'Caja en dólares']);
    await page.getByRole('option', { name: 'Ahorro en dólares', exact: true }).click();
    await dialog().getByLabel('Monto').fill('500');
    await dialog().getByRole('button', { name: 'Registrar cobro', exact: true }).click();

    await expect(dialog()).toBeHidden();
    const entry = cells(row('Ingresos', 'Alquiler cobrado'));
    await expect(entry.nth(6)).toHaveText('US$ 500,00');
    await expect(entry.nth(7)).toHaveText('US$ 0,00');
    await expect(entry.nth(8)).toHaveText('Parcial');
    await expect(page.getByText('Cobro de US$ 500,00 registrado en «Alquiler cobrado».')).toBeVisible();
  });

  test('en /cuentas los saldos cambiaron: el pago resta, el cobro suma, cada moneda por separado', async () => {
    await page.goto('/cuentas');

    const bank = page.getByRole('listitem').filter({ hasText: 'Banco Nación' });
    const wallet = page.getByRole('listitem').filter({ hasText: 'Billetera' });
    const savings = page.getByRole('listitem').filter({ hasText: 'Ahorro en dólares' });
    const box = page.getByRole('listitem').filter({ hasText: 'Caja en dólares' });
    await expect(bank.getByTestId('balance')).toHaveText('-$ 70.000,00');
    await expect(wallet.getByTestId('balance')).toHaveText('-$ 50.000,00');
    await expect(savings.getByTestId('balance')).toHaveText('US$ 500,00');
    // La cuenta prevista del cobro no se tocó: el movimiento salió de otra.
    await expect(box.getByTestId('balance')).toHaveText('US$ 0,00');
    await expect(page.getByRole('region', { name: '$ (ARS)' }).getByTestId('subtotal')).toContainText('-$ 120.000,00');
    await expect(page.getByRole('region', { name: 'US$ (USD)' }).getByTestId('subtotal')).toContainText('US$ 500,00');
  });

  test('una fecha futura se rechaza bajo el campo, sin perder lo cargado ni cambiar la partida', async () => {
    await page.goto('/presupuesto');
    await row('Gastos', 'Patente').getByRole('button', { name: 'Registrar pago de Patente' }).click();
    await dialog().getByLabel('Fecha').fill(isoDate(inTwoDays));
    await dialog().getByLabel('Monto').fill('1500,50');
    await dialog().getByLabel('Nota').fill('Adelantado');

    await dialog().getByRole('button', { name: 'Registrar pago', exact: true }).click();

    await expect(dialog()).toBeVisible();
    await expect(dialog().getByText('La fecha no puede ser posterior a hoy')).toBeVisible();
    await expect(dialog().getByLabel('Fecha')).toBeFocused();
    await expect(dialog().getByLabel('Monto')).toHaveValue('1500,50');
    await expect(dialog().getByLabel('Nota')).toHaveValue('Adelantado');
    await dialog().getByRole('button', { name: 'Cancelar' }).click();
    await expect(dialog()).toBeHidden();
    // La partida no cambió.
    const entry = cells(row('Gastos', 'Patente'));
    await expect(entry.nth(6)).toHaveText('$ 0,00');
    await expect(entry.nth(8)).toHaveText('Estimada');
  });

  test('corregida la fecha, el mismo diálogo guarda el pago', async () => {
    await row('Gastos', 'Patente').getByRole('button', { name: 'Registrar pago de Patente' }).click();
    await dialog().getByLabel('Fecha').fill(isoDate(inTwoDays));
    await dialog().getByLabel('Monto').fill('1500,50');
    await dialog().getByRole('button', { name: 'Registrar pago', exact: true }).click();
    await expect(dialog().getByText('La fecha no puede ser posterior a hoy')).toBeVisible();

    await dialog().getByLabel('Fecha').fill(isoDate(today));
    await dialog().getByRole('button', { name: 'Registrar pago', exact: true }).click();

    await expect(dialog()).toBeHidden();
    const entry = cells(row('Gastos', 'Patente'));
    await expect(entry.nth(6)).toHaveText('$ 1.500,50');
    await expect(entry.nth(7)).toHaveText('$ 28.499,50');
    await expect(entry.nth(8)).toHaveText('Parcial');
  });
});
