import { Page, expect, test } from '@playwright/test';
import { API_URL, startPeriod, startSession } from './support';

/** Épica 7 · Transferencias. HU-27 (misma moneda) y HU-28 (compra y venta de dólares). Cada describe usa un usuario propio. */

const opening = `${startPeriod()}-01`;

function isoDay(offsetDays: number): string {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

async function chooseOption(page: Page, label: string, option: string) {
  await page.getByRole('combobox', { name: label }).click();
  await page.getByRole('option', { name: option, exact: true }).click();
}

test.describe('HU-27 · transferir entre cuentas de la misma moneda', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let token: string;
  let monthBefore: string;

  const api = async (path: string, init?: { method?: string; body?: unknown }) => {
    const response = await page.request.fetch(`${API_URL}${path}`, {
      method: init?.method ?? 'GET',
      headers: { Authorization: `Bearer ${token}` },
      data: init?.body,
    });
    expect(response.status(), path).toBeLessThan(300);
    return response.json();
  };

  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    ({ token } = await startSession(page, playwright));
    const accounts: [string, string, number][] = [
      ['Galicia', 'ARS', 100000],
      ['Caja chica', 'ARS', 20000],
      ['Caja en dólares', 'USD', 500],
    ];
    for (const [name, currency, initialBalance] of accounts) {
      await api('/api/accounts', {
        method: 'POST',
        body: { name, type: 'BANK', currency, initialBalance, openingDate: opening },
      });
    }
    monthBefore = JSON.stringify(await api('/api/periods/current'));
    await page.goto('/transferencias');
  });

  test.afterAll(async () => {
    await page.close();
  });

  test('sin transferencias lo avisa y el formulario arranca con la fecha de hoy', async () => {
    await expect(page.getByRole('heading', { level: 1, name: 'Transferencias' })).toBeVisible();
    await expect(page.getByText('No hay transferencias en ese rango.')).toBeVisible();

    await page.getByRole('button', { name: 'Nueva transferencia' }).click();

    await expect(page.getByLabel('Fecha', { exact: true })).toHaveValue(isoDay(0));
  });

  test('ofrece como destino las cuentas distintas del origen y, entre las de la misma moneda, pide un solo monto', async () => {
    await chooseOption(page, 'Cuenta de origen', 'Galicia (ARS)');
    await page.getByRole('combobox', { name: 'Cuenta de destino' }).click();

    await expect(page.getByRole('option')).toHaveText(['Caja chica (ARS)', 'Caja en dólares (USD)']);
    await page.getByRole('option', { name: 'Caja chica (ARS)', exact: true }).click();
    await expect(page.getByLabel('Monto', { exact: true })).toBeVisible();
    await expect(page.getByLabel('Monto en dólares')).toHaveCount(0);
  });

  test('el ejemplo de la historia: extraer 50.000,00 de Galicia a Caja chica', async () => {
    await page.getByLabel('Monto', { exact: true }).fill('50.000,00');
    await page.getByLabel('Nota (opcional)').fill('Extracción para la semana');
    await page.getByRole('button', { name: 'Transferir' }).click();

    await expect(page.getByText('Transferencia registrada.')).toBeVisible();
    const item = page.getByTestId('transfer').first();
    await expect(item).toContainText('Galicia → Caja chica');
    await expect(item).toContainText('$ 50.000,00');
    await expect(item).toContainText('Extracción para la semana');
  });

  test('en /cuentas resta en el origen, suma en el destino y el subtotal en ARS queda igual', async () => {
    await page.goto('/cuentas');
    const row = (name: string) => page.getByRole('listitem').filter({ hasText: name });

    await expect(row('Galicia').getByTestId('balance')).toHaveText('$ 50.000,00');
    await expect(row('Caja chica').getByTestId('balance')).toHaveText('$ 70.000,00');
    await expect(page.getByRole('region', { name: '$ (ARS)' }).getByTestId('subtotal')).toContainText(
      '$ 120.000,00',
    );
    await expect(row('Caja en dólares').getByTestId('balance')).toHaveText('US$ 500,00');
  });

  test('la vista del mes no cambió: una transferencia no es ingreso ni gasto', async () => {
    expect(JSON.stringify(await api('/api/periods/current'))).toBe(monthBefore);
  });

  test('una fecha futura se rechaza con el mensaje del backend y no se pierde lo cargado', async () => {
    await page.goto('/transferencias');
    await page.getByRole('button', { name: 'Nueva transferencia' }).click();
    await chooseOption(page, 'Cuenta de origen', 'Caja chica (ARS)');
    await chooseOption(page, 'Cuenta de destino', 'Galicia (ARS)');
    await page.getByLabel('Fecha', { exact: true }).fill(isoDay(1));
    await page.getByLabel('Monto', { exact: true }).fill('1.000,50');
    await page.getByLabel('Nota (opcional)').fill('Mañana');
    await page.getByRole('button', { name: 'Transferir' }).click();

    await expect(page.getByRole('alert')).toContainText('posterior a hoy');
    await expect(page.getByLabel('Monto', { exact: true })).toHaveValue('1.000,50');
    await expect(page.getByLabel('Nota (opcional)')).toHaveValue('Mañana');
    await expect(page.getByLabel('Fecha', { exact: true })).toHaveValue(isoDay(1));

    // No se guardó nada: los saldos siguen igual.
    const list = await api('/api/accounts');
    const balance = (name: string) =>
      list.accounts.find((a: { name: string }) => a.name === name).currentBalance as number;
    expect(balance('Galicia')).toBe(50000);
    expect(balance('Caja chica')).toBe(70000);
  });

  test('con el teclado: se llega al formulario y los campos muestran el foco', async () => {
    await page.getByLabel('Monto', { exact: true }).focus();
    await expect(page.getByLabel('Monto', { exact: true })).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(page.getByLabel('Fecha', { exact: true })).toBeFocused();
  });
});

