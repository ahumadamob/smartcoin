import { APIRequestContext, expect, test } from '@playwright/test';

const API_URL = process.env['E2E_API_URL'] ?? 'http://localhost:8080';
const SESSION_KEY = 'smartcoin.session';
const PASSWORD = 'contrasena-inicial-e2e';

/** Dos meses antes del actual, para poder probar cierres en historias futuras (YYYY-MM). */
function startPeriod(): string {
  const date = new Date();
  date.setMonth(date.getMonth() - 2, 1);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

// Cada corrida crea su propio usuario con el endpoint de administración: nunca se usa el usuario real.
test.describe('inicio y cierre de sesión', () => {
  let email: string;

  test.beforeAll(async ({ playwright }) => {
    const adminKey = process.env['APP_ADMIN_KEY'];
    if (!adminKey) {
      throw new Error(
        'Falta la variable de entorno APP_ADMIN_KEY para crear el usuario de prueba.',
      );
    }
    email = `e2e-${Date.now()}@prueba.local`;
    const api: APIRequestContext = await playwright.request.newContext({ baseURL: API_URL });
    const response = await api.post('/api/admin/users', {
      headers: { 'X-Admin-Key': adminKey },
      data: { email, password: PASSWORD, startPeriod: startPeriod() },
    });
    expect(response.status(), 'alta del usuario de prueba').toBe(201);
    await api.dispose();
  });

  test('sin sesión, cualquier ruta lleva al login', async ({ page }) => {
    await page.goto('/cuentas');

    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Iniciar sesión' })).toBeVisible();
  });

  test('login correcto con cambio de contraseña pendiente va a /cambiar-contrasena', async ({
    page,
  }) => {
    await page.goto('/login');

    // El email no distingue mayúsculas.
    await page.getByLabel('Email').fill(email.toUpperCase());
    await page.getByLabel('Contraseña').fill(PASSWORD);
    await page.getByRole('button', { name: 'Ingresar' }).click();

    await expect(page).toHaveURL(/\/cambiar-contrasena$/);
    const session = await page.evaluate((key) => sessionStorage.getItem(key), SESSION_KEY);
    expect(session).not.toBeNull();
    expect(JSON.parse(session!).token).toBeTruthy();
  });

  test('con contraseña incorrecta muestra el error y se queda en el login', async ({ page }) => {
    await page.goto('/login');

    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Contraseña').fill('esta-no-es-la-contrasena');
    await page.getByRole('button', { name: 'Ingresar' }).click();

    await expect(page.getByRole('alert')).toHaveText('Email o contraseña incorrectos.');
    await expect(page).toHaveURL(/\/login$/);
    expect(await page.evaluate((key) => sessionStorage.getItem(key), SESSION_KEY)).toBeNull();
  });

  test('con un usuario inexistente muestra el mismo error', async ({ page }) => {
    await page.goto('/login');

    await page.getByLabel('Email').fill('nadie@prueba.local');
    await page.getByLabel('Contraseña').fill(PASSWORD);
    await page.getByRole('button', { name: 'Ingresar' }).click();

    await expect(page.getByRole('alert')).toHaveText('Email o contraseña incorrectos.');
  });

  test('cerrar sesión descarta el token y vuelve al login', async ({ page, playwright }) => {
    // El usuario recién creado debe cambiar la contraseña y no llega al menú por la pantalla de login (se prueba en
    // HU-04): se inicia sesión por la API y se guarda el token como lo hace la aplicación.
    const api = await playwright.request.newContext({ baseURL: API_URL });
    const login = await (
      await api.post('/api/auth/login', { data: { email, password: PASSWORD } })
    ).json();
    await api.dispose();
    await page.goto('/login');
    await page.evaluate(
      ([key, session]) => sessionStorage.setItem(key, session),
      [SESSION_KEY, JSON.stringify({ token: login.token, expiresAt: login.expiresAt })],
    );

    await page.goto('/presupuesto');
    await expect(page.getByRole('navigation', { name: 'Principal' })).toBeVisible();
    await page.getByRole('button', { name: 'Cerrar sesión' }).click();

    await expect(page).toHaveURL(/\/login$/);
    expect(await page.evaluate((key) => sessionStorage.getItem(key), SESSION_KEY)).toBeNull();
    await page.goto('/presupuesto');
    await expect(page).toHaveURL(/\/login$/);
  });
});
