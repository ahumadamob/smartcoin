import { Page, expect, test } from '@playwright/test';
import { API_URL, startPeriod, startSession } from './support';

/** Épica 9 · Saldos y proyección. HU-34 (flujo de caja por fecha) y HU-35 (proyección de saldos). Cada describe usa un usuario propio. */

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

test.describe('HU-35 · proyección de saldos por cuenta', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let token: string;
  let rentEntryId: number;
  let walletId: number;

  const api = async (path: string, init?: { method?: string; body?: unknown }) => {
    const response = await page.request.fetch(`${API_URL}${path}`, {
      method: init?.method ?? 'GET',
      headers: { Authorization: `Bearer ${token}` },
      data: init?.body,
    });
    expect(response.status(), path).toBeLessThan(300);
    return response.json();
  };

  const monthRows = () => page.getByTestId('month-row');
  /** Una celda de una fila: las columnas son Banco, Billetera, Subtotal $, Caja USD, Subtotal US$. */
  const cell = (row: number, testId: 'balance' | 'subtotal', index: number) =>
    monthRows().nth(row).getByTestId(testId).nth(index);

  /** Moneda como la muestra la pantalla: $ 1.234,50, -$ 1.234,50 o US$ 1.234,50. */
  const pesos = (amount: number) => `${amount < 0 ? '-' : ''}$ ${Math.abs(amount).toLocaleString('es-AR', { minimumFractionDigits: 2 })}`;
  const dollars = (amount: number) => `${amount < 0 ? '-' : ''}US$ ${Math.abs(amount).toLocaleString('es-AR', { minimumFractionDigits: 2 })}`;

  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    ({ token } = await startSession(page, playwright));

    const ids: Record<string, number> = {};
    for (const [name, currency, initialBalance] of [
      ['Banco', 'ARS', 1000000],
      ['Billetera', 'ARS', 200000],
      ['Caja USD', 'USD', 1000],
    ] as const) {
      const created = await api('/api/accounts', {
        method: 'POST',
        body: { name, type: 'BANK', currency, initialBalance, openingDate: opening },
      });
      ids[name] = created.id;
    }
    walletId = ids['Billetera'];

    // Tres Conceptos mensuales desde el mes actual: un ingreso y un gasto en pesos y un gasto en dólares. El día 28
    // cae en el mes actual o, si ya pasó, la partida está vencida y cuenta igual desde este mes.
    for (const [name, kind, account, amount] of [
      ['Sueldo', 'INCOME', 'Banco', 500000],
      ['Alquiler', 'EXPENSE', 'Billetera', 200000],
      ['Suscripción en dólares', 'EXPENSE', 'Caja USD', 100],
    ] as const) {
      await api('/api/budget-items', {
        method: 'POST',
        body: {
          name,
          kind,
          defaultAccountId: ids[account],
          periodicity: 'MONTHLY',
          dueDay: 28,
          dueMonthOffset: 0,
          startPeriod: currentPeriod,
          estimationRule: 'LAST_VALUE',
          currentAmount: amount,
        },
      });
    }
    const view = await api(`/api/periods/${currentPeriod}`);
    rentEntryId = view.expenses.find((e: { name: string }) => e.name === 'Alquiler').id;

    await page.goto('/proyeccion');
  });

  test.afterAll(async () => {
    await page.close();
  });

  test('muestra 25 meses desde el actual, con las cuentas agrupadas por moneda y la línea de supuestos', async () => {
    await expect(page.getByRole('heading', { level: 1, name: 'Proyección' })).toBeVisible();
    await expect(monthRows()).toHaveCount(25);
    await expect(page.getByTestId('group')).toHaveText(['Pesos ($)', 'Dólares (US$)']);
    await expect(page.getByTestId('account-head')).toHaveText(['Banco', 'Billetera', 'Caja USD']);
    await expect(page.getByTestId('subtotal-head')).toHaveText(['Subtotal $', 'Subtotal US$']);
    await expect(page.getByTestId('assumption')).toContainText('cada pendiente se cobra o paga en su cuenta prevista');
    await expect(page.getByTestId('assumption')).toContainText('No incluye transferencias futuras');
    await expect(page.getByLabel('Hasta')).toHaveValue(/^\d{4}-\d{2}$/);
  });

  test('crece o decrece mes a mes según los montos, con subtotales separados por moneda', async () => {
    // Mes k (0 = actual): Banco 1.000.000 + 500.000·(k+1); Billetera 200.000 − 200.000·(k+1); Caja USD 1.000 − 100·(k+1).
    for (const k of [0, 1, 2, 24]) {
      const bank = 1000000 + 500000 * (k + 1);
      const wallet = 200000 - 200000 * (k + 1);
      const usd = 1000 - 100 * (k + 1);
      await expect(cell(k, 'balance', 0), `Banco, mes ${k}`).toContainText(pesos(bank));
      await expect(cell(k, 'balance', 1), `Billetera, mes ${k}`).toContainText(pesos(wallet));
      await expect(cell(k, 'subtotal', 0), `Subtotal $, mes ${k}`).toContainText(pesos(bank + wallet));
      await expect(cell(k, 'balance', 2), `Caja USD, mes ${k}`).toContainText(dollars(usd));
      await expect(cell(k, 'subtotal', 1), `Subtotal US$, mes ${k}`).toContainText(dollars(usd));
    }
  });

  test('una cuenta con saldo proyectado negativo lleva el texto «Faltante», y el subtotal solo cuando lo es', async () => {
    await expect(cell(0, 'balance', 1)).not.toContainText('Faltante');
    await expect(cell(1, 'balance', 1)).toContainText('Faltante');
    await expect(cell(1, 'subtotal', 0)).not.toContainText('Faltante');
    // El último mes: la Billetera y la Caja USD faltan; el Banco y el subtotal en pesos no.
    await expect(cell(24, 'balance', 2)).toContainText('Faltante');
    await expect(cell(24, 'subtotal', 1)).toContainText('Faltante');
    await expect(cell(24, 'subtotal', 0)).not.toContainText('Faltante');
  });

  test('el último mes es el saldo actual más todos los pendientes hasta el horizonte, y coincide con la API', async () => {
    const projection = await api('/api/projection');
    expect(projection.months).toHaveLength(25);
    expect(projection.months[0].month).toBe(currentPeriod);
    expect(projection.until).toBe(projection.months[24].month);
    const last = projection.months[24];
    expect(last.balances.map((b: { balance: number }) => b.balance)).toEqual([13500000, -4800000, -1500]);
    expect(last.subtotals).toEqual([
      { currency: 'ARS', balance: 8700000 },
      { currency: 'USD', balance: -1500 },
    ]);
  });

  test('con el teclado: la región se enfoca, se recorre con las flechas y muestra el foco', async () => {
    const region = page.getByRole('region', { name: 'Saldos proyectados por mes' });
    await region.focus();
    await expect(region).toBeFocused();
    await expect(region).toHaveCSS('outline-style', 'solid');
    await page.keyboard.press('End');
    await page.keyboard.press('PageDown');
    // Con la tabla recorrida hasta abajo, el encabezado sigue a la vista.
    await expect(page.getByTestId('account-head').first()).toBeInViewport();
    await expect(page.getByRole('columnheader', { name: 'Mes' })).toBeInViewport();
  });

  test('registrar un pago no cambia el total del mes actual: pasa de previsto a real', async () => {
    const before = await api('/api/projection');
    const totalBefore = before.months[0].subtotals;

    await api(`/api/entries/${rentEntryId}/movements`, {
      method: 'POST',
      body: { date: isoDay(0), amount: 200000, accountId: walletId },
    });
    await page.getByRole('button', { name: 'Ver' }).click();
    await expect(page.getByRole('status')).toHaveCount(0);

    // El pago bajó el saldo actual de la Billetera y sacó el pendiente: el total del mes actual es el mismo.
    const after = await api('/api/projection');
    expect(after.months[0].subtotals).toEqual(totalBefore);
    expect(after.months[0].balances).toEqual(before.months[0].balances);
    await expect(cell(0, 'subtotal', 0)).toContainText(pesos(1000000 + 500000 + 0));
    // Los meses siguientes también conservan el total: el pago real reemplaza al previsto de este mes.
    expect(after.months[1].subtotals).toEqual(before.months[1].subtotals);
  });

  test('un «Hasta» fuera de rango muestra el error del backend y no deja la tabla anterior', async () => {
    await page.getByLabel('Hasta').fill('2099-01');
    await page.getByRole('button', { name: 'Ver' }).click();

    await expect(page.getByRole('alert')).toContainText('horizonte');
    await expect(monthRows()).toHaveCount(0);

    // Un mes anterior al actual también se rechaza.
    await page.getByLabel('Hasta').fill('2000-01');
    await page.getByRole('button', { name: 'Ver' }).click();
    await expect(page.getByRole('alert')).toContainText('mes actual');
  });

  test('un «Hasta» válido limita los meses', async () => {
    await page.getByLabel('Hasta').fill(currentPeriod);
    await page.getByRole('button', { name: 'Ver' }).click();

    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(monthRows()).toHaveCount(1);
  });
});
