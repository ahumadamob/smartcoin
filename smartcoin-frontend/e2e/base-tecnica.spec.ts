import { expect, test } from '@playwright/test';

// Estas pruebas solo recorren el menú: alcanza con una sesión guardada que el guard acepte. Lo único que piden el
// layout (el usuario actual, para el menú de usuario) y las pantallas de cuentas, categorías y Conceptos (sus listas),
// del presupuesto (el mes actual) y del flujo de caja se responde acá, porque el token es falso. Cada pantalla que llame a la API desde su historia suma su respuesta simulada.
test.beforeEach(async ({ page }) => {
  await page.route('**/api/accounts', (route) => route.fulfill({ json: { accounts: [], subtotals: [] } }));
  await page.route('**/api/categories', (route) => route.fulfill({ json: [] }));
  await page.route(/\/api\/transfers(\?.*)?$/, (route) => route.fulfill({ json: [] }));
  await page.route(/\/api\/budget-items(\?.*)?$/, (route) => route.fulfill({ json: [] }));
  await page.route(/\/api\/cashflow(\?.*)?$/, (route) =>
    route.fulfill({
      json: {
        currency: 'ARS',
        from: '2026-10-01',
        to: '2026-10-31',
        openingBalance: 0,
        openingBalanceDate: '2026-09-30',
        openingBalanceProjected: false,
        rows: [],
        closingBalance: 0,
      },
    }),
  );
  await page.route('**/api/periods/current', (route) =>
    route.fulfill({
      json: {
        period: '2026-10',
        status: 'OPEN',
        startPeriod: '2026-08',
        currentPeriod: '2026-10',
        horizon: '2028-10',
        incomes: [],
        expenses: [],
        totals: [],
      },
    }),
  );
  await page.route('**/api/auth/me', (route) =>
    route.fulfill({
      json: {
        id: 1,
        email: 'base@prueba.local',
        mustChangePassword: false,
        startPeriod: '2026-08',
      },
    }),
  );
  await page.addInitScript(() => {
    sessionStorage.setItem(
      'smartcoin.session',
      JSON.stringify({ token: 'token-de-prueba', expiresAt: '2999-01-01T00:00:00Z' }),
    );
  });
});

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
    ['Presupuesto', 'Presupuesto de octubre 2026', /\/presupuesto$/],
  ];
  for (const [link, heading, url] of screens) {
    await menu.getByRole('link', { name: link }).click();
    await expect(page).toHaveURL(url);
    await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible();
  }
});