test.describe('HU-28 · comprar y vender dólares', () => {
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

  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    ({ token } = await startSession(page, playwright));
    const accounts: [string, string, number][] = [
      ['Banco en pesos', 'ARS', 2000000],
      ['Caja en dólares', 'USD', 500],
    ];
    for (const [name, currency, initialBalance] of accounts) {
      await api('/api/accounts', {
        method: 'POST',
        body: { name, type: 'BANK', currency, initialBalance, openingDate: opening },
      });
    }
    await page.goto('/transferencias');
  });

  test.afterAll(async () => {
    await page.close();
  });

  test('compra: pide el monto en pesos y el monto en dólares, y muestra el tipo de cambio 1.250,00', async () => {
    await page.getByRole('button', { name: 'Nueva transferencia' }).click();
    await page.getByLabel('Fecha', { exact: true }).fill(isoDay(-1));
    await chooseOption(page, 'Cuenta de origen', 'Banco en pesos (ARS)');
    await chooseOption(page, 'Cuenta de destino', 'Caja en dólares (USD)');

    await expect(page.getByLabel('Monto', { exact: true })).toHaveCount(0);
    await page.getByLabel('Monto en pesos').fill('1.250.000,00');
    await page.getByLabel('Monto en dólares').fill('1.000,00');
    await page.getByRole('button', { name: 'Transferir' }).click();

    await expect(page.getByText('Compra de dólares registrada. Tipo de cambio: $ 1.250,00.')).toBeVisible();
    const item = page.getByTestId('transfer').first();
    await expect(item).toContainText('Banco en pesos → Caja en dólares');
    await expect(item).toContainText('$ 1.250.000,00');
    await expect(item).toContainText('US$ 1.000,00');
    await expect(item.getByTestId('exchange')).toContainText('Compra de dólares · Tipo de cambio $ 1.250,00');
  });

  test('venta: de la cuenta en dólares al banco en pesos, tipo de cambio 1.220,00', async () => {
    // El formulario sigue abierto después de guardar, con las cuentas y la fecha de la anterior.
    await page.getByLabel('Fecha', { exact: true }).fill(isoDay(0));
    await chooseOption(page, 'Cuenta de origen', 'Caja en dólares (USD)');
    await chooseOption(page, 'Cuenta de destino', 'Banco en pesos (ARS)');
    await page.getByLabel('Monto en pesos').fill('610.000,00');
    await page.getByLabel('Monto en dólares').fill('500,00');
    await page.getByRole('button', { name: 'Transferir' }).click();

    await expect(page.getByText('Venta de dólares registrada. Tipo de cambio: $ 1.220,00.')).toBeVisible();
    const item = page.getByTestId('transfer').first();
    await expect(item).toContainText('Caja en dólares → Banco en pesos');
    await expect(item.getByTestId('exchange')).toContainText('Venta de dólares · Tipo de cambio $ 1.220,00');
  });

  test('en /cuentas cada moneda cambió por separado y no hay ningún total que las mezcle', async () => {
    await page.goto('/cuentas');
    const row = (name: string) => page.getByRole('listitem').filter({ hasText: name });

    // ARS: 2.000.000,00 - 1.250.000,00 + 610.000,00. USD: 500,00 + 1.000,00 - 500,00.
    await expect(row('Banco en pesos').getByTestId('balance')).toHaveText('$ 1.360.000,00');
    await expect(row('Caja en dólares').getByTestId('balance')).toHaveText('US$ 1.000,00');
    const subtotals = page.getByTestId('subtotal');
    await expect(subtotals).toHaveCount(2);
    await expect(subtotals.nth(0)).toContainText('$ 1.360.000,00');
    await expect(subtotals.nth(1)).toContainText('US$ 1.000,00');
    await expect(page.getByText(/total general/i)).toHaveCount(0);
  });

  test('un monto en dólares en cero se rechaza por formato y no se guarda nada', async () => {
    await page.goto('/transferencias');
    await page.getByRole('button', { name: 'Nueva transferencia' }).click();
    await chooseOption(page, 'Cuenta de origen', 'Banco en pesos (ARS)');
    await chooseOption(page, 'Cuenta de destino', 'Caja en dólares (USD)');
    await page.getByLabel('Monto en pesos').fill('1.000,00');
    await page.getByLabel('Monto en dólares').fill('0');
    await page.getByRole('button', { name: 'Transferir' }).click();

    await expect(page.getByText('El monto tiene que ser mayor que 0.')).toBeVisible();
    const accounts = await api('/api/accounts');
    const balance = (name: string) =>
      accounts.accounts.find((a: { name: string }) => a.name === name).currentBalance as number;
    expect(balance('Banco en pesos')).toBe(1360000);
    expect(balance('Caja en dólares')).toBe(1000);
  });

  test('una fecha futura entre monedas distintas la rechaza el backend y no se pierde lo cargado', async () => {
    await page.getByLabel('Fecha', { exact: true }).fill(isoDay(1));
    await page.getByLabel('Monto en dólares').fill('10,00');
    await page.getByRole('button', { name: 'Transferir' }).click();

    await expect(page.getByRole('alert')).toContainText('posterior a hoy');
    await expect(page.getByLabel('Monto en pesos')).toHaveValue('1.000,00');
    await expect(page.getByLabel('Monto en dólares')).toHaveValue('10,00');
  });
});

