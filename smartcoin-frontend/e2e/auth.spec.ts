import { Page, PlaywrightWorkerArgs, expect, test } from '@playwright/test';

type Playwright = PlaywrightWorkerArgs['playwright'];

const API_URL = process.env['E2E_API_URL'] ?? 'http://localhost:8080';
const SESSION_KEY = 'smartcoin.session';
const PASSWORD = 'contrasena-inicial-e2e';

/** Dos meses antes del actual, para poder probar cierres en historias futuras (YYYY-MM). */
function startPeriod(): string {
  const date = new Date();
  date.setMonth(date.getMonth() - 2, 1);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

let userCount = 0;

/**
 * Crea un usuario propio con el endpoint de administración: nunca se usa el usuario real. Queda con la contraseña
 * inicial y el cambio obligatorio pendiente.
 */
async function createUser(playwright: Playwright) {
  const adminKey = process.env['APP_ADMIN_KEY'];
  if (!adminKey) {
    throw new Error('Falta la variable de entorno APP_ADMIN_KEY para crear el usuario de prueba.');
  }
  const email = `e2e-${Date.now()}-${userCount++}@prueba.local`;
  const api = await playwright.request.newContext({ baseURL: API_URL });
  const response = await api.post('/api/admin/users', {
    headers: { 'X-Admin-Key': adminKey },
    data: { email, password: PASSWORD, startPeriod: startPeriod() },
  });
  expect(response.status(), 'alta del usuario de prueba').toBe(201);
  await api.dispose();
  return email;
}

/** Inicia sesión por la API, sin pasar por la pantalla. */
async function apiLogin(playwright: Playwright, email: string, password: string) {
  const api = await playwright.request.newContext({ baseURL: API_URL });
  const response = await api.post('/api/auth/login', { data: { email, password } });
  expect(response.status(), 'login por la API').toBe(200);
  const login = await response.json();
  await api.dispose();
  return login as { token: string; expiresAt: string; mustChangePassword: boolean };
}

async function loginInScreen(page: Page, email: string, password = PASSWORD) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Contraseña').fill(password);
  await page.getByRole('button', { name: 'Ingresar' }).click();
}

/**
 * Deja al usuario con la contraseña ya cambiada (por la API, para no repetir la pantalla obligatoria) y con esa sesión
 * guardada en el navegador, como la guarda la aplicación.
 */
