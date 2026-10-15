import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { API_URL, startPeriod, startSession } from './support';

/**
 * Épica 6 · Consolidación. HU-23 (consolidar una partida), HU-24 (promedio), HU-25 (partidas editadas) y HU-22
 * (pago rápido). Un usuario propio: se consolida siempre con un usuario de
 * prueba, nunca con el real. La cuenta y el Concepto se cargan por la API (sus pantallas las cubren otras épicas);
 * el cobro, la vista previa y la consolidación se hacen en la pantalla.
 *
 * Todas las fechas salen de hoy y el período inicial es el actual: el test pasa cualquier día del mes y en diciembre.
 */

const two = (n: number) => String(n).padStart(2, '0');
const isoDate = (date: Date) => `${date.getFullYear()}-${two(date.getMonth() + 1)}-${two(date.getDate())}`;
const periodOf = (date: Date) => `${date.getFullYear()}-${two(date.getMonth() + 1)}`;
const monthFrom = (offset: number) => new Date(today.getFullYear(), today.getMonth() + offset, 1);

const today = new Date();
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
const periodText = (date: Date) => `${MONTHS[date.getMonth()]} ${date.getFullYear()}`;

test.describe('HU-23 · consolidar una partida', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let api: APIRequestContext;
  let accountId: number;
  let token: string;

  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    ({ token } = await startSession(page, playwright));
    api = await playwright.request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${token}` },
    });
    const account = await api.post('/api/accounts', {
      data: { name: 'Banco Nación', type: 'BANK', currency: 'ARS', openingDate: `${startPeriod()}-01`, initialBalance: 0 },
    });
    expect(account.status()).toBe(201);
    accountId = (await account.json()).id;
    const item = await api.post('/api/budget-items', {
      data: {
        name: 'Sueldo',
        kind: 'INCOME',
        defaultAccountId: accountId,
        periodicity: 'MONTHLY',
        dueDay: 25,
        dueMonthOffset: 0,
        startPeriod: periodOf(today),
        estimationRule: 'LAST_VALUE',
        currentAmount: 1800000,
      },
    });
    expect(item.status(), 'alta del Concepto').toBe(201);
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

  async function entryIds(period: string, side: 'incomes' | 'expenses'): Promise<Record<string, number>> {
    const view = await (await api.get(`/api/periods/${period}`)).json();
    return Object.fromEntries((view[side] as { name: string; id: number }[]).map((e) => [e.name, e.id]));
  }

  async function oneOff(name: string, kind: 'INCOME' | 'EXPENSE', amount: number): Promise<number> {
    const response = await api.post(`/api/periods/${periodOf(today)}/entries`, {
      data: { name, kind, accountId, dueDate: isoDate(today), budgetedAmount: amount },
    });
    expect(response.status(), `alta de ${name}`).toBe(201);
    return (await response.json()).id;
  }

  async function pay(entryId: number, amount: number) {
    const response = await api.post(`/api/entries/${entryId}/movements`, {
      data: { date: isoDate(today), amount, accountId },
    });
    expect(response.status(), 'movimiento').toBe(201);
  }

  test('un cobro distinto del presupuestado, vista previa, consolidar: el monto se propaga a los meses siguientes', async () => {
    await page.goto('/presupuesto');
    await expect(row('Ingresos', 'Sueldo')).toBeVisible();
    // Sin movimientos no se ofrece consolidar.
    await expect(row('Ingresos', 'Sueldo').getByRole('button', { name: 'Consolidar Sueldo' })).toHaveCount(0);

    await row('Ingresos', 'Sueldo').getByRole('button', { name: 'Registrar cobro de Sueldo' }).click();
    await dialog().getByLabel('Monto').fill('1.850.000,00');
    await dialog().getByRole('button', { name: 'Registrar cobro', exact: true }).click();
    await expect(dialog()).toBeHidden();
    await expect(cells(row('Ingresos', 'Sueldo')).nth(8)).toHaveText('Parcial');

    await row('Ingresos', 'Sueldo').getByRole('button', { name: 'Consolidar Sueldo' }).click();

    // La vista previa sale del backend: real, monto vigente actual, nueva estimación y los 24 meses que cambian.
    await expect(dialog().getByRole('heading', { name: `Consolidar «Sueldo» de ${periodText(today)}` })).toBeVisible();
    await expect(dialog().getByTestId('actual')).toHaveText('$ 1.850.000,00');
    await expect(dialog().getByTestId('current-amount')).toHaveText('$ 1.800.000,00');
    await expect(dialog().getByTestId('estimate')).toHaveText('$ 1.850.000,00');
    await expect(dialog().getByTestId('rule')).toContainText('Último valor');
    await expect(dialog().getByTestId('target')).toHaveCount(24);
    const first = dialog().getByTestId('target').first();
    await expect(first).toContainText(periodText(monthFrom(1)));
    await expect(first).toContainText('$ 1.800.000,00');
    await expect(first).toContainText('$ 1.850.000,00');
    await expect(dialog().getByTestId('irreversible')).toContainText('no se puede deshacer');
    await expect(dialog().getByTestId('confirm-consolidate')).toBeEnabled();

    await dialog().getByTestId('confirm-consolidate').click();

    await expect(dialog()).toBeHidden();
    // Consolidada, con el monto real, pendiente 0 y sin acciones.
    const entry = cells(row('Ingresos', 'Sueldo'));
    await expect(entry.nth(8)).toHaveText('Consolidada');
    await expect(entry.nth(6)).toHaveText('$ 1.850.000,00');
    await expect(entry.nth(7)).toHaveText('$ 0,00');
    await expect(row('Ingresos', 'Sueldo').getByRole('button')).toHaveCount(0);
    await expect(page.getByText('«Sueldo» consolidada por $ 1.850.000,00. Se actualizaron 24 partidas')).toBeVisible();
    // El estimado de la consolidada es su monto consolidado: el resultado del mes lo usa.
    await expect(page.getByTestId('result')).toContainText('$ 1.850.000,00');

    // El mes siguiente y uno lejano (el horizonte) tomaron el valor nuevo.
    for (const offset of [1, 24]) {
      await page.goto(`/presupuesto/${periodOf(monthFrom(offset))}`);
      await expect(cells(row('Ingresos', 'Sueldo')).nth(5)).toContainText('$ 1.850.000,00');
      await expect(cells(row('Ingresos', 'Sueldo')).nth(8)).toHaveText('Estimada');
    }

    // El monto vigente cambió en la lista de Conceptos.
    await page.goto('/conceptos');
    await expect(page.getByRole('row').filter({ hasText: 'Sueldo' })).toContainText('$ 1.850.000,00');
  });

  test('una partida puntual se consolida sin propagar y sin cambiar nada más', async () => {
    const id = await oneOff('Venta de bicicleta', 'INCOME', 70000);
    await pay(id, 65000);
    await page.goto('/presupuesto');

    await row('Ingresos', 'Venta de bicicleta').getByRole('button', { name: 'Consolidar Venta de bicicleta' }).click();

    await expect(dialog().getByTestId('actual')).toHaveText('$ 65.000,00');
    await expect(dialog().getByTestId('no-propagation')).toContainText('no afecta otros meses');
    await expect(dialog().getByTestId('target')).toHaveCount(0);
    await dialog().getByTestId('confirm-consolidate').click();

    await expect(dialog()).toBeHidden();
    const entry = cells(row('Ingresos', 'Venta de bicicleta'));
    await expect(entry.nth(8)).toHaveText('Consolidada');
    await expect(entry.nth(6)).toHaveText('$ 65.000,00');
    await expect(page.getByText('«Venta de bicicleta» consolidada por $ 65.000,00.', { exact: true })).toBeVisible();

    // Nada más cambió: el sueldo del mes siguiente y el monto vigente siguen igual, y la puntual no se copió.
    await page.goto(`/presupuesto/${periodOf(monthFrom(1))}`);
    await expect(row('Ingresos', 'Sueldo')).toHaveCount(1);
    await expect(section('Ingresos').locator('tbody tr')).toHaveCount(1);
    await expect(cells(row('Ingresos', 'Sueldo')).nth(5)).toContainText('$ 1.850.000,00');
    const concepts = await (await api.get('/api/budget-items')).json();
    const sueldo = (concepts.items ?? concepts).find((c: { name: string }) => c.name === 'Sueldo');
    expect(sueldo.currentAmount).toBe(1850000);
  });

  test('error: una partida que ya se consolidó en otra pestaña explica el motivo y no deja confirmar', async () => {
    const id = await oneOff('Patente', 'EXPENSE', 30000);
    await pay(id, 30000);
    await page.goto('/presupuesto');
    await expect(row('Gastos', 'Patente').getByRole('button', { name: 'Consolidar Patente' })).toBeVisible();

    // Otra pestaña (la API) la consolida mientras esta pantalla sigue mostrándola pendiente.
    const other = await api.post(`/api/entries/${id}/consolidate`, { data: {} });
    expect(other.status()).toBe(200);

    await row('Gastos', 'Patente').getByRole('button', { name: 'Consolidar Patente' }).click();

    await expect(dialog().getByTestId('blocked')).toContainText('ya está consolidada');
    await expect(dialog().getByTestId('confirm-consolidate')).toBeDisabled();
    await dialog().getByTestId('cancel-consolidate').click();
    await expect(dialog()).toBeHidden();
  });

  test('la API rechaza consolidar sin movimientos, dos veces y una partida de otro usuario, sin cambiar nada', async ({
    browser,
    playwright,
  }) => {
    const empty = await oneOff('Gas', 'EXPENSE', 20000);
    const withoutMovements = await api.post(`/api/entries/${empty}/consolidate`, { data: {} });
    expect(withoutMovements.status()).toBe(409);
    expect((await withoutMovements.json()).code).toBe('CONSOLIDATION_REQUIRES_MOVEMENT');

    const patente = (await entryIds(periodOf(today), 'expenses'))['Patente'];
    const again = await api.post(`/api/entries/${patente}/consolidate`, { data: {} });
    expect(again.status()).toBe(409);
    expect((await again.json()).code).toBe('ENTRY_NOT_PENDING');

    // La vista previa de una partida sin movimientos responde 200 con el motivo, sin cambiar nada.
    const preview = await (await api.get(`/api/entries/${empty}/consolidation-preview`)).json();
    expect(preview.allowed).toBe(false);
    expect(preview.blockedBy).toBe('CONSOLIDATION_REQUIRES_MOVEMENT');

    // Otro usuario no ve la partida: 404 también en la vista previa.
    const otherPage = await browser.newPage();
    const { token: otherToken } = await startSession(otherPage, playwright);
    await otherPage.close();
    const stranger = await playwright.request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${otherToken}` },
    });
    const sueldo = (await entryIds(periodOf(today), 'incomes'))['Sueldo'];
    expect((await stranger.post(`/api/entries/${sueldo}/consolidate`, { data: {} })).status()).toBe(404);
    expect((await stranger.get(`/api/entries/${sueldo}/consolidation-preview`)).status()).toBe(404);
    await stranger.dispose();

    // La partida del otro usuario sigue como estaba.
    const view = await (await api.get(`/api/periods/${periodOf(today)}`)).json();
    expect(view.incomes.find((e: { name: string }) => e.name === 'Sueldo').status).toBe('CONSOLIDATED');
  });
});

