import { Page, PlaywrightWorkerArgs, expect } from '@playwright/test';

export type Playwright = PlaywrightWorkerArgs['playwright'];

export const API_URL = process.env['E2E_API_URL'] ?? 'http://localhost:8080';
export const SESSION_KEY = 'smartcoin.session';
export const INITIAL_PASSWORD = 'contrasena-inicial-e2e';
export const PASSWORD = 'contrasena-definitiva-e2e';

/** Dos meses antes del actual, para poder probar cierres en historias futuras (YYYY-MM). */
export function startPeriod(): string {
  const date = new Date();
  date.setMonth(date.getMonth() - 2, 1);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

let userCount = 0;

/**
 * Crea un usuario propio con el endpoint de administración: los tests nunca usan el usuario real. Queda con la
 * contraseña inicial y el cambio obligatorio pendiente.
 */
export async function createUser(playwright: Playwright): Promise<string> {
  const adminKey = process.env['APP_ADMIN_KEY'];
  if (!adminKey) {
    throw new Error('Falta la variable de entorno APP_ADMIN_KEY para crear el usuario de prueba.');
  }
  const email = `e2e-${Date.now()}-${userCount++}@prueba.local`;
  const api = await playwright.request.newContext({ baseURL: API_URL });
  const response = await api.post('/api/admin/users', {
    headers: { 'X-Admin-Key': adminKey },
    data: { email, password: INITIAL_PASSWORD, startPeriod: startPeriod() },
  });
  expect(response.status(), 'alta del usuario de prueba').toBe(201);
  await api.dispose();
  return email;
}

/**
 * Crea un usuario, le cambia la contraseña inicial por la API (para no repetir la pantalla obligatoria) y deja esa
 * sesión guardada en el navegador, como la guarda la aplicación.
 */
export async function startSession(page: Page, playwright: Playwright): Promise<{ email: string; token: string }> {
  const email = await createUser(playwright);
  const api = await playwright.request.newContext({ baseURL: API_URL });
  const first = await (await api.post('/api/auth/login', { data: { email, password: INITIAL_PASSWORD } })).json();
  const change = await api.post('/api/auth/change-password', {
    headers: { Authorization: `Bearer ${first.token}` },
    data: { currentPassword: INITIAL_PASSWORD, newPassword: PASSWORD },
  });
  expect(change.status(), 'cambio de contraseña por la API').toBe(200);
  const login = await change.json();
  await api.dispose();
  await page.goto('/login');
  await page.evaluate(
    ([key, session]) => sessionStorage.setItem(key, session),
    [
      SESSION_KEY,
      JSON.stringify({
        token: login.token,
        expiresAt: login.expiresAt,
        mustChangePassword: login.mustChangePassword,
      }),
    ],
  );
  return { email, token: login.token as string };
}