async function startSessionWithPasswordChanged(
  page: Page,
  playwright: Playwright,
  newPassword: string,
) {
  const email = await createUser(playwright);
  const first = await apiLogin(playwright, email, PASSWORD);
  const api = await playwright.request.newContext({ baseURL: API_URL });
  const change = await api.post('/api/auth/change-password', {
    headers: { Authorization: `Bearer ${first.token}` },
    data: { currentPassword: PASSWORD, newPassword },
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

/** Estado HTTP de `GET /api/auth/me` con ese token: 200 si sirve, 401 si ya no. */
async function meStatus(playwright: Playwright, token: string) {
  const api = await playwright.request.newContext({ baseURL: API_URL });
  const response = await api.get('/api/auth/me', { headers: { Authorization: `Bearer ${token}` } });
  await api.dispose();
  return response.status();
}

const userMenuButton = (page: Page, email: string) => page.getByRole('button', { name: email });

async function openUserMenu(page: Page, email: string) {
  await userMenuButton(page, email).click();
}

async function fillChangeForm(page: Page, current: string, next: string, repeat = next) {
  await page.getByLabel('Contraseña actual').fill(current);
  await page.getByLabel('Contraseña nueva', { exact: true }).fill(next);
  await page.getByLabel('Repetí la contraseña nueva').fill(repeat);
}

const storedToken = async (page: Page) =>
  JSON.parse((await page.evaluate((key) => sessionStorage.getItem(key), SESSION_KEY))!).token;

test.describe('inicio y cierre de sesión', () => {
  let email: string;

  test.beforeAll(async ({ playwright }) => {
    email = await createUser(playwright);
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
    await expect(page.getByRole('heading', { level: 1, name: 'Cambiar contraseña' })).toBeVisible();
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

  test('cerrar sesión desde el menú de usuario descarta el token y vuelve al login', async ({
    page,
    playwright,
  }) => {
    // Con el cambio pendiente el usuario no llega al menú: se cambia la contraseña por la API (el flujo por pantalla
    // se prueba abajo).
    const { email: loggedOut } = await startSessionWithPasswordChanged(
      page,
      playwright,
      'contrasena-ya-cambiada',
    );

    await page.goto('/presupuesto');
    await expect(page.getByRole('navigation', { name: 'Principal' })).toBeVisible();
    await openUserMenu(page, loggedOut);
    await page.getByRole('menuitem', { name: 'Cerrar sesión' }).click();

    await expect(page).toHaveURL(/\/login$/);
    expect(await page.evaluate((key) => sessionStorage.getItem(key), SESSION_KEY)).toBeNull();
    await page.goto('/presupuesto');
    await expect(page).toHaveURL(/\/login$/);
  });
});

// Cada test crea su propio usuario: cambiar la contraseña consume el estado inicial.
test.describe('cambio de contraseña obligatorio', () => {
  const NEW_PASSWORD = 'contrasena-nueva-e2e';
  let email: string;

  test.beforeEach(async ({ playwright }) => {
    email = await createUser(playwright);
  });

  test('el login lleva a la pantalla de cambio, que avisa que es obligatorio', async ({ page }) => {
    await loginInScreen(page, email);

    await expect(page).toHaveURL(/\/cambiar-contrasena$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Cambiar contraseña' })).toBeVisible();
    await expect(
      page.getByText('Tenés que cambiar la contraseña inicial antes de seguir.'),
    ).toBeVisible();
    // Sin salida hasta cambiarla: ni menú de usuario ni "Cancelar", solo cerrar la sesión.
    await expect(page.getByRole('navigation', { name: 'Principal' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: email })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Cancelar' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Cerrar sesión' })).toBeVisible();
  });

  test('con el cambio pendiente, cualquier otra ruta vuelve a /cambiar-contrasena', async ({
    page,
  }) => {
    await loginInScreen(page, email);
    await expect(page).toHaveURL(/\/cambiar-contrasena$/);

    for (const route of ['/cuentas', '/presupuesto/2026-10', '/']) {
      await page.goto(route);
      await expect(page).toHaveURL(/\/cambiar-contrasena$/);
    }
    // El login tampoco sirve de salida: con sesión iniciada vuelve acá.
    await page.goto('/login');
    await expect(page).toHaveURL(/\/cambiar-contrasena$/);
    // La sesión sigue intacta.
    expect(await storedToken(page)).toBeTruthy();
  });

  test('cambiar la contraseña reemplaza el token y lleva al presupuesto del mes', async ({
    page,
    playwright,
  }) => {
    await loginInScreen(page, email);
    await expect(page).toHaveURL(/\/cambiar-contrasena$/);
    const oldToken = await storedToken(page);

    await fillChangeForm(page, PASSWORD, NEW_PASSWORD);
    await page.getByRole('button', { name: 'Cambiar contraseña' }).click();

    await expect(page).toHaveURL(/\/presupuesto$/);
    await expect(page.getByRole('navigation', { name: 'Principal' })).toBeVisible();
    await expect(page.getByText('Contraseña cambiada.')).toBeVisible();
    const newToken = await storedToken(page);
    expect(newToken).not.toBe(oldToken);

    // Ya no hay cambio pendiente: el resto de la aplicación es accesible.
    await page.goto('/cuentas');
    await expect(page).toHaveURL(/\/cuentas$/);

    // El token anterior ya no sirve; el nuevo sí.
    const api = await playwright.request.newContext({ baseURL: API_URL });
    const old = await api.get('/api/auth/me', { headers: { Authorization: `Bearer ${oldToken}` } });
    expect(old.status(), 'token anterior').toBe(401);
    const current = await api.get('/api/auth/me', {
      headers: { Authorization: `Bearer ${newToken}` },
    });
    expect(current.status(), 'token nuevo').toBe(200);
    expect((await current.json()).mustChangePassword).toBe(false);
    await api.dispose();

    // La contraseña inicial dejó de valer y la nueva funciona.
    await openUserMenu(page, email);
    await page.getByRole('menuitem', { name: 'Cerrar sesión' }).click();
    await loginInScreen(page, email, PASSWORD);
    await expect(page.getByRole('alert')).toHaveText('Email o contraseña incorrectos.');
    await loginInScreen(page, email, NEW_PASSWORD);
    await expect(page).toHaveURL(/\/presupuesto$/);
  });

  test('con la contraseña actual incorrecta muestra el error y sigue pendiente', async ({
    page,
  }) => {
    await loginInScreen(page, email);
    await expect(page).toHaveURL(/\/cambiar-contrasena$/);
    const token = await storedToken(page);

    await fillChangeForm(page, 'esta-no-es-la-actual', NEW_PASSWORD);
    await page.getByRole('button', { name: 'Cambiar contraseña' }).click();

    await expect(page.getByRole('alert')).toHaveText('La contraseña actual es incorrecta.');
    await expect(page).toHaveURL(/\/cambiar-contrasena$/);
    expect(await storedToken(page)).toBe(token);
    await page.goto('/cuentas');
    await expect(page).toHaveURL(/\/cambiar-contrasena$/);
  });

  test('una nueva igual a la actual muestra el error del backend', async ({ page }) => {
    await loginInScreen(page, email);

    await fillChangeForm(page, PASSWORD, PASSWORD);
    await page.getByRole('button', { name: 'Cambiar contraseña' }).click();

    await expect(page.getByRole('alert')).toHaveText(
      'La contraseña nueva debe ser distinta de la actual.',
    );
    await expect(page).toHaveURL(/\/cambiar-contrasena$/);
  });

  test('valida el formato en el formulario sin llamar a la API', async ({ page }) => {
    await loginInScreen(page, email);
    let calls = 0;
    page.on('request', (request) => {
      if (request.url().includes('/api/auth/change-password')) {
        calls++;
      }
    });

    await fillChangeForm(page, PASSWORD, 'corta', 'otra');
    await page.getByRole('button', { name: 'Cambiar contraseña' }).click();

    await expect(
      page.getByText('La contraseña nueva debe tener al menos 10 caracteres.'),
    ).toBeVisible();
    await expect(page.getByText('Las contraseñas no coinciden.')).toBeVisible();
    expect(calls).toBe(0);
  });
});

// Cada test tiene su propio usuario, con el cambio obligatorio ya hecho salvo en el primero, que lo recorre entero.
test.describe('cambio de contraseña voluntario', () => {
  const CHANGED = 'contrasena-cambiada-e2e';
  const NEXT = 'contrasena-voluntaria-e2e';

  test('desde el menú: cambiarla, seguir sin volver a iniciar sesión, cerrar sesión y entrar con la nueva', async ({
    page,
    playwright,
  }) => {
    const email = await createUser(playwright);
    const initial = await apiLogin(playwright, email, PASSWORD);

    // Cambio obligatorio por la pantalla.
    await loginInScreen(page, email);
    await expect(page).toHaveURL(/\/cambiar-contrasena$/);
    await fillChangeForm(page, PASSWORD, CHANGED);
    await page.getByRole('button', { name: 'Cambiar contraseña' }).click();
    await expect(page).toHaveURL(/\/presupuesto$/);
    const afterMandatory = await storedToken(page);

    // Desde otra pantalla, el menú muestra el email y lleva al mismo formulario, ahora dentro del layout.
    await page.getByRole('link', { name: 'Cuentas' }).click();
    await expect(page).toHaveURL(/\/cuentas$/);
    await expect(userMenuButton(page, email)).toBeVisible();
    await openUserMenu(page, email);
    await expect(page.getByRole('menuitem')).toHaveText(['Cambiar contraseña', 'Cerrar sesión']);
    await page.getByRole('menuitem', { name: 'Cambiar contraseña' }).click();

    await expect(page).toHaveURL(/\/cambiar-contrasena$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Cambiar contraseña' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Principal' })).toBeVisible();
    await expect(page.getByText('Tenés que cambiar la contraseña inicial')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Cancelar' })).toBeVisible();

    await fillChangeForm(page, CHANGED, NEXT);
    await page.getByRole('button', { name: 'Cambiar contraseña' }).click();

    // Confirma y vuelve a donde estaba, con el token nuevo y sin pasar por el login.
    await expect(page).toHaveURL(/\/cuentas$/);
    await expect(page.getByText('Contraseña cambiada.')).toBeVisible();
    const afterVoluntary = await storedToken(page);
    expect(afterVoluntary).not.toBe(afterMandatory);

    // Se sigue navegando sin recargar y recargando: la sesión no se pierde.
    await page.getByRole('link', { name: 'Conceptos' }).click();
    await expect(page).toHaveURL(/\/conceptos$/);
    await page.goto('/categorias');
    await expect(page).toHaveURL(/\/categorias$/);
    await expect(userMenuButton(page, email)).toBeVisible();
    expect(await storedToken(page)).toBe(afterVoluntary);

    // Los tokens anteriores dejan de funcionar; el nuevo sí.
    expect(await meStatus(playwright, initial.token), 'token del login inicial').toBe(401);
    expect(await meStatus(playwright, afterMandatory), 'token anterior al cambio').toBe(401);
    expect(await meStatus(playwright, afterVoluntary), 'token nuevo').toBe(200);

    // Cerrar sesión por el menú y entrar con la contraseña nueva; la anterior ya no vale.
    await openUserMenu(page, email);
    await page.getByRole('menuitem', { name: 'Cerrar sesión' }).click();
    await expect(page).toHaveURL(/\/login$/);
    await loginInScreen(page, email, CHANGED);
    await expect(page.getByRole('alert')).toHaveText('Email o contraseña incorrectos.');
    await loginInScreen(page, email, NEXT);
    await expect(page).toHaveURL(/\/presupuesto$/);
    await expect(userMenuButton(page, email)).toBeVisible();
  });

  test('con la contraseña actual incorrecta muestra el error y no cambia nada', async ({
    page,
    playwright,
  }) => {
    const { email, token } = await startSessionWithPasswordChanged(page, playwright, CHANGED);
    await page.goto('/cuentas');
    await openUserMenu(page, email);
    await page.getByRole('menuitem', { name: 'Cambiar contraseña' }).click();

    await fillChangeForm(page, 'esta-no-es-la-actual', NEXT);
    await page.getByRole('button', { name: 'Cambiar contraseña' }).click();

    await expect(page.getByRole('alert')).toHaveText('La contraseña actual es incorrecta.');
    await expect(page).toHaveURL(/\/cambiar-contrasena$/);
    await expect(page.getByRole('navigation', { name: 'Principal' })).toBeVisible();
    await expect(page.getByText('Contraseña cambiada.')).toHaveCount(0);
    expect(await storedToken(page)).toBe(token);
    expect(await meStatus(playwright, token), 'el token sigue sirviendo').toBe(200);
  });

  test('"Cancelar" vuelve a la pantalla de origen sin cambiar nada', async ({
    page,
    playwright,
  }) => {
    const { email, token } = await startSessionWithPasswordChanged(page, playwright, CHANGED);
    await page.goto('/presupuesto/2026-10');
    await openUserMenu(page, email);
    await page.getByRole('menuitem', { name: 'Cambiar contraseña' }).click();
    await expect(page).toHaveURL(/\/cambiar-contrasena$/);

    await page.getByRole('button', { name: 'Cancelar' }).click();

    await expect(page).toHaveURL(/\/presupuesto\/2026-10$/);
    expect(await storedToken(page)).toBe(token);
  });

  test('abierta directamente, "Cancelar" lleva al presupuesto', async ({ page, playwright }) => {
    await startSessionWithPasswordChanged(page, playwright, CHANGED);

    await page.goto('/cambiar-contrasena');
    await expect(page.getByRole('navigation', { name: 'Principal' })).toBeVisible();
    await page.getByRole('button', { name: 'Cancelar' }).click();

    await expect(page).toHaveURL(/\/presupuesto$/);
  });

  test('el menú se maneja con el teclado y muestra el foco', async ({ page, playwright }) => {
    const { email } = await startSessionWithPasswordChanged(page, playwright, CHANGED);
    await page.goto('/presupuesto');

    const trigger = userMenuButton(page, email);
    await trigger.focus();
    await expect(trigger).toBeFocused();
    await page.keyboard.press('Enter');

    // Al abrir, el foco pasa a la primera opción; las flechas recorren y Esc cierra devolviendo el foco.
    await expect(page.getByRole('menuitem', { name: 'Cambiar contraseña' })).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(page.getByRole('menuitem', { name: 'Cerrar sesión' })).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('menuitem')).toHaveCount(0);
    await expect(trigger).toBeFocused();

    await page.keyboard.press('Enter');
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/cambiar-contrasena$/);
  });
});