/**
 * HU-24 (estimar con el promedio de los últimos 3). Un usuario propio, con el período inicial dos meses antes del
 * actual: se pueden consolidar como máximo tres meses de un Concepto mensual (el actual y los dos anteriores). Los
 * montos están elegidos para que el orden y el redondeo cambien el resultado: 100,00 (mes anterior), 200,01 (mes
 * actual) y 400,00 (dos meses antes, consolidado último). El promedio de los dos primeros es 150,005 → 150,01 y el
 * de los tres, 233,3366… → 233,34; el último valor por orden de consolidación daría 400,00.
 *
 * «Más de 3» solo se prueba con tests unitarios (no hay cuatro meses consolidables) y las consolidaciones de cierre,
 * en HU-31.
 */
test.describe('HU-24 · estimar con el promedio de los últimos 3', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let api: APIRequestContext;
  let accountId: number;

  const months = [monthFrom(-2), monthFrom(-1), monthFrom(0)];
  /** Un día de cada mes que ya pasó (el día 1) y, en el actual, hoy: nunca una fecha futura. */
  const movementDate = (month: Date) => (periodOf(month) === periodOf(today) ? isoDate(today) : `${periodOf(month)}-01`);
  /** Los meses como los nombra el diálogo: sin año si son todos del mismo. */
  const names = (dates: Date[]) => {
    const sameYear = new Set(dates.map((d) => d.getFullYear())).size === 1;
    return [...dates]
      .sort((a, b) => a.getTime() - b.getTime())
      .map((d) => (sameYear ? MONTHS[d.getMonth()] : periodText(d)));
  };

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
    accountId = (await account.json()).id;
    const item = await api.post('/api/budget-items', {
      data: {
        name: 'Luz',
        kind: 'EXPENSE',
        defaultAccountId: accountId,
        periodicity: 'MONTHLY',
        dueDay: 10,
        dueMonthOffset: 0,
        startPeriod: periodOf(months[0]),
        estimationRule: 'AVERAGE_LAST_3',
        currentAmount: 50000,
      },
    });
    expect(item.status(), 'alta del Concepto').toBe(201);
  });

  test.afterAll(async () => {
    await api.dispose();
    await page.close();
  });

  const section = (name: string) => page.getByRole('region', { name, exact: true });
  const row = () =>
    section('Gastos')
      .locator('tbody tr')
      .filter({ has: page.getByRole('rowheader', { name: 'Luz', exact: true }) });
  const dialog = () => page.getByRole('dialog');

  async function pay(month: Date, amount: number) {
    const view = await (await api.get(`/api/periods/${periodOf(month)}`)).json();
    const id = (view.expenses as { name: string; id: number }[]).find((e) => e.name === 'Luz')!.id;
    const response = await api.post(`/api/entries/${id}/movements`, {
      data: { date: movementDate(month), amount, accountId },
    });
    expect(response.status(), 'movimiento').toBe(201);
  }

  /** Abre el diálogo de consolidación de la Luz del mes, comprueba lo que explica y confirma. */
  async function consolidate(month: Date, estimate: string, basis: string | RegExp) {
    await page.goto(`/presupuesto/${periodOf(month)}`);
    await row().getByRole('button', { name: 'Consolidar Luz' }).click();
    await expect(dialog().getByTestId('estimate')).toHaveText(estimate);
    await expect(dialog().getByTestId('rule')).toContainText('Promedio de los últimos 3');
    await expect(dialog().getByTestId('average-basis')).toHaveText(basis);
    await dialog().getByTestId('confirm-consolidate').click();
    await expect(dialog()).toBeHidden();
    await expect(row().locator('th, td').nth(8)).toHaveText('Consolidada');
  }

  test('una consolidación: la estimación es ese monto y el diálogo aclara que promedia la que hay', async () => {
    await pay(months[1], 100);

    await consolidate(months[1], '$ 100,00', new RegExp(`^Promedio de ${names([months[1]])[0]}: la única consolidada`));
  });

  test('dos consolidaciones: el promedio de las dos, con medio centavo hacia arriba', async () => {
    await pay(months[2], 200.01);

    const [first, second] = names([months[1], months[2]]);
    await consolidate(months[2], '$ 150,01', new RegExp(`^Promedio de ${first} y ${second}: las únicas consolidadas`));
  });

  test('tres consolidaciones, la más vieja al final: el promedio de las tres con redondeo', async () => {
    await pay(months[0], 400);

    const [a, b, c] = names(months);
    await consolidate(months[0], '$ 233,34', `Promedio de ${a}, ${b} y ${c}.`);
  });

  test('el monto vigente es el promedio en el mes siguiente y en la lista de Conceptos', async () => {
    await page.goto(`/presupuesto/${periodOf(monthFrom(1))}`);
    const cells = row().locator('th, td');
    await expect(cells.nth(5)).toContainText('$ 233,34');
    await expect(cells.nth(8)).toHaveText('Estimada');

    await page.goto('/conceptos');
    await expect(page.getByRole('row').filter({ hasText: 'Luz' })).toContainText('$ 233,34');

    // El mes actual, consolidado, conserva su monto real y no lo pisó el promedio.
    await page.goto('/presupuesto');
    await expect(row().locator('th, td').nth(6)).toHaveText('$ 200,01');
  });
});

