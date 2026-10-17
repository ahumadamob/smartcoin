import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { API_URL, startSession } from './support';

/**
 * Épica 8 · Cierre de mes. HU-30 (ver qué falta para cerrar el mes). Un usuario propio, nunca el real: nace con el
 * período inicial dos meses antes del actual, así que ese mes ya terminó y no tiene anterior. Se puede cerrar. El mes
 * siguiente tiene el anterior abierto, y el actual todavía no terminó.
 *
 * Todas las fechas salen de hoy: el test pasa cualquier día del mes y en enero (el período inicial es noviembre del año
 * anterior). El último día del mes, el mes actual ya terminó: «el mes no terminó» se prueba entonces en el siguiente.
 * Las cuentas, partidas, movimientos y transferencias se cargan por la API: sus pantallas las cubren otras épicas.
 */

const two = (n: number) => String(n).padStart(2, '0');
const isoDate = (date: Date) => `${date.getFullYear()}-${two(date.getMonth() + 1)}-${two(date.getDate())}`;
const periodOf = (date: Date) => `${date.getFullYear()}-${two(date.getMonth() + 1)}`;
/** `dd/MM/yyyy`, como lo muestra la aplicación. */
const shown = (date: Date) => `${two(date.getDate())}/${two(date.getMonth() + 1)}/${date.getFullYear()}`;
const MONTHS = [
  'enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio',
  'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre',
];
const label = (date: Date) => `${MONTHS[date.getMonth()]} ${date.getFullYear()}`;

const today = new Date();
/** Primer día del período inicial, del siguiente y del actual. */
const start = new Date(today.getFullYear(), today.getMonth() - 2, 1);
const next = new Date(today.getFullYear(), today.getMonth() - 1, 1);
const current = new Date(today.getFullYear(), today.getMonth(), 1);
/** Último día del período inicial. */
const startLast = new Date(today.getFullYear(), today.getMonth() - 1, 0);
/** El mes que seguro no terminó: el actual, salvo en su último día; entonces, el siguiente. */
const isLastDay = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 1).getDate() === 1;
const unfinished = isLastDay ? new Date(today.getFullYear(), today.getMonth() + 1, 1) : current;
const unfinishedLast = new Date(unfinished.getFullYear(), unfinished.getMonth() + 1, 0);

