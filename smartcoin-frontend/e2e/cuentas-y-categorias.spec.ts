import { Page, expect, test } from '@playwright/test';
import { startPeriod, startSession } from './support';

/** Épica 2 · Cuentas y categorías. Por ahora, HU-07 (cuentas). Cada corrida usa un usuario propio. */

const firstDayOfStartPeriod = `${startPeriod()}-01`;

async function chooseOption(page: Page, label: string, option: string) {
  await page.getByLabel(label, { exact: true }).click();
  await page.getByRole('option', { name: option, exact: true }).click();
}

async function fillAccount(
  page: Page,
  account: { name: string; type: string; currency?: string; balance: string; date?: string },
) {
  await page.getByLabel('Nombre', { exact: true }).fill(account.name);
  await chooseOption(page, 'Tipo', account.type);
  if (account.currency) {
    await chooseOption(page, 'Moneda', account.currency);
  }
  if (account.date) {
    await page.getByLabel('Fecha de apertura').fill(account.date);
  }
  await page.getByLabel('Saldo inicial').fill(account.balance);
}

/** Fila de la lista de una cuenta. */
const row = (page: Page, name: string) => page.getByRole('listitem').filter({ hasText: name });
/** Grupo de la lista de una moneda: su encabezado y las cuentas. */
const group = (page: Page, heading: string) =>
  page.getByRole('region', { name: heading });

