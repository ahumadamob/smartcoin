import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { API_URL, startSession } from './support';

/**
 * Épica 8 · Cierre de mes. HU-30 (ver qué falta para cerrar el mes) y HU-31 (resolver las partidas pendientes). Cada
 * grupo usa un usuario propio, nunca el real: nace con el
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
    const entries = page.getByTestId('pending-entry');
    await expect(entries).toHaveCount(2);
    // Luz: 20.000,00 del último día y 7.000,00 del día siguiente cuentan en su real.
    await expect(entries.nth(0).locator('legend')).toHaveText('Luz');
    await expect(entries.nth(0).locator('.entry-details')).toHaveText(
      `Gasto · Banco · vence el 18/${two(start.getMonth() + 1)}/${start.getFullYear()} · Parcial`,
    );
    await expect(entries.nth(0).getByTestId('amounts')).toHaveText(
      /Presupuestado\s*\$ 45\.000,00\s*Real\s*\$ 27\.000,00\s*Pendiente\s*\$ 18\.000,00/,
    );
    await expect(entries.nth(1).locator('legend')).toHaveText('Internet');
    await expect(entries.nth(1).locator('.entry-details')).toHaveText(
      `Gasto · Banco · vence el 20/${two(start.getMonth() + 1)}/${start.getFullYear()} · Estimada`,
    );
    await expect(entries.nth(1).getByTestId('amounts')).toHaveText(
      /Presupuestado\s*\$ 30\.000,00\s*Real\s*\$ 0,00\s*Pendiente\s*\$ 30\.000,00/,
    );
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

test.describe('HU-31 · resolver partidas pendientes al cerrar', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let api: APIRequestContext;
  let viewsBefore: string[];

  const views = async () =>
    Promise.all(
      [start, next].map(async (month) => JSON.stringify(await (await api.get(`/api/periods/${periodOf(month)}`)).json())),
    );

  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    const { token } = await startSession(page, playwright);
    api = await playwright.request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${token}` },
    });

    const created = async (path: string, data: unknown): Promise<number> => {
      const response = await api.post(path, { data });
      expect(response.status(), path).toBe(201);
      return (await response.json()).id;
    };
    const bank = await created('/api/accounts', {
      name: 'Banco', type: 'BANK', currency: 'ARS', openingDate: isoDate(start), initialBalance: 100000,
    });
    const entry = (name: string, day: number, budgetedAmount: number) =>
      created(`/api/periods/${periodOf(start)}/entries`, {
        name,
        kind: 'EXPENSE',
        accountId: bank,
        dueDate: isoDate(new Date(start.getFullYear(), start.getMonth(), day)),
        budgetedAmount,
      });
    // El ejemplo de RN-40: la luz de 45.000,00 que vence el 18, con 20.000,00 pagados.
    const power = await entry('Luz', 18, 45000);
    await entry('Internet', 20, 30000);
    await created(`/api/entries/${power}/movements`, {
      date: isoDate(new Date(start.getFullYear(), start.getMonth(), 18)),
      amount: 20000,
      accountId: bank,
    });

    viewsBefore = await views();
  });

  test.afterAll(async () => {
    await api.dispose();
    await page.close();
  });

  const pending = (name: string) => page.getByRole('group', { name });
  const effect = (name: string) => pending(name).getByTestId('effect');
  const nextStep = () => page.getByRole('button', { name: 'Siguiente: Saldos' });
  const hint = () => page.getByTestId('next-hint');
  /** El mes siguiente se nombra sin año, salvo que sea de otro año. */
  const nextMonthName = next.getFullYear() === start.getFullYear() ? MONTHS[next.getMonth()] : label(next);

  test('las pendientes arrancan sin elegir y no se puede avanzar', async () => {
    await page.goto(`/cierre/${periodOf(start)}`);

    await expect(page.getByTestId('summary')).toHaveText('Se puede cerrar el mes.');
    await expect(page.getByTestId('pending-entry')).toHaveCount(2);
    await expect(pending('Luz').getByRole('radio', { name: 'Postergar saldo' })).not.toBeChecked();
    await expect(pending('Luz').getByRole('radio', { name: 'Cerrar con lo registrado' })).not.toBeChecked();
    await expect(pending('Luz').getByText('lo que falta pasa al mes siguiente como una partida nueva')).toBeVisible();
    await expect(nextStep()).toBeDisabled();
    await expect(hint()).toHaveText('Falta elegir qué hacer con 2 partidas pendientes.');
  });

  test('Postergar saldo en la luz anuncia el saldo pendiente en el mes siguiente', async () => {
    await pending('Luz').getByRole('radio', { name: 'Postergar saldo' }).check();

    await expect(effect('Luz')).toHaveText(
      `Queda consolidada en $ 20.000,00. En ${nextMonthName} aparece «Saldo pendiente: Luz» por $ 25.000,00, ` +
        `con vencimiento el 18/${two(next.getMonth() + 1)}.`,
    );
    await expect(nextStep()).toBeDisabled();
    await expect(hint()).toHaveText('Falta elegir qué hacer con 1 partida pendiente.');
  });

  test('Cerrar con lo registrado en la partida sin movimientos la deja en 0 y descarta el pendiente', async () => {
    // Con el teclado: el foco entra al grupo y la barra espaciadora elige la opción.
    await pending('Internet').getByRole('radio', { name: 'Cerrar con lo registrado' }).focus();
    await page.keyboard.press('Space');

    await expect(pending('Internet').getByRole('radio', { name: 'Cerrar con lo registrado' })).toBeChecked();
    await expect(effect('Internet')).toHaveText('Queda consolidada en $ 0,00. Se descarta el pendiente de $ 30.000,00.');
    await expect(hint()).toHaveText(
      'Todas las partidas pendientes tienen una resolución. El paso «Saldos» estará disponible más adelante.',
    );
    // «Saldos» llega con HU-32: con todo resuelto, el botón sigue deshabilitado.
    await expect(nextStep()).toBeDisabled();
    await expect(effect('Luz')).toContainText('Saldo pendiente: Luz');
  });

  test('todavía no se creó ni consolidó nada: el mes siguiente y el inicial siguen igual', async () => {
    await page.goto(`/presupuesto/${periodOf(next)}`);
    await expect(page.getByRole('heading', { level: 1 })).toContainText(label(next));
    await expect(page.getByText('Saldo pendiente: Luz')).toHaveCount(0);

    await page.goto(`/presupuesto/${periodOf(start)}`);
    await expect(page.getByRole('row', { name: /Luz/ })).toContainText('Parcial');
    await expect(page.getByRole('row', { name: /Internet/ })).toContainText('Estimada');

    expect(await views()).toEqual(viewsBefore);
  });
});