/**
 * HU-25 (decidir sobre partidas editadas al consolidar): el ejemplo del Resumen Visa de RN-26. Un usuario propio y
 * Conceptos mensuales con último valor desde el mes actual. En cada uno se edita el monto del mes siguiente, se
 * paga el mes actual por otro monto y se consolida desde la pantalla. Todas las fechas salen de hoy.
 */
test.describe('HU-25 · elegir qué hacer con las partidas editadas', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let api: APIRequestContext;
  let accountId: number;

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
    accountId = (await account.json()).id;
    for (const name of ['Resumen Visa', 'Resumen Master', 'Gas', 'Agua']) {
      const item = await api.post('/api/budget-items', {
        data: {
          name,
          kind: 'EXPENSE',
          defaultAccountId: accountId,
          periodicity: 'MONTHLY',
          dueDay: 10,
          dueMonthOffset: 0,
          startPeriod: periodOf(today),
          estimationRule: 'LAST_VALUE',
          currentAmount: 200000,
        },
      });
      expect(item.status(), `alta de ${name}`).toBe(201);
    }
  });

  test.afterAll(async () => {
    await api.dispose();
    await page.close();
  });

  const dialog = () => page.getByRole('dialog');
  const row = (name: string) =>
    page
      .getByRole('region', { name: 'Gastos', exact: true })
      .locator('tbody tr')
      .filter({ has: page.getByRole('rowheader', { name, exact: true }) });

  async function entryId(name: string, offset: number): Promise<number> {
    const view = await (await api.get(`/api/periods/${periodOf(monthFrom(offset))}`)).json();
    return (view.expenses as { name: string; id: number }[]).find((e) => e.name === name)!.id;
  }

  /** Edita el presupuestado de la partida del mes (la marca como editada, D-31). */
  async function edit(name: string, offset: number, amount: number) {
    const response = await api.patch(`/api/entries/${await entryId(name, offset)}`, { data: { budgetedAmount: amount } });
    expect(response.status(), `edición de ${name}`).toBe(200);
  }

  async function payThisMonth(name: string, amount: number) {
    const response = await api.post(`/api/entries/${await entryId(name, 0)}/movements`, {
      data: { date: isoDate(today), amount, accountId },
    });
    expect(response.status(), `pago de ${name}`).toBe(201);
  }

  async function openDialog(name: string) {
    await page.goto('/presupuesto');
    await row(name).getByRole('button', { name: `Consolidar ${name}` }).click();
    await expect(dialog().getByTestId('actual')).toBeVisible();
  }

  /** Lo que muestra la vista de un mes de la partida: su fila completa, con la marca «Editada» si la tiene. */
  async function monthRow(name: string, offset: number) {
    await page.goto(`/presupuesto/${periodOf(monthFrom(offset))}`);
    await expect(row(name)).toBeVisible();
    return row(name);
  }

  test('Respetar: el mes editado conserva su monto y su marca, y el de dos meses adelante toma el nuevo', async () => {
    await edit('Resumen Visa', 1, 300000);
    await payThisMonth('Resumen Visa', 210000);

    await openDialog('Resumen Visa');

    // La elección aparece con la editada (mes, monto actual y nuevo si se pisa) y ninguna opción elegida.
    await expect(dialog().getByTestId('policy-required')).toContainText('Hay 1 partida editada a mano');
    await expect(dialog().getByTestId('edited-target')).toHaveCount(1);
    const edited = dialog().getByTestId('edited-target');
    await expect(edited).toContainText(periodText(monthFrom(1)));
    await expect(edited).toContainText('$ 300.000,00');
    await expect(edited).toContainText('$ 210.000,00');
    await expect(dialog().getByTestId('policy-keep').getByRole('radio')).not.toBeChecked();
    await expect(dialog().getByTestId('policy-overwrite').getByRole('radio')).not.toBeChecked();
    await expect(dialog().getByTestId('policy-keep-text')).toContainText('conserva su monto y sigue editada');
    await expect(dialog().getByTestId('policy-overwrite-text')).toContainText('deja de estar editada');
    await expect(dialog().getByTestId('confirm-consolidate')).toBeDisabled();

    await dialog().getByTestId('policy-keep').getByRole('radio').check();
    await expect(dialog().getByTestId('confirm-consolidate')).toBeEnabled();
    await dialog().getByTestId('confirm-consolidate').click();
    await expect(dialog()).toBeHidden();
    await expect(page.getByText('«Resumen Visa» consolidada por $ 210.000,00.')).toBeVisible();

    const next = await monthRow('Resumen Visa', 1);
    await expect(next).toContainText('$ 300.000,00');
    await expect(next.getByText('Editada', { exact: true })).toBeVisible();
    const later = await monthRow('Resumen Visa', 2);
    await expect(later).toContainText('$ 210.000,00');
    await expect(later.getByText('Editada', { exact: true })).toHaveCount(0);
  });

  test('Pisar: todas toman el nuevo monto y el mes editado pierde la marca', async () => {
    await edit('Resumen Master', 1, 150000);
    await payThisMonth('Resumen Master', 210000);

    await openDialog('Resumen Master');
    await dialog().getByTestId('policy-overwrite').getByRole('radio').check();
    await dialog().getByTestId('confirm-consolidate').click();
    await expect(dialog()).toBeHidden();

    for (const offset of [1, 2]) {
      const month = await monthRow('Resumen Master', offset);
      await expect(month).toContainText('$ 210.000,00');
      await expect(month.getByText('Editada', { exact: true })).toHaveCount(0);
    }
    // El monto vigente del Concepto es el nuevo en la lista de Conceptos.
    await page.goto('/conceptos');
    await expect(page.getByRole('row').filter({ hasText: 'Resumen Master' })).toContainText('$ 210.000,00');
  });

  test('sin partidas editadas en el destino no aparece la elección', async () => {
    await payThisMonth('Gas', 180000);

    await openDialog('Gas');

    await expect(dialog().getByTestId('policy-choice')).toHaveCount(0);
    await expect(dialog().getByTestId('confirm-consolidate')).toBeEnabled();
    await dialog().getByTestId('confirm-consolidate').click();
    await expect(dialog()).toBeHidden();
    await expect((await monthRow('Gas', 2))).toContainText('$ 180.000,00');
  });

  test('error: si alguien edita un mes después de la vista previa, el diálogo pide elegir y no pierde nada', async () => {
    await payThisMonth('Agua', 90000);
    await openDialog('Agua');
    await expect(dialog().getByTestId('policy-choice')).toHaveCount(0);

    // Otra pestaña (la API) edita el mes siguiente mientras el diálogo sigue abierto.
    await edit('Agua', 1, 250000);
    await dialog().getByTestId('confirm-consolidate').click();

    await expect(dialog().getByTestId('consolidate-error')).toBeVisible();
    await expect(dialog().getByTestId('policy-choice')).toBeVisible();
    await expect(dialog().getByTestId('confirm-consolidate')).toBeDisabled();
    // Nada cambió: la partida sigue sin consolidar.
    const view = await (await api.get(`/api/periods/${periodOf(today)}`)).json();
    expect(view.expenses.find((e: { name: string }) => e.name === 'Agua').status).not.toBe('CONSOLIDATED');

    await dialog().getByTestId('policy-keep').getByRole('radio').check();
    await dialog().getByTestId('confirm-consolidate').click();
    await expect(dialog()).toBeHidden();
    const next = await monthRow('Agua', 1);
    await expect(next).toContainText('$ 250.000,00');
    await expect(next.getByText('Editada', { exact: true })).toBeVisible();
  });
});