test.describe('HU-07 · cuentas', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;

  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    await startSession(page, playwright);
    await page.goto('/cuentas');
  });

  test.afterAll(async () => {
    await page.close();
  });

  test('sin cuentas lo avisa y sugiere el primer día del período inicial', async () => {
    await expect(page.getByRole('heading', { level: 1, name: 'Cuentas' })).toBeVisible();
    await expect(page.getByText('Todavía no cargaste ninguna cuenta')).toBeVisible();

    await page.getByRole('button', { name: 'Nueva cuenta' }).click();

    await expect(page.getByLabel('Fecha de apertura')).toHaveValue(firstDayOfStartPeriod);
  });

  test('alta de una cuenta en pesos con saldo con coma decimal', async () => {
    await fillAccount(page, { name: 'Banco Nación', type: 'Banco', currency: '$ (ARS)', balance: '150.000,50' });
    await page.getByRole('button', { name: 'Guardar' }).click();

    await expect(page.getByText('Cuenta «Banco Nación» creada.')).toBeVisible();
    await expect(page.getByLabel('Nombre', { exact: true })).toHaveCount(0);
    await expect(group(page, '$ (ARS)')).toBeVisible();
    await expect(row(page, 'Banco Nación')).toContainText('$ 150.000,50');
    await expect(row(page, 'Banco Nación')).toContainText('Banco');
  });

  test('alta de una cuenta en dólares: se agrupa aparte y no se mezcla con los pesos', async () => {
    await page.getByRole('button', { name: 'Nueva cuenta' }).click();
    await fillAccount(page, {
      name: 'Caja de ahorro USD',
      type: 'Billetera virtual',
      currency: 'US$ (USD)',
      balance: '1200',
    });
    await page.getByRole('button', { name: 'Guardar' }).click();

    await expect(row(page, 'Caja de ahorro USD')).toContainText('US$ 1.200,00');
    await expect(group(page, 'US$ (USD)').getByText('Caja de ahorro USD')).toBeVisible();
    await expect(group(page, '$ (ARS)').getByText('Caja de ahorro USD')).toHaveCount(0);
    await expect(group(page, '$ (ARS)').getByText('Banco Nación')).toBeVisible();
  });

  test('nombre repetido, sin distinguir mayúsculas: muestra el error y no crea la cuenta', async () => {
    await page.getByRole('button', { name: 'Nueva cuenta' }).click();
    await fillAccount(page, { name: 'BANCO NACIÓN', type: 'Efectivo', currency: '$ (ARS)', balance: '0' });
    await page.getByRole('button', { name: 'Guardar' }).click();

    await expect(page.getByRole('alert')).toHaveText('Ya tenés una cuenta con ese nombre.');
    // El formulario sigue abierto con lo escrito, para corregirlo.
    await expect(page.getByLabel('Nombre', { exact: true })).toHaveValue('BANCO NACIÓN');
    await page.getByRole('button', { name: 'Cancelar' }).click();
    await expect(page.getByRole('listitem')).toHaveCount(2);
  });

  test('una fecha de apertura futura es rechazada por el backend', async () => {
    await page.getByRole('button', { name: 'Nueva cuenta' }).click();
    const future = new Date(Date.now() + 3 * 24 * 3600 * 1000).toISOString().slice(0, 10);
    await fillAccount(page, {
      name: 'Cuenta del futuro',
      type: 'Banco',
      currency: '$ (ARS)',
      balance: '0',
      date: future,
    });
    await page.getByRole('button', { name: 'Guardar' }).click();

    await expect(page.getByRole('alert')).toContainText('La fecha de apertura no puede ser futura.');
    await page.getByRole('button', { name: 'Cancelar' }).click();
  });

  test('edición: sin referencias se puede cambiar todo, incluso a un saldo negativo, y queda guardado', async () => {
    await page.getByRole('button', { name: 'Editar Banco Nación' }).click();

    await expect(page.getByRole('heading', { name: 'Editar cuenta' })).toBeVisible();
    await expect(page.getByLabel('Nombre', { exact: true })).toHaveValue('Banco Nación');
    await expect(page.getByLabel('Saldo inicial')).toHaveValue('150000,50');
    // La cuenta no tiene referencias: ningún campo está bloqueado.
    await expect(page.getByLabel('Moneda', { exact: true })).not.toHaveAttribute('aria-disabled', 'true');
    await expect(page.getByLabel('Saldo inicial')).toBeEnabled();
    await expect(page.getByLabel('Fecha de apertura')).toBeEnabled();

    await page.getByLabel('Nombre', { exact: true }).fill('Banco Nación (principal)');
    await page.getByLabel('Saldo inicial').fill('-1.500,25');
    await page.getByRole('button', { name: 'Guardar' }).click();

    await expect(page.getByText('Cuenta «Banco Nación (principal)» guardada.')).toBeVisible();
    await expect(row(page, 'Banco Nación (principal)')).toContainText('-$ 1.500,25');

    await page.reload();
    await expect(row(page, 'Banco Nación (principal)')).toContainText('-$ 1.500,25');
  });

  test('renombrar a un nombre que ya tiene otra cuenta da el error', async () => {
    await page.getByRole('button', { name: 'Editar Caja de ahorro USD' }).click();
    await page.getByLabel('Nombre', { exact: true }).fill('banco nación (PRINCIPAL)');
    await page.getByRole('button', { name: 'Guardar' }).click();

    await expect(page.getByRole('alert')).toHaveText('Ya tenés una cuenta con ese nombre.');
    await page.getByRole('button', { name: 'Cancelar' }).click();
    await expect(row(page, 'Caja de ahorro USD')).toBeVisible();
  });

  test('eliminación: pide confirmación; cancelar no borra y confirmar sí', async () => {
    await page.getByRole('button', { name: 'Eliminar Caja de ahorro USD' }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog).toContainText('«Caja de ahorro USD»');
    await dialog.getByRole('button', { name: 'Cancelar' }).click();
    await expect(row(page, 'Caja de ahorro USD')).toBeVisible();

    await page.getByRole('button', { name: 'Eliminar Caja de ahorro USD' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Eliminar' }).click();

    await expect(page.getByText('Cuenta «Caja de ahorro USD» eliminada.')).toBeVisible();
    await expect(row(page, 'Caja de ahorro USD')).toHaveCount(0);
    // Sin cuentas en dólares, el grupo desaparece.
    await expect(group(page, 'US$ (USD)')).toHaveCount(0);
    await expect(row(page, 'Banco Nación (principal)')).toBeVisible();
  });
});
