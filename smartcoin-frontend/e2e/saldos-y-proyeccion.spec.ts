import { Page, expect, test } from '@playwright/test';
import { API_URL, startPeriod, startSession } from './support';

/** Épica 9 · Saldos y proyección. HU-34 (flujo de caja por fecha), HU-35 (proyección de saldos) y HU-36 (vista de varios meses). Cada describe usa un usuario propio. */

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

test.describe('HU-36 · vista de varios meses', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let token: string;
  let sueldoNext: number;

  const api = async (path: string, init?: { method?: string; body?: unknown }, expected = 300) => {
    const response = await page.request.fetch(`${API_URL}${path}`, {
      method: init?.method ?? 'GET',
      headers: { Authorization: `Bearer ${token}` },
      data: init?.body,
    });
    if (expected === 300) {
      expect(response.status(), path).toBeLessThan(300);
      return response.json();
    }
    expect(response.status(), path).toBe(expected);
    return response.json();
  };

  /** El período `offset` meses después del actual (YYYY-MM). */
  const period = (offset: number) => iso(new Date(today.getFullYear(), today.getMonth() + offset, 1)).slice(0, 7);
  const cell = (row: number, column: number) =>
    page.locator(`[data-testid="cell"][data-row="${row}"][data-column="${column}"]`);
  const pesos = (amount: number) => `${amount < 0 ? '-' : ''}$ ${Math.abs(amount).toLocaleString('es-AR', { minimumFractionDigits: 2 })}`;
  const dollars = (amount: number) => `${amount < 0 ? '-' : ''}US$ ${Math.abs(amount).toLocaleString('es-AR', { minimumFractionDigits: 2 })}`;
  const totalRow = (index: number) => page.getByTestId('total-row').nth(index);

  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    ({ token } = await startSession(page, playwright));

    const ids: Record<string, number> = {};
    for (const [name, currency, initialBalance] of [
      ['Banco', 'ARS', 3000000],
      ['Caja USD', 'USD', 1000],
    ] as const) {
      const created = await api('/api/accounts', {
        method: 'POST',
        body: { name, type: 'BANK', currency, initialBalance, openingDate: opening },
      });
      ids[name] = created.id;
    }

    // Un Concepto mensual, uno bimestral y uno en dólares, todos desde el mes actual.
    for (const [name, kind, account, periodicity, dueDay, amount] of [
      ['Sueldo', 'INCOME', 'Banco', 'MONTHLY', 25, 1000000],
      ['Seguro', 'EXPENSE', 'Banco', 'BIMONTHLY', 10, 60000],
      ['Hosting', 'EXPENSE', 'Caja USD', 'MONTHLY', 5, 20],
    ] as const) {
      await api('/api/budget-items', {
        method: 'POST',
        body: {
          name,
          kind,
          defaultAccountId: ids[account],
          periodicity,
          dueDay,
          dueMonthOffset: 0,
          startPeriod: currentPeriod,
          estimationRule: 'LAST_VALUE',
          currentAmount: amount,
        },
      });
    }

    // Consolidar el sueldo del mes actual (cobrado por completo) y editar el del mes siguiente.
    const current = await api(`/api/periods/${currentPeriod}`);
    const sueldoNow = current.incomes.find((e: { name: string }) => e.name === 'Sueldo').id;
    await api(`/api/entries/${sueldoNow}/movements`, {
      method: 'POST',
      body: { date: isoDay(0), amount: 1000000, accountId: ids['Banco'] },
    });
    await api(`/api/entries/${sueldoNow}/consolidate`, { method: 'POST', body: {} });
    const next = await api(`/api/periods/${period(1)}`);
    sueldoNext = next.incomes.find((e: { name: string }) => e.name === 'Sueldo').id;
    await api(`/api/entries/${sueldoNext}`, { method: 'PATCH', body: { budgetedAmount: 1200000 } });

    await page.goto('/planificacion');
  });

  test.afterAll(async () => {
    await page.close();
  });

  test('sin elegir muestra 12 meses desde el actual y las filas por tipo', async () => {
    await expect(page.getByRole('heading', { level: 1, name: 'Varios meses' })).toBeVisible();
    await expect(page.getByLabel('Desde')).toHaveValue(currentPeriod);
    await expect(page.getByLabel('Hasta')).toHaveValue(period(11));
    await expect(page.getByTestId('month-head')).toHaveCount(12);
    await expect(page.getByTestId('month-head').first()).toContainText('Mes actual');
    await expect(page.getByTestId('section')).toHaveText([/Ingresos/, /Gastos/]);
    await expect(page.getByTestId('row-name')).toHaveText([/Sueldo/, /Hosting/, /Seguro/]);
  });

  test('los estimados con sus marcas: consolidada, editada, bimestral con celdas vacías y dólares', async () => {
    await expect(cell(0, 0)).toContainText(pesos(1000000));
    await expect(cell(0, 0).getByTestId('mark-consolidated')).toHaveText('Consolidada');
    await expect(cell(0, 1)).toContainText(pesos(1200000));
    await expect(cell(0, 1).getByTestId('mark-edited')).toHaveText('Editada');
    await expect(cell(0, 2)).toContainText(pesos(1000000));
    await expect(cell(0, 2).locator('.mark')).toHaveCount(0);

    await expect(cell(1, 0)).toContainText(dollars(20));
    await expect(cell(1, 5)).toContainText(dollars(20));

    await expect(cell(2, 0)).toContainText(pesos(60000));
    await expect(cell(2, 1)).toContainText('Sin partida');
    await expect(cell(2, 1)).not.toContainText('0,00');
    await expect(cell(2, 2)).toContainText(pesos(60000));
  });

  test('los totales por mes y moneda están separados y coinciden con la vista de cada mes', async () => {
    // Pesos: ingresos, gastos y resultado; dólares: lo mismo, sin sumarse a los pesos.
    await expect(totalRow(0).getByTestId('total-income').nth(0)).toHaveText(pesos(1000000));
    await expect(totalRow(1).getByTestId('total-expense').nth(0)).toHaveText(pesos(60000));
    await expect(totalRow(2).getByTestId('total-result').nth(0)).toHaveText(pesos(940000));
    await expect(totalRow(1).getByTestId('total-expense').nth(1)).toHaveText(pesos(0));
    await expect(totalRow(2).getByTestId('total-result').nth(1)).toHaveText(pesos(1200000));
    await expect(totalRow(5).getByTestId('total-result').nth(0)).toHaveText(dollars(-20));

    // Tres meses distintos: cada celda y cada total son los de la vista del mes.
    const grid = await api(`/api/budget-grid?from=${currentPeriod}&to=${period(11)}`);
    for (const column of [0, 1, 2]) {
      const view = await api(`/api/periods/${period(column)}`);
      const entries = [...view.incomes, ...view.expenses];
      for (const row of grid.rows) {
        const found = entries.find((e: { budgetItemId: number }) => e.budgetItemId === row.budgetItemId);
        expect(row.cells[column]?.forecast, `${row.name} ${period(column)}`).toBe(found?.forecastAmount);
        expect(row.cells[column]?.entryId).toBe(found?.id);
      }
      for (const total of grid.totals[column].currencies) {
        const expected = view.totals.find((t: { currency: string }) => t.currency === total.currency);
        expect(total.incomeForecast).toBe(expected.income.forecastAmount);
        expect(total.expenseForecast).toBe(expected.expense.forecastAmount);
        expect(total.result).toBe(expected.result);
      }
    }
  });

  test('con el teclado: las celdas se recorren con flechas, tienen foco visible y Enter abre el mes', async () => {
    await cell(0, 0).focus();
    await expect(cell(0, 0)).toBeFocused();
    await expect(cell(0, 0)).toHaveCSS('outline-style', 'solid');

    await page.keyboard.press('ArrowRight');
    await expect(cell(0, 1)).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(cell(1, 1)).toBeFocused();
    await page.keyboard.press('ArrowUp');
    await page.keyboard.press('Enter');

    await expect(page).toHaveURL(new RegExp(`/presupuesto/${period(1)}$`));
    await expect(page.getByRole('heading', { level: 1, name: /Presupuesto de/ })).toBeVisible();
    // La partida editada, en la vista de ese mes.
    await expect(page.getByRole('row', { name: /Sueldo/ })).toContainText('1.200.000');
  });

  test('un clic en una celda abre ese mes', async () => {
    await page.goto('/planificacion');
    await cell(2, 2).click();

    await expect(page).toHaveURL(new RegExp(`/presupuesto/${period(2)}$`));
    await expect(page.getByRole('row', { name: /Seguro/ })).toContainText('60.000');
  });

  test('error: un rango invertido o de más de 24 meses muestra el error del backend y no deja la grilla anterior', async () => {
    await page.goto('/planificacion');
    await expect(page.getByTestId('month-head')).toHaveCount(12);

    await page.getByLabel('Desde').fill(period(5));
    await page.getByLabel('Hasta').fill(period(2));
    await page.getByRole('button', { name: 'Ver' }).click();
    await expect(page.getByRole('alert')).toContainText('no puede ser posterior');
    await expect(page.getByTestId('month-head')).toHaveCount(0);

    await page.getByLabel('Desde').fill(period(0));
    await page.getByLabel('Hasta').fill(period(24));
    await page.getByRole('button', { name: 'Ver' }).click();
    await expect(page.getByRole('alert')).toContainText('24 meses');

    // Un rango válido vuelve a mostrar la grilla.
    await page.getByLabel('Hasta').fill(period(3));
    await page.getByRole('button', { name: 'Ver' }).click();
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(page.getByTestId('month-head')).toHaveCount(4);
  });

  test('por la API: rango fuera de los períodos, invertido o demasiado largo es 400 en su campo', async () => {
    const before = await api(`/api/budget-grid?from=2000-01&to=2000-03`, undefined, 400);
    expect(before.code).toBe('VALIDATION_ERROR');
    expect(before.errors[0].field).toBe('from');

    const inverted = await api(`/api/budget-grid?from=${period(5)}&to=${period(2)}`, undefined, 400);
    expect(inverted.errors[0].field).toBe('from');

    const beyond = await api(`/api/budget-grid?from=${period(0)}&to=2099-01`, undefined, 400);
    expect(beyond.errors[0].field).toBe('to');

    const tooLong = await api(`/api/budget-grid?from=${startPeriod()}&to=${period(24)}`, undefined, 400);
    expect(tooLong.errors[0].field).toBe('to');

    const malformed = await api(`/api/budget-grid?from=ayer`, undefined, 400);
    expect(malformed.code).toBe('VALIDATION_ERROR');
  });
});
