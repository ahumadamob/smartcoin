import { Page, expect, test } from '@playwright/test';
import { API_URL, startPeriod, startSession } from './support';

/** Épica 9 · Saldos y proyección. HU-34 (flujo de caja por fecha). Cada describe usa un usuario propio. */

const opening = `${startPeriod()}-01`;

function iso(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

function isoDay(offsetDays: number): string {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  return iso(date);
}

const today = new Date();
const currentPeriod = iso(today).slice(0, 7);
/** Último día del mes actual: "más adelante en el mes" (si hoy es el último día, es hoy). */
const monthEnd = iso(new Date(today.getFullYear(), today.getMonth() + 1, 0));
const nextMonthStart = iso(new Date(today.getFullYear(), today.getMonth() + 1, 1));
const nextMonthEnd = iso(new Date(today.getFullYear(), today.getMonth() + 2, 0));

test.describe('HU-34 · flujo de caja por fecha', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let token: string;

  const api = async (path: string, init?: { method?: string; body?: unknown }) => {
    const response = await page.request.fetch(`${API_URL}${path}`, {
      method: init?.method ?? 'GET',
      headers: { Authorization: `Bearer ${token}` },
      data: init?.body,
    });
    expect(response.status(), path).toBeLessThan(300);
    return response.json();
  };

  const rows = () => page.getByTestId('row');

  async function filter(from: string, to: string) {
    await page.getByLabel('Desde').fill(from);
    await page.getByLabel('Hasta').fill(to);
    await page.getByRole('button', { name: 'Ver' }).click();
  }

  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    ({ token } = await startSession(page, playwright));

    const ids: Record<string, number> = {};
    for (const [name, currency, initialBalance] of [
      ['Banco', 'ARS', 2000000],
      ['Caja USD', 'USD', 500],
    ] as const) {
      const created = await api('/api/accounts', {
        method: 'POST',
        body: { name, type: 'BANK', currency, initialBalance, openingDate: opening },
      });
      ids[name] = created.id;
    }

    const entry = (name: string, kind: 'INCOME' | 'EXPENSE', dueDate: string, budgetedAmount: number) =>
      api(`/api/periods/${currentPeriod}/entries`, {
        method: 'POST',
        body: { name, kind, accountId: ids['Banco'], dueDate, budgetedAmount },
      });

    // Un cobro y un pago reales de hoy.
    const salary = await entry('Cobro freelance', 'INCOME', isoDay(0), 80000);
    const internet = await entry('Pago de internet', 'EXPENSE', isoDay(0), 30000);
    for (const [created, amount] of [
      [salary, 80000],
      [internet, 30000],
    ] as const) {
      await api(`/api/entries/${created.id}/movements`, {
        method: 'POST',
        body: { date: isoDay(0), amount, accountId: ids['Banco'] },
      });
    }
    // Dos pendientes que vencen más adelante en el mes; con el segundo el acumulado termina en negativo.
    await entry('Alquiler', 'EXPENSE', monthEnd, 150000);
    await entry('Seguro del auto', 'EXPENSE', monthEnd, 1500000);
    // Una compra de dólares de hoy: 600.000,00 ARS por 500,00 USD.
    await api('/api/transfers', {
      method: 'POST',
      body: {
        sourceAccountId: ids['Banco'],
        targetAccountId: ids['Caja USD'],
        date: isoDay(0),
        sourceAmount: 600000,
        targetAmount: 500,
      },
    });

    await page.goto('/flujo-de-caja');
  });

  test.afterAll(async () => {
    await page.close();
  });

  test('sin fechas muestra el mes actual en pesos, con el saldo de arranque arriba y el rango que resolvió el backend', async () => {
    await expect(page.getByRole('heading', { level: 1, name: 'Flujo de caja' })).toBeVisible();
    await expect(page.getByLabel('Desde')).toHaveValue(`${currentPeriod}-01`);
    await expect(page.getByLabel('Hasta')).toHaveValue(monthEnd);
    await expect(page.getByTestId('opening-balance')).toHaveText('$ 2.000.000,00');
    await expect(page.getByTestId('opening')).toContainText('Saldo de arranque al');
    await expect(page.getByTestId('opening')).not.toContainText('proyectado');
  });

  test('lista lo real antes que lo previsto, con el signo y el saldo acumulado de cada fila', async () => {
    await expect(rows()).toHaveCount(5);
    const description = (index: number) => rows().nth(index).getByTestId('description');

    // Hoy: lo real primero (ingresos antes que gastos) y después lo previsto de ese día, si hay.
    await expect(description(0)).toContainText('Cobro freelance');
    await expect(rows().nth(0).getByTestId('amount')).toContainText('80.000,00');
    await expect(rows().nth(0).getByTestId('balance')).toHaveText('$ 2.080.000,00');

    await expect(description(1)).toContainText('Compra de dólares: Banco → Caja USD');
    await expect(rows().nth(1).getByTestId('amount')).toContainText('-');
    await expect(rows().nth(1).getByTestId('amount')).toContainText('600.000,00');
    await expect(rows().nth(1).getByTestId('balance')).toHaveText('$ 1.480.000,00');

    await expect(description(2)).toContainText('Pago de internet');
    await expect(rows().nth(2).getByTestId('balance')).toHaveText('$ 1.450.000,00');

    await expect(description(3)).toContainText('Alquiler');
    await expect(rows().nth(3).getByTestId('balance')).toHaveText('$ 1.300.000,00');
    await expect(rows().nth(3).getByTestId('date')).toHaveText(new RegExp(`^${monthEnd.slice(8)}/${monthEnd.slice(5, 7)}/`));
  });

  test('lo real y lo previsto se distinguen por el texto, no solo por el color', async () => {
    for (const index of [0, 1, 2]) {
      await expect(rows().nth(index)).toHaveAttribute('data-status', 'REAL');
      await expect(rows().nth(index).getByTestId('status')).toContainText('Real');
    }
    for (const index of [3, 4]) {
      await expect(rows().nth(index)).toHaveAttribute('data-status', 'PLANNED');
      await expect(rows().nth(index).getByTestId('status')).toContainText('Prevista');
    }
  });

  test('un pago ya hecho por completo no vuelve a aparecer como pendiente', async () => {
    await expect(page.getByTestId('description').filter({ hasText: 'Pago de internet' })).toHaveCount(1);
    await expect(page.getByTestId('description').filter({ hasText: 'Cobro freelance' })).toHaveCount(1);
  });

  test('el acumulado negativo se marca con el texto «Faltante» y el saldo final es el de la última fila', async () => {
    await expect(rows().nth(4).getByTestId('balance')).toContainText('200.000,00');
    await expect(rows().nth(4).getByTestId('balance')).toContainText('Faltante');
    for (const index of [0, 1, 2, 3]) {
      await expect(rows().nth(index).getByTestId('balance')).not.toContainText('Faltante');
    }
    await expect(page.getByTestId('closing')).toContainText('200.000,00');
    await expect(page.getByTestId('closing')).toContainText('Faltante');
  });

  test('el acumulado antes de los pendientes coincide con el saldo de /cuentas, y el final suma los pendientes', async () => {
    await page.goto('/cuentas');
    const ars = page.getByRole('region', { name: '$ (ARS)' });
    await expect(ars.getByTestId('subtotal')).toContainText('1.450.000,00');
    await expect(page.getByRole('region', { name: 'US$ (USD)' }).getByTestId('subtotal')).toContainText('1.000,00');

    // $ 1.450.000,00 menos 150.000,00 y 1.500.000,00 de lo que se espera pagar: -$ 200.000,00.
    await page.goto('/flujo-de-caja');
    await expect(page.getByTestId('closing')).toContainText('200.000,00');
  });

  test('en dólares muestra la compra con signo positivo y su acumulado, sin mezclar pesos', async () => {
    await page.getByRole('combobox', { name: 'Moneda' }).click();
    await page.getByRole('option', { name: 'US$ (USD)' }).click();

    await expect(page.getByTestId('opening-balance')).toHaveText('US$ 500,00');
    await expect(rows()).toHaveCount(1);
    await expect(rows().first().getByTestId('description')).toContainText('Compra de dólares');
    await expect(rows().first().getByTestId('amount')).toHaveText('US$ 500,00');
    await expect(rows().first().getByTestId('balance')).toHaveText('US$ 1.000,00');
    await expect(page.getByTestId('closing')).toContainText('US$ 1.000,00');
    await expect(page.locator('app-cash-flow-table')).not.toContainText('Alquiler');
  });

  test('un mes futuro arranca con un saldo proyectado que ya descuenta lo que vence antes', async () => {
    await page.getByRole('combobox', { name: 'Moneda' }).click();
    await page.getByRole('option', { name: '$ (ARS)' }).click();
    await filter(nextMonthStart, nextMonthEnd);

    await expect(page.getByTestId('opening')).toContainText('Saldo de arranque proyectado');
    await expect(page.getByTestId('projected-hint')).toBeVisible();
    await expect(page.getByTestId('opening-balance')).toContainText('200.000,00');
    await expect(page.getByTestId('opening')).toContainText('Faltante');
    await expect(page.getByText('No hay movimientos ni pendientes en este rango.')).toBeVisible();
  });

  test('un rango invertido muestra el error del backend y no deja el flujo anterior a la vista', async () => {
    await filter(isoDay(5), isoDay(1));

    await expect(page.getByRole('alert')).toContainText('La fecha desde no puede ser posterior a la fecha hasta.');
    await expect(page.locator('app-cash-flow-table')).toHaveCount(0);
  });

  test('un rango de más de un año también se rechaza', async () => {
    await filter(isoDay(0), isoDay(400));

    await expect(page.getByRole('alert')).toContainText('más de 366 días');
  });

  test('se puede usar solo con el teclado: Enter en un campo pide el flujo de nuevo', async () => {
    await page.getByLabel('Desde').fill(`${currentPeriod}-01`);
    await page.getByLabel('Hasta').fill(monthEnd);
    await page.getByLabel('Hasta').press('Enter');

    await expect(rows()).toHaveCount(5);
    await expect(page.getByRole('alert')).toHaveCount(0);

    await page.getByRole('button', { name: 'Ver' }).focus();
    await expect(page.getByRole('button', { name: 'Ver' })).toBeFocused();
  });
});