test.describe('HU-30 · ver qué falta para cerrar el mes', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let api: APIRequestContext;
  let startViewBefore: string;

  const create = async (path: string, data: unknown): Promise<number> => {
    const response = await api.post(path, { data });
    expect(response.status(), path).toBe(201);
    return (await response.json()).id;
  };

  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    const { token } = await startSession(page, playwright);
    api = await playwright.request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${token}` },
    });

    const account = (name: string, currency: string, openingDate: Date, initialBalance: number) =>
      create('/api/accounts', { name, type: 'BANK', currency, openingDate: isoDate(openingDate), initialBalance });
    const bank = await account('Banco', 'ARS', start, 200000);
    const dollars = await account('Dólares', 'USD', start, 500);
    // Abierta el primer día del mes siguiente: no se concilia en el período inicial.
    await account('Billetera', 'ARS', next, 1000);

    const entry = (period: Date, name: string, kind: string, day: number, budgetedAmount: number) =>
      create(`/api/periods/${periodOf(period)}/entries`, {
        name,
        kind,
        accountId: bank,
        dueDate: isoDate(new Date(period.getFullYear(), period.getMonth(), day)),
        budgetedAmount,
      });
    const power = await entry(start, 'Luz', 'EXPENSE', 18, 45000);
    await entry(start, 'Internet', 'EXPENSE', 20, 30000);
    const salary = await entry(next, 'Sueldo', 'INCOME', 1, 300000);

    const movement = (entryId: number, date: Date, amount: number) =>
      create(`/api/entries/${entryId}/movements`, { date: isoDate(date), amount, accountId: bank });
    // El último día del período inicial: entra en su saldo.
    await movement(power, startLast, 20000);
    // El día siguiente, de la misma partida: no entra en el saldo, sí en su real.
    await movement(power, next, 7000);
    // Sueldo del mes siguiente cobrado el último día del inicial (RN-41): entra.
    await movement(salary, startLast, 300000);
    // Compra de dólares el día 10 del período inicial.
    await create('/api/transfers', {
      sourceAccountId: bank,
      targetAccountId: dollars,
      date: isoDate(new Date(start.getFullYear(), start.getMonth(), 10)),
      sourceAmount: 125000,
      targetAmount: 100,
    });

    startViewBefore = JSON.stringify(await (await api.get(`/api/periods/${periodOf(start)}`)).json());
  });

  test.afterAll(async () => {
    await api.dispose();
    await page.close();
  });

  const blockers = () => page.getByTestId('blocker');
  const accountGroup = (name: string) => page.getByRole('table', { name: `Cuentas en ${name}` });

  test('«Cerrar mes» abre el asistente del período inicial, que se puede cerrar', async () => {
    await page.goto(`/presupuesto/${periodOf(start)}`);
    await page.getByRole('link', { name: 'Cerrar mes' }).click();

    await expect(page).toHaveURL(`/cierre/${periodOf(start)}`);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(`Cierre de ${label(start)}`);
    await expect(page.getByTestId('step')).toHaveText([
      /Pendientes/,
      /Saldos\s*Disponible más adelante/,
      /Confirmación\s*Disponible más adelante/,
    ]);
    await expect(page.getByTestId('summary')).toHaveText('Se puede cerrar el mes.');
    await expect(blockers()).toHaveCount(0);
  });

  test('lista las partidas pendientes con real y pendiente', async () => {
    const rows = page.getByTestId('pending-entries').locator('tbody tr');
    await expect(rows).toHaveCount(2);
    // Luz: 20.000,00 del último día y 7.000,00 del día siguiente cuentan en su real.
    await expect(rows.nth(0).locator('th, td')).toHaveText([
      `18/${two(start.getMonth() + 1)}/${start.getFullYear()}`,
      'Luz',
      'Gasto',
      'Banco',
      '$ 45.000,00',
      '$ 27.000,00',
      '$ 18.000,00',
      'Parcial',
    ]);
    await expect(rows.nth(1).locator('th, td')).toHaveText([
      `20/${two(start.getMonth() + 1)}/${start.getFullYear()}`,
      'Internet',
      'Gasto',
      'Banco',
      '$ 30.000,00',
      '$ 0,00',
      '$ 30.000,00',
      'Estimada',
    ]);
  });

  test('cada cuenta con su saldo calculado al último día, agrupadas por moneda', async () => {
    await expect(page.getByText(`Saldo calculado al ${shown(startLast)}`)).toBeVisible();
    // Banco: 200.000,00 − 20.000,00 + 300.000,00 (sueldo anticipado) − 125.000,00 (compra); el pago del día
    // siguiente no entra.
    await expect(accountGroup('pesos').locator('tbody tr')).toHaveText([/Banco\s*\$ 355\.000,00/]);
    await expect(accountGroup('dólares').locator('tbody tr')).toHaveText([/Dólares\s*US\$ 600,00/]);
    // La Billetera se abrió después del último día.
    await expect(page.getByText('Billetera')).toHaveCount(0);
  });

  test('el mes siguiente tiene el anterior abierto y ofrece ir a cerrarlo, con el teclado', async () => {
    await page.goto(`/cierre/${periodOf(next)}`);

    await expect(page.getByTestId('summary')).toHaveText('Todavía no se puede cerrar el mes.');
    await expect(blockers()).toHaveCount(1);
    await expect(blockers().first()).toContainText('El mes anterior todavía está abierto.');
    // El diagnóstico se ve igual: la Billetera ya estaba abierta.
    await expect(accountGroup('pesos').locator('tbody tr')).toHaveText([/Banco/, /Billetera\s*\$ 1\.000,00/]);

    const goClose = page.getByRole('link', { name: `Ir a cerrar ${label(start)}` });
    await goClose.focus();
    await page.keyboard.press('Enter');

    await expect(page).toHaveURL(`/cierre/${periodOf(start)}`);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(`Cierre de ${label(start)}`);
    await expect(page.getByRole('heading', { level: 1 })).toBeFocused();
    await expect(page.getByTestId('summary')).toHaveText('Se puede cerrar el mes.');
  });

  test('un mes que no terminó dice cuándo termina', async () => {
    await page.goto(`/presupuesto/${periodOf(unfinished)}`);
    await page.getByRole('link', { name: 'Cerrar mes' }).click();

    await expect(page).toHaveURL(`/cierre/${periodOf(unfinished)}`);
    await expect(page.getByTestId('summary')).toHaveText('Todavía no se puede cerrar el mes.');
    await expect(blockers()).toHaveText([
      /El mes anterior todavía está abierto\./,
      `El mes termina el ${two(unfinishedLast.getDate())}/${two(unfinishedLast.getMonth() + 1)}: se puede cerrar desde ese día.`,
    ]);
  });

  test('un mes fuera de rango lo dice y ofrece ir al mes actual', async () => {
    await page.goto('/cierre/2000-01');

    await expect(page.getByRole('alert')).toContainText('Ese mes no existe en tu presupuesto');
    await expect(page.getByRole('link', { name: 'Ir al mes actual' })).toBeVisible();
    await expect(page.getByTestId('diagnosis')).toHaveCount(0);
  });

  test('ver el asistente no modificó nada', async () => {
    const after = JSON.stringify(await (await api.get(`/api/periods/${periodOf(start)}`)).json());
    expect(after).toBe(startViewBefore);
  });
});
