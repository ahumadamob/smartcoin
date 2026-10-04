import { expect, test } from '@playwright/test';

test('abre la aplicación y muestra el menú', async ({ page }) => {
  await page.goto('/');

  await expect(page).toHaveTitle(/Smartcoin/);
  await expect(page.getByRole('navigation', { name: 'Principal' })).toBeVisible();
  await expect(page).toHaveURL(/\/presupuesto$/);
});

test('el menú navega entre las pantallas', async ({ page }) => {
  await page.goto('/');
  const menu = page.getByRole('navigation', { name: 'Principal' });

  const screens: [string, string, RegExp][] = [
    ['Conceptos', 'Conceptos', /\/conceptos$/],
    ['Cuentas', 'Cuentas', /\/cuentas$/],
    ['Categorías', 'Categorías', /\/categorias$/],
    ['Transferencias', 'Transferencias', /\/transferencias$/],
    ['Flujo de caja', 'Flujo de caja', /\/flujo-de-caja$/],
    ['Proyección', 'Proyección', /\/proyeccion$/],
    ['Varios meses', 'Varios meses', /\/planificacion$/],
    ['Presupuesto', 'Presupuesto del mes', /\/presupuesto$/],
  ];
  for (const [link, heading, url] of screens) {
    await menu.getByRole('link', { name: link }).click();
    await expect(page).toHaveURL(url);
    await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible();
  }
});