test.describe('HU-29 · corregir o eliminar una transferencia', () => {
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
    return response.text().then((body) => (body ? JSON.parse(body) : null));
  };

  const balances = async () => {
    const list = await api('/api/accounts');
    return Object.fromEntries(
      list.accounts.map((a: { name: string; currentBalance: number }) => [a.name, a.currentBalance]),
    );
  };

  const item = (name: string) => page.getByTestId('transfer').filter({ hasText: name });

  async function showFromOpening() {
    await page.goto('/transferencias');
    await page.getByLabel('Desde').fill(opening);
    await page.getByRole('button', { name: 'Ver' }).click();
    await expect(page.getByTestId('transfer')).toHaveCount(2);
  }

  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    ({ token } = await startSession(page, playwright));
    const accounts: [string, string, number][] = [
      ['Galicia', 'ARS', 5000000],
      ['Caja chica', 'ARS', 10000],
      ['Caja en dólares', 'USD', 100],
    ];
    const ids: Record<string, number> = {};
    for (const [name, currency, initialBalance] of accounts) {
      const created = await api('/api/accounts', {
        method: 'POST',
        body: { name, type: 'BANK', currency, initialBalance, openingDate: opening },
      });
      ids[name] = created.id;
    }
    await api('/api/transfers', {
      method: 'POST',
      body: {
        sourceAccountId: ids['Galicia'],
        targetAccountId: ids['Caja chica'],
        date: isoDay(-2),
        sourceAmount: 50000,
        targetAmount: 50000,
      },
    });
    await api('/api/transfers', {
      method: 'POST',
      body: {
        sourceAccountId: ids['Galicia'],
        targetAccountId: ids['Caja en dólares'],
        date: isoDay(-1),
        sourceAmount: 1250000,
        targetAmount: 1000,
      },
    });
    await showFromOpening();
  });

  test.afterAll(async () => {
    await page.close();
  });

  test('corregir el monto: la lista muestra el nuevo y /cuentas los saldos nuevos', async () => {
    await item('Caja chica').getByRole('button', { name: /Editar/ }).click();

    await expect(page.getByRole('heading', { name: 'Editar transferencia' })).toBeVisible();
    await expect(page.getByLabel('Monto', { exact: true })).toHaveValue('50000,00');
    await page.getByLabel('Monto', { exact: true }).fill('45.000,00');
    await page.getByRole('button', { name: 'Guardar cambios' }).click();

    await expect(page.getByText('Transferencia corregida.')).toBeVisible();
    await expect(item('Caja chica')).toContainText('$ 45.000,00');

    expect(await balances()).toMatchObject({ Galicia: 3705000, 'Caja chica': 55000, 'Caja en dólares': 1100 });
    await page.goto('/cuentas');
    const row = (name: string) => page.getByRole('listitem').filter({ hasText: name });
    await expect(row('Galicia').getByTestId('balance')).toHaveText('$ 3.705.000,00');
    await expect(row('Caja chica').getByTestId('balance')).toHaveText('$ 55.000,00');
  });

  test('corregir una compra de dólares: carga los dos montos y recalcula el tipo de cambio', async () => {
    await showFromOpening();
    await item('Caja en dólares').getByRole('button', { name: /Editar/ }).click();

    await expect(page.getByLabel('Monto en pesos')).toHaveValue('1250000,00');
    await expect(page.getByLabel('Monto en dólares')).toHaveValue('1000,00');
    await page.getByLabel('Monto en pesos').fill('1.300.000,00');
    await page.getByRole('button', { name: 'Guardar cambios' }).click();

    await expect(page.getByText('Compra de dólares corregida. Tipo de cambio: $ 1.300,00.')).toBeVisible();
    await expect(item('Caja en dólares').getByTestId('exchange')).toContainText('Tipo de cambio $ 1.300,00');
    expect(await balances()).toMatchObject({ Galicia: 3655000, 'Caja en dólares': 1100 });
  });

  test('una fecha futura al editar la rechaza el backend, no se pierde lo cargado y no cambia nada', async () => {
    await item('Caja chica').getByRole('button', { name: /Editar/ }).click();
    await page.getByLabel('Fecha', { exact: true }).fill(isoDay(1));
    await page.getByLabel('Monto', { exact: true }).fill('1.000,00');
    await page.getByRole('button', { name: 'Guardar cambios' }).click();

    await expect(page.getByRole('alert')).toContainText('posterior a hoy');
    await expect(page.getByLabel('Monto', { exact: true })).toHaveValue('1.000,00');
    await expect(page.getByLabel('Fecha', { exact: true })).toHaveValue(isoDay(1));
    expect(await balances()).toMatchObject({ Galicia: 3655000, 'Caja chica': 55000 });
    await page.getByRole('button', { name: 'Cancelar' }).click();
    await expect(page.getByRole('heading', { name: 'Editar transferencia' })).toHaveCount(0);
  });

  test('eliminar pide confirmar con los datos de la transferencia; cancelar no elimina', async () => {
    await item('Caja en dólares').getByRole('button', { name: /Eliminar/ }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog).toContainText('Galicia → Caja en dólares');
    await expect(dialog).toContainText('$ 1.300.000,00');
    await expect(dialog).toContainText('US$ 1.000,00');
    await expect(dialog).toContainText('tipo de cambio $ 1.300,00');
    await dialog.getByRole('button', { name: 'Cancelar' }).click();

    await expect(page.getByTestId('transfer')).toHaveCount(2);
  });

  test('eliminar las dos: los saldos vuelven al principio y el foco queda en Nueva transferencia', async () => {
    for (const name of ['Caja en dólares', 'Caja chica']) {
      await item(name).getByRole('button', { name: /Eliminar/ }).click();
      await page.getByRole('dialog').getByRole('button', { name: 'Eliminar', exact: true }).click();
      await expect(page.getByText('Transferencia eliminada.')).toBeVisible();
      await expect(item(name)).toHaveCount(0);
    }

    await expect(page.getByText('No hay transferencias en ese rango.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Nueva transferencia' })).toBeFocused();
    expect(await balances()).toMatchObject({ Galicia: 5000000, 'Caja chica': 10000, 'Caja en dólares': 100 });
  });
});
