import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { API_URL, startPeriod, startSession } from './support';

/**
 * Épica 6 · Consolidación. HU-23 (consolidar una partida). Un usuario propio: se consolida siempre con un usuario de
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
