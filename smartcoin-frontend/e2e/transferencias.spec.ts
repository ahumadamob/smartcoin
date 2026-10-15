import { Page, expect, test } from '@playwright/test';
import { API_URL, startPeriod, startSession } from './support';

/** Épica 7 · Transferencias. HU-27 (entre cuentas de la misma moneda). Cada corrida usa un usuario propio. */

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

  test('solo ofrece como destino cuentas de la misma moneda y distintas del origen', async () => {
    await chooseOption(page, 'Cuenta de origen', 'Galicia (ARS)');
    await page.getByRole('combobox', { name: 'Cuenta de destino' }).click();

    await expect(page.getByRole('option')).toHaveText(['Caja chica (ARS)']);
    await page.getByRole('option', { name: 'Caja chica (ARS)', exact: true }).click();
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