/**
 * HU-22 (pago rápido): «Pagar todo» registra el movimiento por el pendiente y consolida en un solo paso. Un usuario
 * propio y cuatro Conceptos de gasto mensuales con último valor desde el mes actual, uno por caso. Todas las fechas
 * salen de hoy.
 */
test.describe('HU-22 · pagar todo', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let api: APIRequestContext;
  let accountId: number;

  const AMOUNTS: Record<string, number> = { Expensas: 200000, Alquiler: 120000, Seguro: 80000, Patente: 60000 };

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
    accountId = (await account.json()).id;
    for (const [name, amount] of Object.entries(AMOUNTS)) {
      const item = await api.post('/api/budget-items', {
        data: {
          name,
          kind: 'EXPENSE',
          defaultAccountId: accountId,
          periodicity: 'MONTHLY',
          dueDay: 10,
          dueMonthOffset: 0,
          startPeriod: periodOf(today),
          estimationRule: 'LAST_VALUE',
          currentAmount: amount,
        },
      });
      expect(item.status(), `alta de ${name}`).toBe(201);
    }
  });

  test.afterAll(async () => {
    await api.dispose();
    await page.close();
  });

  const dialog = () => page.getByRole('dialog');
  const row = (name: string) =>
    page
      .getByRole('region', { name: 'Gastos', exact: true })
      .locator('tbody tr')
      .filter({ has: page.getByRole('rowheader', { name, exact: true }) });
  const cells = (name: string) => row(name).locator('th, td');
  const settleButton = (name: string) => row(name).getByRole('button', { name: `Pagar todo de ${name}` });

  async function entryId(name: string, offset: number): Promise<number> {
    const view = await (await api.get(`/api/periods/${periodOf(monthFrom(offset))}`)).json();
    return (view.expenses as { name: string; id: number }[]).find((e) => e.name === name)!.id;
  }

  async function movementsOf(name: string): Promise<{ amount: number; date: string }[]> {
    return (await api.get(`/api/entries/${await entryId(name, 0)}/movements`)).json();
  }

  async function monthRow(name: string, offset: number) {
    await page.goto(`/presupuesto/${periodOf(monthFrom(offset))}`);
    await expect(row(name)).toBeVisible();
    return row(name);
  }

  test('una Estimada: queda Consolidada con el real igual al presupuestado y los meses siguientes no cambian de monto', async () => {
    await page.goto('/presupuesto');
    await expect(row('Expensas')).toBeVisible();
    await expect(cells('Expensas').nth(8)).toHaveText('Estimada');

    await settleButton('Expensas').click();

    await expect(dialog().getByRole('heading', { name: 'Pagar todo · Expensas' })).toBeVisible();
    await expect(dialog().getByTestId('settle-amount')).toContainText('un pago de $ 200.000,00');
    // La fecha que sugiere el backend es hoy y la cuenta es la de la partida.
    await expect(dialog().locator('[formcontrolname="date"]')).toHaveValue(isoDate(today));
    await expect(dialog().getByTestId('window-notice')).toContainText('Se puede fechar hasta 10 días antes');
    // La vista previa muestra la estimación que resultaría, sin cambios en los meses siguientes.
    await expect(dialog().getByTestId('actual')).toHaveText('$ 200.000,00');
    await expect(dialog().getByTestId('estimate')).toHaveText('$ 200.000,00');
    await expect(dialog().getByTestId('no-changes')).toContainText('no cambia ningún otro mes');

    await dialog().getByTestId('confirm-quick-settle').click();

    await expect(dialog()).toBeHidden();
    await expect(cells('Expensas').nth(8)).toHaveText('Consolidada');
    await expect(cells('Expensas').nth(5)).toContainText('$ 200.000,00');
    await expect(cells('Expensas').nth(6)).toHaveText('$ 200.000,00');
    await expect(cells('Expensas').nth(7)).toHaveText('$ 0,00');
    await expect(page.getByText('Pago de $ 200.000,00 registrado. «Expensas» consolidada por $ 200.000,00.')).toBeVisible();
    await expect(row('Expensas').getByRole('button')).toHaveCount(0);

    const movements = await movementsOf('Expensas');
    expect(movements).toHaveLength(1);
    expect(movements[0]).toMatchObject({ amount: 200000, date: isoDate(today) });
    const next = await monthRow('Expensas', 1);
    await expect(next.locator('th, td').nth(5)).toContainText('$ 200.000,00');
    await expect(next.getByText('Editada', { exact: true })).toHaveCount(0);
  });

  test('una Parcial: el movimiento es por lo que faltaba', async () => {
    const id = await entryId('Alquiler', 0);
    const first = await api.post(`/api/entries/${id}/movements`, {
      data: { date: isoDate(today), amount: 50000, accountId },
    });
    expect(first.status()).toBe(201);
    await page.goto('/presupuesto');
    await expect(cells('Alquiler').nth(8)).toHaveText('Parcial');

    await settleButton('Alquiler').click();
    await expect(dialog().getByTestId('settle-amount')).toContainText('un pago de $ 70.000,00');
    await dialog().getByTestId('confirm-quick-settle').click();

    await expect(dialog()).toBeHidden();
    await expect(cells('Alquiler').nth(8)).toHaveText('Consolidada');
    await expect(cells('Alquiler').nth(6)).toHaveText('$ 120.000,00');
    const movements = await movementsOf('Alquiler');
    expect(movements.map((m) => m.amount).sort((a, b) => a - b)).toEqual([50000, 70000]);
  });

  test('con una partida editada en el destino: pide elegir y, con Respetar, la conserva', async () => {
    const edit = await api.patch(`/api/entries/${await entryId('Seguro', 1)}`, { data: { budgetedAmount: 100000 } });
    expect(edit.status()).toBe(200);
    await page.goto('/presupuesto');

    await settleButton('Seguro').click();

    await expect(dialog().getByTestId('policy-required')).toContainText('Hay 1 partida editada a mano');
    await expect(dialog().getByTestId('edited-target')).toContainText(periodText(monthFrom(1)));
    await expect(dialog().getByTestId('policy-keep').getByRole('radio')).not.toBeChecked();
    await expect(dialog().getByTestId('policy-overwrite').getByRole('radio')).not.toBeChecked();
    await expect(dialog().getByTestId('confirm-quick-settle')).toBeDisabled();

    await dialog().getByTestId('policy-keep').getByRole('radio').check();
    await expect(dialog().getByTestId('confirm-quick-settle')).toBeEnabled();
    await dialog().getByTestId('confirm-quick-settle').click();

    await expect(dialog()).toBeHidden();
    await expect(cells('Seguro').nth(8)).toHaveText('Consolidada');
    const next = await monthRow('Seguro', 1);
    await expect(next.locator('th, td').nth(5)).toContainText('$ 100.000,00');
    await expect(next.getByText('Editada', { exact: true })).toBeVisible();
  });

  test('error: una fecha futura se rechaza bajo el campo y no queda ningún movimiento', async () => {
    const tomorrow = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 1);
    await page.goto('/presupuesto');
    await settleButton('Patente').click();
    await expect(dialog().getByTestId('settle-amount')).toContainText('$ 60.000,00');

    await dialog().locator('[formcontrolname="date"]').fill(isoDate(tomorrow));
    await dialog().getByTestId('confirm-quick-settle').click();

    await expect(dialog().getByText('no puede ser posterior a hoy')).toBeVisible();
    // El diálogo sigue abierto con lo cargado, y se puede corregir la fecha.
    await expect(dialog().locator('[formcontrolname="date"]')).toHaveValue(isoDate(tomorrow));
    // Nada se guardó: ni el movimiento ni la consolidación.
    expect(await movementsOf('Patente')).toEqual([]);
    const view = await (await api.get(`/api/periods/${periodOf(today)}`)).json();
    expect(view.expenses.find((e: { name: string }) => e.name === 'Patente').status).toBe('ESTIMATED');

    await dialog().locator('[formcontrolname="date"]').fill(isoDate(today));
    await dialog().getByTestId('confirm-quick-settle').click();
    await expect(dialog()).toBeHidden();
    await expect(cells('Patente').nth(8)).toHaveText('Consolidada');
    expect(await movementsOf('Patente')).toHaveLength(1);
  });
});
