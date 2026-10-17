import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { API_URL, startSession } from './support';

/**
 * Épica 8 · Cierre de mes. HU-30 (ver qué falta para cerrar el mes), HU-31 (resolver las partidas pendientes), HU-32
 * (conciliar saldos y ver las diferencias) y HU-33 (el cierre efectivo, que deja el mes inmutable). Cada
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
/** El mes siguiente al inicial se nombra sin año, salvo que sea de otro año. */
const nextMonthName = next.getFullYear() === start.getFullYear() ? MONTHS[next.getMonth()] : label(next);
/** Los meses abreviados de «Diferencia de cierre: <cuenta> (<mes> <año>)». */
const ABBREVIATIONS = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic'];
const closedLabel = `${ABBREVIATIONS[start.getMonth()]} ${start.getFullYear()}`;

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
      /^2\s*Saldos\s*$/,
      /^3\s*Confirmación\s*$/,
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

  test('en «Saldos», cada cuenta con su saldo calculado al último día, agrupadas por moneda', async () => {
    await page.getByRole('button', { name: 'Cerrar con lo registrado en todas' }).click();
    await page.getByRole('button', { name: 'Siguiente: Saldos' }).click();

    await expect(page.getByTestId('balances-intro')).toContainText(`al último día del mes (${shown(startLast)})`);
    // Banco: 200.000,00 − 20.000,00 + 300.000,00 (sueldo anticipado) − 125.000,00 (compra); el pago del día
    // siguiente no entra.
    await expect(accountGroup('pesos').getByTestId('balance-row')).toHaveText([/Banco\s*\$ 355\.000,00/]);
    await expect(accountGroup('dólares').getByTestId('balance-row')).toHaveText([/Dólares\s*US\$ 600,00/]);
    // La Billetera se abrió después del último día.
    await expect(page.getByRole('rowheader', { name: 'Billetera' })).toHaveCount(0);
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
    await expect(hint()).toHaveText('Todas las partidas pendientes tienen una resolución.');
    // Con todo resuelto, se puede pasar a «Saldos» (HU-32).
    await expect(nextStep()).toBeEnabled();
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

test.describe('HU-32 · conciliar saldos y registrar diferencias', () => {
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
    const account = (name: string, currency: string, initialBalance: number) =>
      created('/api/accounts', {
        name, type: 'BANK', currency, openingDate: isoDate(start), initialBalance,
      });
    const bank = await account('Banco', 'ARS', 200000);
    await account('Efectivo', 'ARS', 50000);
    await account('Dólares', 'USD', 500);
    // Una pendiente con un pago parcial: el saldo del Banco es 200.000,00 − 20.000,00.
    const power = await created(`/api/periods/${periodOf(start)}/entries`, {
      name: 'Luz',
      kind: 'EXPENSE',
      accountId: bank,
      dueDate: isoDate(new Date(start.getFullYear(), start.getMonth(), 18)),
      budgetedAmount: 45000,
    });
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

  const row = (name: string) => page.getByTestId('balance-row').filter({ has: page.getByRole('rowheader', { name }) });
  const balance = (name: string) => page.getByLabel(`Saldo real de ${name}`);
  const effect = (name: string) => row(name).getByTestId('difference-effect');
  const confirm = () => page.getByRole('button', { name: 'Siguiente: Confirmación' });
  const confirmHint = () => page.getByTestId('confirm-hint');

  /** Escribe el saldo real y sale del campo, como lo haría el usuario. */
  async function enter(name: string, value: string) {
    await balance(name).fill(value);
    await balance(name).press('Tab');
  }

  test('con las pendientes resueltas se pasa a «Saldos», que muestra cada cuenta con su saldo calculado', async () => {
    await page.goto(`/cierre/${periodOf(start)}`);
    await page.getByRole('group', { name: 'Luz' }).getByRole('radio', { name: 'Cerrar con lo registrado' }).check();
    await page.getByRole('button', { name: 'Siguiente: Saldos' }).click();

    await expect(page.getByTestId('step').nth(1)).toHaveAttribute('aria-current', 'step');
    await expect(page.getByTestId('balances-intro')).toContainText(
      `El saldo real es el que figura en el banco o la billetera al último día del mes (${shown(startLast)})`,
    );
    await expect(row('Banco')).toContainText('$ 180.000,00');
    await expect(row('Efectivo')).toContainText('$ 50.000,00');
    await expect(row('Dólares')).toContainText('US$ 500,00');
    await expect(page.getByRole('table', { name: 'Cuentas en pesos' }).getByTestId('balance-row')).toHaveCount(2);
    await expect(page.getByRole('table', { name: 'Cuentas en dólares' }).getByTestId('balance-row')).toHaveCount(1);
  });

  test('no se puede avanzar a «Confirmación» con un saldo sin cargar', async () => {
    await expect(confirm()).toBeDisabled();
    await expect(confirmHint()).toHaveText('Falta cargar el saldo real de 3 cuentas.');
  });

  test('con el mismo saldo calculado ve «Coincide»', async () => {
    await enter('Banco', '180.000,00');

    await expect(effect('Banco')).toHaveText('Coincide.');
    await expect(row('Banco').getByTestId('difference')).toHaveText('$ 0,00');
    await expect(confirmHint()).toHaveText('Falta cargar el saldo real de 2 cuentas.');
  });

  test('con un saldo menor ve el gasto anunciado en el mes siguiente', async () => {
    await enter('Efectivo', '47.500,00');

    await expect(row('Efectivo').getByTestId('difference')).toHaveText('-$ 2.500,00');
    await expect(effect('Efectivo')).toHaveText(
      `En ${nextMonthName} aparece un gasto «Diferencia de cierre: Efectivo (${closedLabel})» por $ 2.500,00.`,
    );
    await expect(confirm()).toBeDisabled();
    await expect(confirmHint()).toHaveText('Falta cargar el saldo real de 1 cuenta.');
  });

  test('con un saldo mayor en dólares ve el ingreso anunciado en dólares', async () => {
    await enter('Dólares', '650');

    await expect(row('Dólares').getByTestId('difference')).toHaveText('US$ 150,00');
    await expect(effect('Dólares')).toHaveText(
      `En ${nextMonthName} aparece un ingreso «Diferencia de cierre: Dólares (${closedLabel})» por US$ 150,00.`,
    );
    await expect(confirmHint()).toHaveText('Todos los saldos reales están cargados.');
    await expect(confirm()).toBeEnabled();
  });

  test('un saldo con formato inválido lo dice y vuelve a faltar', async () => {
    await enter('Banco', '180.000.5');

    await expect(row('Banco').locator('mat-error')).toHaveText(
      'Escribilo con coma decimal y hasta 2 decimales, por ejemplo 1500,50.',
    );
    await expect(confirmHint()).toHaveText('Revisá el saldo real con formato inválido.');
    await expect(confirm()).toBeDisabled();

    await enter('Banco', '180.000,00');
    await expect(effect('Banco')).toHaveText('Coincide.');
  });

  test('se puede volver a «Pendientes» sin perder lo cargado', async () => {
    await page.getByRole('button', { name: 'Volver a Pendientes' }).click();

    await expect(page.getByTestId('step').nth(0)).toHaveAttribute('aria-current', 'step');
    await expect(
      page.getByRole('group', { name: 'Luz' }).getByRole('radio', { name: 'Cerrar con lo registrado' }),
    ).toBeChecked();

    await page.getByRole('button', { name: 'Siguiente: Saldos' }).click();
    await expect(balance('Banco')).toHaveValue('180.000,00');
    await expect(balance('Efectivo')).toHaveValue('47.500,00');
    await expect(balance('Dólares')).toHaveValue('650');
    await expect(effect('Efectivo')).toContainText('un gasto');
    await expect(effect('Dólares')).toContainText('un ingreso');
  });

  test('todavía no se creó nada: el mes siguiente y el inicial siguen igual', async () => {
    await page.goto(`/presupuesto/${periodOf(next)}`);
    await expect(page.getByRole('heading', { level: 1 })).toContainText(label(next));
    await expect(page.getByText('Diferencia de cierre')).toHaveCount(0);

    await page.goto(`/presupuesto/${periodOf(start)}`);
    await expect(page.getByRole('row', { name: /Luz/ })).toContainText('Parcial');

    expect(await views()).toEqual(viewsBefore);
  });
});

test.describe('HU-33 · período cerrado inmutable', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let api: APIRequestContext;
  let bank: number;

  /** El mes siguiente al segundo: el actual. Su nombre en los textos del asistente del segundo mes. */
  const third = new Date(next.getFullYear(), next.getMonth() + 1, 1);
  const thirdName = third.getFullYear() === next.getFullYear() ? MONTHS[third.getMonth()] : label(third);
  const nextClosedLabel = `${ABBREVIATIONS[next.getMonth()]} ${next.getFullYear()}`;
  const capitalized = (text: string) => text.charAt(0).toUpperCase() + text.slice(1);

  const created = async (path: string, data: unknown): Promise<number> => {
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
    const account = (name: string, currency: string, initialBalance: number) =>
      created('/api/accounts', { name, type: 'BANK', currency, openingDate: isoDate(start), initialBalance });
    bank = await account('Banco', 'ARS', 200000);
    await account('Dólares', 'USD', 500);
    const entry = (name: string, day: number, budgetedAmount: number) =>
      created(`/api/periods/${periodOf(start)}/entries`, {
        name,
        kind: 'EXPENSE',
        accountId: bank,
        dueDate: isoDate(new Date(start.getFullYear(), start.getMonth(), day)),
        budgetedAmount,
      });
    // La luz de RN-40: 45.000,00 con 20.000,00 pagados. El Banco queda en 180.000,00.
    const power = await entry('Luz', 18, 45000);
    await entry('Internet', 20, 30000);
    await created(`/api/entries/${power}/movements`, {
      date: isoDate(new Date(start.getFullYear(), start.getMonth(), 18)),
      amount: 20000,
      accountId: bank,
    });
  });

  test.afterAll(async () => {
    await api.dispose();
    await page.close();
  });

  const group = (name: string) => page.getByRole('group', { name });
  const balance = (name: string) => page.getByLabel(`Saldo real de ${name}`);
  async function enter(name: string, value: string) {
    await balance(name).fill(value);
    await balance(name).press('Tab');
  }
  const closeButton = (month: Date) => page.getByRole('button', { name: `Cerrar ${label(month)}` });
  const acknowledge = (month: Date) =>
    page.getByRole('checkbox', { name: `Entiendo que ${label(month)} queda cerrado para siempre y no se puede reabrir.` });

  test('cerrar primero el segundo mes responde PREVIOUS_PERIOD_OPEN', async () => {
    const response = await api.post(`/api/periods/${periodOf(next)}/close`, { data: {} });

    expect(response.status()).toBe(409);
    expect((await response.json()).code).toBe('PREVIOUS_PERIOD_OPEN');
    const view = await (await api.get(`/api/periods/${periodOf(next)}`)).json();
    expect(view.status).toBe('OPEN');
  });

  test('cerrar el período inicial con una partida postergada y una diferencia de saldo', async () => {
    await page.goto(`/cierre/${periodOf(start)}`);
    await group('Luz').getByRole('radio', { name: 'Postergar saldo' }).check();
    await group('Internet').getByRole('radio', { name: 'Cerrar con lo registrado' }).check();
    await page.getByRole('button', { name: 'Siguiente: Saldos' }).click();
    await enter('Banco', '177.500,00');
    await enter('Dólares', '500');
    await page.getByRole('button', { name: 'Siguiente: Confirmación' }).click();

    await expect(page.getByTestId('step').nth(2)).toHaveAttribute('aria-current', 'step');
    await expect(page.getByTestId('summary-consolidated').getByRole('listitem')).toHaveText([
      'Luz Postergar saldo: se consolida en $ 20.000,00.',
      'Internet Cerrar con lo registrado: se consolida en $ 0,00.',
    ]);
    await expect(page.getByTestId('summary-created').getByRole('listitem')).toHaveText([
      `Saldo pendiente: Luz Gasto por $ 25.000,00, con vencimiento el 18/${two(next.getMonth() + 1)}.`,
      `Diferencia de cierre: Banco (${closedLabel}) Gasto por $ 2.500,00, con vencimiento el 01/${two(next.getMonth() + 1)}.`,
    ]);
    await expect(page.getByTestId('summary-accounts').first().getByRole('row', { name: /Banco/ })).toContainText(
      '-$ 2.500,00',
    );
    await expect(page.getByTestId('irreversible-warning')).toContainText(
      `Al cerrar, ${label(start)} queda cerrado para siempre`,
    );

    // El botón exige la casilla; se marca con el teclado.
    await expect(closeButton(start)).toBeDisabled();
    await acknowledge(start).focus();
    await page.keyboard.press('Space');
    await expect(acknowledge(start)).toBeChecked();
    await closeButton(start).click();

    await expect(page).toHaveURL(`/presupuesto/${periodOf(start)}`);
    await expect(page.getByText(`${capitalized(label(start))} quedó cerrado.`)).toBeVisible();
  });

  test('el mes cerrado se ve «Cerrado» y sin acciones', async () => {
    await expect(page.getByTestId('closed')).toHaveText('Cerrado');
    await expect(page.getByRole('link', { name: 'Cerrar mes' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Agregar partida' })).toHaveCount(0);
    await expect(page.getByRole('row', { name: /Luz/ })).toContainText('Consolidada');
    await expect(page.getByRole('row', { name: /Internet/ })).toContainText('Consolidada');
    await expect(page.getByRole('row', { name: /Luz/ }).getByRole('button')).toHaveCount(0);

    // Un cambio en el mes cerrado, por la API: 409 PERIOD_CLOSED.
    const response = await api.post(`/api/periods/${periodOf(start)}/entries`, {
      data: {
        name: 'Tarde', kind: 'EXPENSE', accountId: bank,
        dueDate: isoDate(new Date(start.getFullYear(), start.getMonth(), 5)), budgetedAmount: 100,
      },
    });
    expect(response.status()).toBe(409);
    expect((await response.json()).code).toBe('PERIOD_CLOSED');
  });

  test('el aviso lleva al mes siguiente, con las partidas nuevas y «Cerrar mes»', async () => {
    await page.getByRole('button', { name: `Ir a ${label(next)}` }).click();

    await expect(page).toHaveURL(`/presupuesto/${periodOf(next)}`);
    await expect(page.getByRole('row', { name: /Saldo pendiente: Luz/ })).toContainText('$ 25.000,00');
    await expect(page.getByRole('row', { name: new RegExp(`Diferencia de cierre: Banco \\(${closedLabel}\\)`) }))
      .toContainText('$ 2.500,00');
    await expect(page.getByTestId('closed')).toHaveCount(0);

    await page.getByRole('link', { name: 'Cerrar mes' }).click();
    await expect(page).toHaveURL(`/cierre/${periodOf(next)}`);
    await expect(page.getByTestId('summary')).toHaveText('Se puede cerrar el mes.');
  });

  test('cerrar el segundo mes: un error lleva al paso que corresponde sin perder lo cargado', async () => {
    await group('Saldo pendiente: Luz').getByRole('radio', { name: 'Postergar saldo' }).check();
    // Postergar un saldo postergado conserva su nombre (RN-40).
    await expect(group('Saldo pendiente: Luz').getByTestId('effect')).toContainText(
      `En ${thirdName} aparece «Saldo pendiente: Luz» por $ 25.000,00`,
    );
    await group(`Diferencia de cierre: Banco (${closedLabel})`)
      .getByRole('radio', { name: 'Cerrar con lo registrado' })
      .check();
    await page.getByRole('button', { name: 'Siguiente: Saldos' }).click();
    // La diferencia sigue pendiente: el saldo calculado la sigue arrastrando (RN-42).
    await expect(page.getByTestId('balance-row').filter({ hasText: 'Banco' })).toContainText('$ 180.000,00');
    await enter('Banco', '177.500,00');
    await enter('Dólares', '500');
    await page.getByRole('button', { name: 'Siguiente: Confirmación' }).click();
    await expect(page.getByTestId('summary-created').getByRole('listitem')).toHaveCount(2);

    // Mientras tanto, desde otra pestaña, aparece una partida nueva en el mes: queda sin resolver.
    await created(`/api/periods/${periodOf(next)}/entries`, {
      name: 'Gas', kind: 'EXPENSE', accountId: bank,
      dueDate: isoDate(new Date(next.getFullYear(), next.getMonth(), 10)), budgetedAmount: 8000,
    });
    await acknowledge(next).check();
    await closeButton(next).click();

    await expect(page.getByTestId('close-error')).toHaveText(
      'No se cerró el mes: Falta resolver 1 partida pendiente: elegí «Postergar saldo» o «Cerrar con lo registrado» ' +
        'en cada una.',
    );
    await expect(page.getByTestId('step').nth(0)).toHaveAttribute('aria-current', 'step');
    await expect(group('Saldo pendiente: Luz').getByRole('radio', { name: 'Postergar saldo' })).toBeChecked();
    await expect(page.getByTestId('next-hint')).toHaveText('Falta elegir qué hacer con 1 partida pendiente.');
    await expect(page).toHaveURL(`/cierre/${periodOf(next)}`);
    expect((await (await api.get(`/api/periods/${periodOf(next)}`)).json()).status).toBe('OPEN');
  });

  test('resolviendo la que faltaba, se cierra el segundo mes', async () => {
    await group('Gas').getByRole('radio', { name: 'Cerrar con lo registrado' }).check();
    await page.getByRole('button', { name: 'Siguiente: Saldos' }).click();
    await expect(balance('Banco')).toHaveValue('177.500,00');
    await page.getByRole('button', { name: 'Siguiente: Confirmación' }).click();
    await expect(page.getByTestId('summary-consolidated').getByRole('listitem')).toHaveCount(3);
    await acknowledge(next).check();
    await closeButton(next).click();

    await expect(page).toHaveURL(`/presupuesto/${periodOf(next)}`);
    await expect(page.getByTestId('closed')).toHaveText('Cerrado');

    await page.getByRole('button', { name: `Ir a ${label(third)}` }).click();
    await expect(page).toHaveURL(`/presupuesto/${periodOf(third)}`);
    // Conserva su nombre, y la diferencia que se cerró con lo registrado vuelve a aparecer (RN-42).
    await expect(page.getByRole('row', { name: /Saldo pendiente: Luz/ })).toHaveCount(1);
    await expect(page.getByText('Saldo pendiente: Saldo pendiente')).toHaveCount(0);
    await expect(page.getByRole('row', { name: new RegExp(`Diferencia de cierre: Banco \\(${nextClosedLabel}\\)`) }))
      .toContainText('$ 2.500,00');
  });

  test('el mes actual responde PERIOD_NOT_FINISHED', async () => {
    if (isLastDay) {
      // Hoy terminó el actual: se cierra por la API y se prueba el siguiente.
      const preview = await (await api.post(`/api/periods/${periodOf(current)}/closing-preview`, { data: {} })).json();
      const closed = await api.post(`/api/periods/${periodOf(current)}/close`, {
        data: {
          resolutions: preview.pendingEntries.map((e: { id: number }) => ({ entryId: e.id, resolution: 'CLOSE_AS_IS' })),
          realBalances: preview.accounts.map((a: { id: number; computedBalance: number }) => ({
            accountId: a.id,
            amount: a.computedBalance,
          })),
        },
      });
      expect(closed.status()).toBe(200);
    }
    const response = await api.post(`/api/periods/${periodOf(unfinished)}/close`, { data: {} });

    expect(response.status()).toBe(409);
    const problem = await response.json();
    expect(problem.code).toBe('PERIOD_NOT_FINISHED');
    expect(problem.detail).toContain(shown(unfinishedLast));
  });
});
