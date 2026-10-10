import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { API_URL, startPeriod, startSession } from './support';

/**
 * Épica 3 · Conceptos y presupuesto del mes. HU-10 (crear un Concepto recurrente), HU-11 (en cuotas), HU-13 (editar
 * un Concepto), HU-14 (listar Conceptos) y HU-15 (ver el presupuesto de un mes). Cada corrida usa un usuario propio.
 */

const MONTHS = [
  'enero',
  'febrero',
  'marzo',
  'abril',
  'mayo',
  'junio',
  'julio',
  'agosto',
  'septiembre',
  'octubre',
  'noviembre',
  'diciembre',
];

/** El mes actual corrido `offset` meses. */
function month(offset: number): Date {
  const date = new Date();
  date.setDate(1);
  date.setMonth(date.getMonth() + offset);
  return date;
}

const two = (n: number) => String(n).padStart(2, '0');
/** `YYYY-MM`, como lo espera el campo de período. */
const periodValue = (date: Date) => `${date.getFullYear()}-${two(date.getMonth() + 1)}`;
/** "noviembre 2026", como lo muestra la pantalla. */
const periodText = (date: Date) => `${MONTHS[date.getMonth()]} ${date.getFullYear()}`;
/** `dd/MM/yyyy` del día `day` de ese mes. */
const dateText = (date: Date, day: number) => `${two(day)}/${two(date.getMonth() + 1)}/${date.getFullYear()}`;

async function chooseOption(page: Page, label: string, option: string) {
  await page.getByLabel(label, { exact: true }).click();
  await page.getByRole('option', { name: option, exact: true }).click();
}

async function fillItem(
  page: Page,
  item: { name: string; kind: string; account: string; category?: string; dueDay: string; amount: string },
) {
  await page.getByLabel('Nombre', { exact: true }).fill(item.name);
  await chooseOption(page, 'Tipo', item.kind);
  await chooseOption(page, 'Cuenta por defecto', item.account);
  if (item.category) {
    await chooseOption(page, 'Categoría', item.category);
  }
  await page.getByLabel('Día de vencimiento').fill(item.dueDay);
  await page.getByLabel('Monto vigente').fill(item.amount);
}

const summary = (page: Page) => page.getByRole('status', { name: 'Concepto creado' });

test.describe('HU-10 · crear un Concepto recurrente', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let api: APIRequestContext;
  let accountId: number;
  let categoryId: number;

  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    const { token } = await startSession(page, playwright);
    // La cuenta y la categoría se cargan por la API: sus pantallas ya las cubre la épica 2.
    api = await playwright.request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${token}` },
    });
    const account = await api.post('/api/accounts', {
      data: {
        name: 'Banco Nación',
        type: 'BANK',
        currency: 'ARS',
        openingDate: `${startPeriod()}-01`,
        initialBalance: 0,
      },
    });
    expect(account.status(), 'alta de la cuenta de prueba').toBe(201);
    const category = await api.post('/api/categories', { data: { name: 'Impuestos' } });
    expect(category.status(), 'alta de la categoría de prueba').toBe(201);
    accountId = (await account.json()).id;
    categoryId = (await category.json()).id;
    await page.goto('/conceptos/nuevo');
  });

  test.afterAll(async () => {
    await api.dispose();
    await page.close();
  });

  test('el formulario sugiere el mes actual y explica el desfase y las reglas de estimación', async () => {
    await expect(page.getByRole('heading', { level: 1, name: 'Nuevo Concepto' })).toBeVisible();
    await expect(page.getByLabel('Período de inicio')).toHaveValue(periodValue(month(0)));
    await expect(
      page.getByRole('checkbox', {
        name: 'Vence el mes anterior al período, por ejemplo un sueldo que se cobra a fin del mes anterior',
      }),
    ).not.toBeChecked();
    const rules = page.getByRole('radiogroup', { name: 'Regla de estimación' });
    await expect(rules.getByRole('radio', { name: /Último valor/ })).toBeChecked();
    await expect(rules).toContainText('las siguientes toman su monto real');
    await expect(rules).toContainText('el promedio de las últimas 3 consolidadas');
  });

  test('alta de un Concepto mensual: genera una partida por mes hasta el horizonte', async () => {
    await fillItem(page, {
      name: 'Monotributo',
      kind: 'Gasto',
      account: 'Banco Nación · $ (ARS)',
      category: 'Impuestos',
      dueDay: '20',
      amount: '85.000,50',
    });
    await page.getByRole('button', { name: 'Crear Concepto' }).click();

    await expect(summary(page)).toContainText('Concepto «Monotributo» creado.');
    // El mes actual y los 24 siguientes.
    await expect(summary(page)).toContainText(
      `Se generaron 25 partidas, de ${periodText(month(0))} a ${periodText(month(24))}.`,
    );
    await expect(summary(page)).toContainText(`Primer vencimiento: ${dateText(month(0), 20)}.`);
    // El formulario queda listo para otro Concepto.
    await expect(page.getByLabel('Nombre', { exact: true })).toHaveValue('');
    await expect(page.getByLabel('Monto vigente')).toHaveValue('');
  });

  test('alta de un Concepto con desfase: la primera partida vence el mes anterior a su período', async () => {
    await fillItem(page, { name: 'Sueldo A', kind: 'Ingreso', account: 'Banco Nación · $ (ARS)', dueDay: '25', amount: '1200000' });
    await page.getByRole('checkbox', { name: /Vence el mes anterior al período/ }).check();
    await page.getByLabel('Período de inicio').fill(periodValue(month(1)));
    await page.getByLabel('Período de fin').fill(periodValue(month(3)));
    await page.getByRole('button', { name: 'Crear Concepto' }).click();

    await expect(summary(page)).toContainText('Concepto «Sueldo A» creado.');
    await expect(summary(page)).toContainText(
      `Se generaron 3 partidas, de ${periodText(month(1))} a ${periodText(month(3))}.`,
    );
    // El período es el mes que viene; con desfase, vence el 25 de este mes.
    await expect(summary(page)).toContainText(`Primer vencimiento: ${dateText(month(0), 25)}.`);
  });

  test('un período de inicio anterior al primer período abierto: muestra el error y no crea nada', async () => {
    await fillItem(page, { name: 'Alquiler', kind: 'Gasto', account: 'Banco Nación · $ (ARS)', dueDay: '10', amount: '450000' });
    // El período inicial del usuario es dos meses antes del actual; tres meses antes no existe.
    await page.getByLabel('Período de inicio').fill(periodValue(month(-3)));
    await page.getByRole('button', { name: 'Crear Concepto' }).click();

    await expect(page.getByRole('alert')).toHaveText(
      'Ese período no está disponible: tiene que estar entre tu primer período abierto y el horizonte.',
    );
    await expect(summary(page)).not.toContainText('Alquiler');
    await expect(page.getByLabel('Nombre', { exact: true })).toHaveValue('Alquiler');
  });

  test('un período de fin anterior al de inicio: muestra el motivo que da el backend', async () => {
    await page.getByLabel('Período de inicio').fill(periodValue(month(2)));
    await page.getByLabel('Período de fin').fill(periodValue(month(1)));
    await page.getByRole('button', { name: 'Crear Concepto' }).click();

    await expect(page.getByRole('alert')).toHaveText('El período de fin no puede ser anterior al período de inicio.');
    await expect(summary(page)).not.toContainText('Alquiler');
  });

  test('corregido el fin, el mismo formulario crea el Concepto con una sola partida', async () => {
    await page.getByLabel('Período de fin').fill(periodValue(month(2)));
    await page.getByRole('button', { name: 'Crear Concepto' }).click();

    await expect(summary(page)).toContainText('Concepto «Alquiler» creado.');
    await expect(summary(page)).toContainText(`Se generó 1 partida, en ${periodText(month(2))}.`);
    await expect(summary(page)).toContainText(`Primer vencimiento: ${dateText(month(2), 10)}.`);
    await expect(page.getByRole('alert')).toHaveCount(0);
  });

  // Pendiente de HU-07 y HU-09: sus verificaciones de uso no se podían probar sin Conceptos reales.
  test('la cuenta y la categoría que usa un Concepto ya no se pueden eliminar', async () => {
    const account = await api.delete(`/api/accounts/${accountId}`);
    expect(account.status()).toBe(409);
    expect((await account.json()).code).toBe('ACCOUNT_IN_USE');

    const category = await api.delete(`/api/categories/${categoryId}`);
    expect(category.status()).toBe(409);
    expect((await category.json()).code).toBe('CATEGORY_IN_USE');
  });

  test('una cuenta o una categoría que no existen para el usuario responden 400 con el campo', async () => {
    const body = {
      name: 'Luz',
      kind: 'EXPENSE',
      defaultAccountId: accountId,
      categoryId,
      periodicity: 'MONTHLY',
      dueDay: 10,
      dueMonthOffset: 0,
      startPeriod: periodValue(month(0)),
      estimationRule: 'LAST_VALUE',
      currentAmount: 45000,
    };
    for (const [field, data] of [
      ['defaultAccountId', { ...body, defaultAccountId: 999_999_999 }],
      ['categoryId', { ...body, categoryId: 999_999_999 }],
    ] as const) {
      const response = await api.post('/api/budget-items', { data });
      expect(response.status(), field).toBe(400);
      const problem = await response.json();
      expect(problem.code).toBe('VALIDATION_ERROR');
      expect(problem.errors[0].field).toBe(field);
    }
  });
});

test.describe('HU-11 · crear un Concepto en cuotas', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let api: APIRequestContext;

  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    const { token } = await startSession(page, playwright);
    api = await playwright.request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${token}` },
    });
    const account = await api.post('/api/accounts', {
      data: {
        name: 'Banco Nación',
        type: 'BANK',
        currency: 'ARS',
        openingDate: `${startPeriod()}-01`,
        initialBalance: 0,
      },
    });
    expect(account.status(), 'alta de la cuenta de prueba').toBe(201);
    await page.goto('/conceptos/nuevo');
  });

  test.afterAll(async () => {
    await api.dispose();
    await page.close();
  });

  const installmentsBox = () => page.getByRole('checkbox', { name: 'Es en cuotas' });

  test('«Es en cuotas» muestra el total y la primera cuota, oculta el fin y lo explica', async () => {
    await expect(page.getByLabel('Período de fin')).toBeVisible();
    await expect(page.getByLabel('Total de cuotas')).toHaveCount(0);

    await installmentsBox().check();

    await expect(page.getByLabel('Total de cuotas')).toBeVisible();
    await expect(page.getByLabel('Primera cuota')).toHaveValue('1');
    await expect(page.getByLabel('Período de fin')).toHaveCount(0);
    await expect(page.getByText('El período de fin no se ingresa: se calcula')).toBeVisible();
    await expect(page.getByText('La primera cuota sirve para cargar un plan que ya empezó')).toBeVisible();
  });

  test('una primera cuota mayor que el total: muestra el error del backend y conserva lo cargado', async () => {
    await fillItem(page, { name: 'Heladera', kind: 'Gasto', account: 'Banco Nación · $ (ARS)', dueDay: '10', amount: '85.000' });
    await page.getByLabel('Total de cuotas').fill('12');
    await page.getByLabel('Primera cuota').fill('13');
    await page.getByRole('button', { name: 'Crear Concepto' }).click();

    await expect(page.getByRole('alert')).toHaveText('La primera cuota no puede ser mayor que el total de cuotas.');
    await expect(summary(page)).toHaveCount(0);
    await expect(page.getByLabel('Nombre', { exact: true })).toHaveValue('Heladera');
    await expect(page.getByLabel('Total de cuotas')).toHaveValue('12');
  });

  test('el ejemplo de la Heladera: 12 cuotas, primera 4, genera las cuotas 4 a 12 y el resumen lo dice', async () => {
    await page.getByLabel('Primera cuota').fill('4');
    await page.getByRole('button', { name: 'Crear Concepto' }).click();

    await expect(summary(page)).toContainText('Concepto «Heladera» creado.');
    // Desde el mes actual, nueve meses: la cuota 4 es la de este mes y la 12, la de dentro de ocho.
    await expect(summary(page)).toContainText(
      `Se generaron 9 partidas, de ${periodText(month(0))} a ${periodText(month(8))}.`,
    );
    await expect(summary(page)).toContainText(`Primer vencimiento: ${dateText(month(0), 10)}.`);
    await expect(summary(page)).toContainText('Son las cuotas 4 a 12 de 12.');
    await expect(summary(page)).not.toContainText('El plan termina');
    await expect(page.getByRole('alert')).toHaveCount(0);
    // El formulario queda listo para otro Concepto, sin cuotas.
    await expect(page.getByLabel('Nombre', { exact: true })).toHaveValue('');
    await expect(installmentsBox()).not.toBeChecked();
    await expect(page.getByLabel('Período de fin')).toBeVisible();
  });

  test('un plan que termina después del horizonte genera solo las cuotas que entran y avisa cuándo termina', async () => {
    await fillItem(page, { name: 'Auto', kind: 'Gasto', account: 'Banco Nación · $ (ARS)', dueDay: '5', amount: '300.000' });
    await installmentsBox().check();
    await page.getByLabel('Total de cuotas').fill('60');
    await page.getByRole('button', { name: 'Crear Concepto' }).click();

    await expect(summary(page)).toContainText('Concepto «Auto» creado.');
    await expect(summary(page)).toContainText(
      `Se generaron 25 partidas, de ${periodText(month(0))} a ${periodText(month(24))}.`,
    );
    await expect(summary(page)).toContainText('Son las cuotas 1 a 25 de 60.');
    await expect(summary(page)).toContainText(
      `El plan termina en ${periodText(month(59))}; las demás cuotas se generan a medida que avance el horizonte.`,
    );
  });

  test('por la API: primera cuota sin total y cuotas con fin informado responden 400 con el campo', async () => {
    const body = {
      name: 'Heladera',
      kind: 'EXPENSE',
      defaultAccountId: (await (await api.get('/api/accounts')).json()).accounts[0].id,
      periodicity: 'MONTHLY',
      dueDay: 10,
      dueMonthOffset: 0,
      startPeriod: periodValue(month(0)),
      estimationRule: 'LAST_VALUE',
      currentAmount: 85000,
    };
    for (const [field, data] of [
      ['installmentsTotal', { ...body, firstInstallmentNumber: 4 }],
      ['endPeriod', { ...body, installmentsTotal: 12, endPeriod: periodValue(month(11)) }],
      ['installmentsTotal', { ...body, installmentsTotal: 361 }],
    ] as const) {
      const response = await api.post('/api/budget-items', { data });
      expect(response.status(), field).toBe(400);
      const problem = await response.json();
      expect(problem.code).toBe('VALIDATION_ERROR');
      expect(problem.errors[0].field).toBe(field);
    }
  });

  test('por la API: el plan bimestral avanza de a dos meses y la respuesta trae el fin calculado', async () => {
    const accountId = (await (await api.get('/api/accounts')).json()).accounts[0].id;
    const response = await api.post('/api/budget-items', {
      data: {
        name: 'Seguro',
        kind: 'EXPENSE',
        defaultAccountId: accountId,
        periodicity: 'BIMONTHLY',
        dueDay: 15,
        dueMonthOffset: 0,
        startPeriod: periodValue(month(0)),
        estimationRule: 'LAST_VALUE',
        currentAmount: 20000,
        installmentsTotal: 6,
        firstInstallmentNumber: 3,
      },
    });
    expect(response.status()).toBe(201);
    const created = await response.json();
    // Cuotas 3 a 6: cuatro partidas, de dos en dos meses.
    expect(created.endPeriod).toBe(periodValue(month(6)));
    expect(created.installmentsTotal).toBe(6);
    expect(created.firstInstallmentNumber).toBe(3);
    expect(created.generation).toMatchObject({ entryCount: 4, firstInstallment: 3, lastInstallment: 6 });
  });
});

test.describe('HU-13 · editar un Concepto', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let api: APIRequestContext;
  let itemId: number;

  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    const { token } = await startSession(page, playwright);
    api = await playwright.request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${token}` },
    });
    for (const [name, currency] of [
      ['Banco Nación', 'ARS'],
      ['Caja en dólares', 'USD'],
    ]) {
      const account = await api.post('/api/accounts', {
        data: { name, type: 'BANK', currency, openingDate: `${startPeriod()}-01`, initialBalance: 0 },
      });
      expect(account.status(), `alta de la cuenta ${name}`).toBe(201);
    }
    await page.goto('/conceptos/nuevo');
  });

  test.afterAll(async () => {
    await api.dispose();
    await page.close();
  });

  const getItem = async () => {
    const response = await api.get(`/api/budget-items/${itemId}`);
    expect(response.status()).toBe(200);
    return response.json();
  };
  const dialog = () => page.getByRole('dialog');
  /** Hace clic y espera la respuesta del PUT: el aviso «guardado» del paso anterior puede seguir en pantalla. */
  const saved = async (click: () => Promise<void>) => {
    const [response] = await Promise.all([
      page.waitForResponse((r) => r.request().method() === 'PUT' && /\/api\/budget-items\/\d+$/.test(r.url())),
      click(),
    ]);
    return response;
  };

  test('se crea un Concepto y desde su resumen se llega a la pantalla de edición', async () => {
    await fillItem(page, {
      name: 'Monotributo',
      kind: 'Gasto',
      account: 'Banco Nación · $ (ARS)',
      dueDay: '20',
      amount: '85.000,50',
    });
    await page.getByRole('button', { name: 'Crear Concepto' }).click();
    await expect(summary(page)).toContainText('Concepto «Monotributo» creado.');

    await summary(page).getByRole('link', { name: 'Editar este Concepto' }).click();

    await expect(page).toHaveURL(/\/conceptos\/\d+\/editar$/);
    itemId = Number(/\/conceptos\/(\d+)\/editar$/.exec(page.url())![1]);
    await expect(page.getByRole('heading', { level: 1, name: 'Editar Concepto' })).toBeVisible();
    await expect(page.getByLabel('Nombre', { exact: true })).toHaveValue('Monotributo');
    await expect(page.getByLabel('Monto vigente')).toHaveValue('85000,50');
    await expect(page.getByLabel('Día de vencimiento')).toHaveValue('20');
  });

  test('los datos que no se pueden editar están deshabilitados y muestran el motivo', async () => {
    for (const label of ['Período de inicio', 'Período de fin']) {
      await expect(page.getByLabel(label), label).toBeDisabled();
    }
    for (const label of ['Tipo', 'Periodicidad']) {
      await expect(page.getByLabel(label, { exact: true }), label).toHaveAttribute('aria-disabled', 'true');
    }
    await expect(page.getByRole('checkbox', { name: 'Es en cuotas' })).toBeDisabled();
    await expect(page.getByText('El tipo no se puede cambiar: para cambiarlo, dá de baja el Concepto y creá otro.')).toBeVisible();
    await expect(page.getByText('La periodicidad no se puede cambiar')).toBeVisible();
    await expect(page.getByText('El período de inicio no se puede cambiar')).toBeVisible();
    await expect(page.getByText('El período de fin no se puede cambiar')).toBeVisible();
    await expect(page.getByText('Las cuotas no se pueden cambiar')).toBeVisible();
    // Lo editable sigue editable.
    await expect(page.getByLabel('Nombre', { exact: true })).toBeEnabled();
    await expect(page.getByLabel('Monto vigente')).toBeEnabled();
  });

  test('la pantalla explica cómo dar de baja el Concepto', async () => {
    await expect(page.getByRole('heading', { name: 'Dar de baja este Concepto' })).toBeVisible();
    await expect(page.getByText('eliminá su partida desde el mes elegido')).toBeVisible();
  });

  test('un cambio de monto vigente pasa por el aviso: cancelarlo no guarda nada', async () => {
    await page.getByLabel('Nombre', { exact: true }).fill('Monotributo categoría C');
    await page.getByLabel('Monto vigente').fill('90.000,50');
    await page.getByRole('button', { name: 'Guardar cambios' }).click();

    await expect(dialog()).toContainText('Cambiar el monto vigente');
    await expect(dialog()).toContainText('de $ 85.000,50 a $ 90.000,50');
    // Recién creado: las 25 partidas están pendientes y ninguna está editada.
    await expect(dialog()).toContainText('Se reemplazará el monto presupuestado de 25 partidas pendientes sin editar');
    await expect(dialog()).toContainText('Las partidas editadas a mano no cambian (hoy no hay ninguna).');
    await dialog().getByRole('button', { name: 'Cancelar' }).click();

    await expect(dialog()).toHaveCount(0);
    const item = await getItem();
    expect(item.name).toBe('Monotributo');
    expect(item.currentAmount).toBe(85000.5);
    // Lo escrito se conserva.
    await expect(page.getByLabel('Monto vigente')).toHaveValue('90.000,50');
  });

  test('confirmado el aviso, se guardan el nombre y el monto vigente', async () => {
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    const response = await saved(() => dialog().getByRole('button', { name: 'Cambiar monto' }).click());

    expect(response.status()).toBe(200);
    await expect(page.getByText('Concepto «Monotributo categoría C» guardado.')).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
    const item = await getItem();
    expect(item.name).toBe('Monotributo categoría C');
    expect(item.currentAmount).toBe(90000.5);
    expect(item.entryCounts).toEqual({ pendingNotManual: 25, pendingManual: 0 });
    await expect(page.getByLabel('Monto vigente')).toHaveValue('90000,50');
  });

  test('cambiar solo el día de vencimiento guarda sin aviso', async () => {
    await page.getByLabel('Día de vencimiento').fill('31');
    const response = await saved(() => page.getByRole('button', { name: 'Guardar cambios' }).click());

    expect(response.status()).toBe(200);
    // Sin cambio de monto no hay aviso: el mensaje «guardado» ya estaba en pantalla por el paso anterior.
    await expect(dialog()).toHaveCount(0);
    await expect(page.getByRole('alert')).toHaveCount(0);
    expect((await getItem()).dueDay).toBe(31);
  });

  test('una cuenta de otra moneda: muestra el error y no guarda nada', async () => {
    await chooseOption(page, 'Cuenta por defecto', 'Caja en dólares · US$ (USD)');
    await page.getByLabel('Nombre', { exact: true }).fill('No debería guardarse');
    const response = await saved(() => page.getByRole('button', { name: 'Guardar cambios' }).click());

    expect(response.status()).toBe(409);
    await expect(page.getByRole('alert')).toHaveText(
      'La cuenta elegida es de otra moneda. Elegí una cuenta en la misma moneda.',
    );
    const item = await getItem();
    expect(item.name).toBe('Monotributo categoría C');
    expect(item.currency).toBe('ARS');
    // Lo escrito se conserva para corregirlo.
    await expect(page.getByLabel('Nombre', { exact: true })).toHaveValue('No debería guardarse');
  });

  test('por la API: cambiar un dato no editable responde 409 FIELD_NOT_EDITABLE y no guarda nada', async () => {
    const current = await getItem();
    const { id: _id, currency: _currency, editability: _editability, entryCounts: _counts, ...body } = current;
    const response = await api.put(`/api/budget-items/${itemId}`, {
      data: { ...body, name: 'Otro nombre', kind: 'INCOME', startPeriod: periodValue(month(1)) },
    });

    expect(response.status()).toBe(409);
    const problem = await response.json();
    expect(problem.code).toBe('FIELD_NOT_EDITABLE');
    expect(problem.detail).toContain('el tipo y el período de inicio');
    expect((await getItem()).name).toBe('Monotributo categoría C');
  });

  test('un Concepto que no existe muestra el error en la pantalla y responde 404 por la API', async () => {
    expect((await api.get('/api/budget-items/999999999')).status()).toBe(404);
    const missing = await api.put('/api/budget-items/999999999', { data: {} });
    expect([400, 404]).toContain(missing.status());

    await page.goto('/conceptos/999999999/editar');

    await expect(page.getByRole('alert')).toHaveText('El Concepto no existe.');
    await expect(page.getByLabel('Nombre', { exact: true })).toHaveCount(0);
  });
});

test.describe('HU-14 · listar Conceptos', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let api: APIRequestContext;
  let taxesId: number;

  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    const { token } = await startSession(page, playwright);
    api = await playwright.request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${token}` },
    });
    const account = await api.post('/api/accounts', {
      data: {
        name: 'Banco Nación',
        type: 'BANK',
        currency: 'ARS',
        openingDate: `${startPeriod()}-01`,
        initialBalance: 0,
      },
    });
    expect(account.status(), 'alta de la cuenta de prueba').toBe(201);
    const category = await api.post('/api/categories', { data: { name: 'Impuestos' } });
    expect(category.status(), 'alta de la categoría de prueba').toBe(201);
    taxesId = (await category.json()).id;
    await page.goto('/conceptos');
  });

  test.afterAll(async () => {
    await api.dispose();
    await page.close();
  });

  const rowOf = (name: string) => page.getByRole('row', { name: new RegExp(name) });
  const table = () => page.getByRole('table', { name: 'Conceptos' });
  /** Hace clic en un filtro y espera la respuesta de la lista, ya filtrada. */
  async function filter(label: string, option: string) {
    const [response] = await Promise.all([
      page.waitForResponse((r) => r.request().method() === 'GET' && /\/api\/budget-items\?/.test(r.url())),
      (async () => {
        await page.getByRole('combobox', { name: label, exact: true }).click();
        await page.getByRole('option', { name: option, exact: true }).click();
      })(),
    ]);
    expect(response.status()).toBe(200);
    // El panel de opciones se cierra con una animación: hasta entonces sigue en el DOM.
    await expect(page.getByRole('listbox')).toHaveCount(0);
  }

  test('sin Conceptos muestra el estado vacío con cómo empezar', async () => {
    await expect(page.getByRole('heading', { level: 1, name: 'Conceptos' })).toBeVisible();
    await expect(page.getByText('Todavía no cargaste ningún Concepto. Empezá con «Nuevo Concepto».')).toBeVisible();
    await expect(table()).toHaveCount(0);
  });

  test('«Nuevo Concepto» lleva al alta y se crean un ingreso, un gasto con categoría y uno en cuotas', async () => {
    await page.getByRole('link', { name: 'Nuevo Concepto' }).click();
    await expect(page).toHaveURL(/\/conceptos\/nuevo$/);

    await fillItem(page, {
      name: 'Sueldo',
      kind: 'Ingreso',
      account: 'Banco Nación · $ (ARS)',
      dueDay: '25',
      amount: '1.200.000',
    });
    await page.getByRole('checkbox', { name: /Vence el mes anterior/ }).check();
    await page.getByRole('button', { name: 'Crear Concepto' }).click();
    await expect(summary(page)).toContainText('Concepto «Sueldo» creado.');

    await fillItem(page, {
      name: 'Monotributo',
      kind: 'Gasto',
      account: 'Banco Nación · $ (ARS)',
      category: 'Impuestos',
      dueDay: '20',
      amount: '85.000,50',
    });
    await page.getByRole('button', { name: 'Crear Concepto' }).click();
    await expect(summary(page)).toContainText('Concepto «Monotributo» creado.');

    await fillItem(page, { name: 'Heladera', kind: 'Gasto', account: 'Banco Nación · $ (ARS)', dueDay: '10', amount: '150.000' });
    await page.getByRole('checkbox', { name: 'Es en cuotas' }).check();
    await page.getByLabel('Total de cuotas').fill('12');
    await page.getByLabel('Primera cuota').fill('4');
    await page.getByRole('button', { name: 'Crear Concepto' }).click();
    await expect(summary(page)).toContainText('Concepto «Heladera» creado.');

    await summary(page).getByRole('link', { name: 'Volver a la lista' }).click();
    await expect(page).toHaveURL(/\/conceptos$/);
  });

  test('por la API se suman uno finalizado, uno por comenzar y un plan de 60 cuotas', async () => {
    const base = {
      kind: 'EXPENSE',
      periodicity: 'MONTHLY',
      dueDay: 5,
      dueMonthOffset: 0,
      estimationRule: 'LAST_VALUE',
      currentAmount: 1000,
    };
    const accounts = await (await api.get('/api/accounts')).json();
    const defaultAccountId = accounts.accounts[0].id;
    for (const extra of [
      { name: 'Gimnasio', startPeriod: periodValue(month(-2)), endPeriod: periodValue(month(-1)) },
      { name: 'Seguro', startPeriod: periodValue(month(1)) },
      // 60 cuotas: el plan termina mucho después del horizonte, y la lista muestra el fin calculado.
      { name: 'Auto', startPeriod: periodValue(month(0)), installmentsTotal: 60 },
    ]) {
      const response = await api.post('/api/budget-items', { data: { ...base, defaultAccountId, ...extra } });
      expect(response.status(), `alta de ${extra.name}`).toBe(201);
    }
    await page.reload();
  });

  test('la lista muestra cada Concepto con su estado y deja los finalizados al final', async () => {
    await expect(table()).toBeVisible();
    const names = await table().locator('tbody tr td:first-child').allTextContents();
    expect(names).toEqual(['Auto', 'Heladera', 'Monotributo', 'Seguro', 'Sueldo', 'Gimnasio']);

    await expect(rowOf('Sueldo')).toContainText('Ingreso');
    await expect(rowOf('Sueldo')).toContainText('día 25, el mes anterior');
    await expect(rowOf('Sueldo')).toContainText('$ 1.200.000,00');
    await expect(rowOf('Monotributo')).toContainText('Impuestos');
    await expect(rowOf('Monotributo')).toContainText('día 20');
    await expect(rowOf('Monotributo')).toContainText('$ 85.000,50');
    await expect(rowOf('Monotributo').getByTestId('status')).toHaveText('Activo');
    // Heladera empieza este mes en la cuota 4: es la cuota 4 de 12 y quedan 8.
    await expect(rowOf('Heladera').getByTestId('status')).toContainText('Cuota 4 de 12, quedan 8');
    // El fin del plan es el calculado: inicio + (12 − 4) meses.
    await expect(rowOf('Heladera').getByTestId('status')).toContainText(`termina en ${periodText(month(8))}`);
    await expect(rowOf('Auto').getByTestId('status')).toContainText('Cuota 1 de 60, quedan 59');
    await expect(rowOf('Auto').getByTestId('status')).toContainText(`termina en ${periodText(month(59))}`);
    await expect(rowOf('Seguro').getByTestId('status')).toContainText('Por comenzar');
    await expect(rowOf('Seguro').getByTestId('status')).toContainText(periodText(month(1)));
    await expect(rowOf('Gimnasio').getByTestId('status')).toContainText('Finalizado');
    await expect(rowOf('Gimnasio').getByTestId('status')).toContainText(periodText(month(-1)));
  });

  test('filtra por tipo, por categoría, sin categoría y combinados', async () => {
    await filter('Tipo', 'Ingreso');
    await expect(table().locator('tbody tr')).toHaveCount(1);
    await expect(rowOf('Sueldo')).toBeVisible();

    await filter('Tipo', 'Gasto');
    await expect(table().locator('tbody tr')).toHaveCount(5);

    await filter('Categoría', 'Impuestos');
    await expect(table().locator('tbody tr')).toHaveCount(1);
    await expect(rowOf('Monotributo')).toBeVisible();

    await filter('Categoría', 'Sin categoría');
    await expect(table().locator('tbody tr')).toHaveCount(4);
    await expect(rowOf('Monotributo')).toHaveCount(0);
  });

  test('un filtro sin resultados muestra otro texto y «Quitar filtros» vuelve a la lista completa', async () => {
    await filter('Tipo', 'Ingreso');
    await filter('Categoría', 'Impuestos');

    await expect(page.getByText('Ningún Concepto coincide con los filtros.')).toBeVisible();
    await expect(page.getByText('Todavía no cargaste ningún Concepto.')).toHaveCount(0);
    await expect(table()).toHaveCount(0);

    await page.getByRole('button', { name: 'Quitar filtros' }).click();
    await expect(table().locator('tbody tr')).toHaveCount(6);
  });

  test('«Editar» de una fila abre la edición del Concepto y se vuelve a la lista', async () => {
    await rowOf('Monotributo').getByRole('link', { name: 'Editar Monotributo' }).click();

    await expect(page).toHaveURL(/\/conceptos\/\d+\/editar$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Editar Concepto' })).toBeVisible();
    await expect(page.getByLabel('Nombre', { exact: true })).toHaveValue('Monotributo');

    await page.getByRole('link', { name: 'Volver a la lista' }).first().click();
    await expect(page).toHaveURL(/\/conceptos$/);
    await expect(table()).toBeVisible();
  });

  test('después de editar el monto vigente, la lista muestra el monto nuevo', async () => {
    await rowOf('Monotributo').getByRole('link', { name: 'Editar Monotributo' }).click();
    await page.getByLabel('Monto vigente').fill('90.000,50');
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Cambiar monto' }).click();
    await expect(page.getByText('Concepto «Monotributo» guardado.')).toBeVisible();

    await page.getByRole('link', { name: 'Volver a la lista' }).first().click();

    await expect(rowOf('Monotributo')).toContainText('$ 90.000,50');
    await expect(rowOf('Monotributo')).not.toContainText('85.000,50');
  });

  test('se llega a «Editar» con el teclado', async () => {
    await page.getByRole('link', { name: 'Editar Heladera' }).focus();
    await page.keyboard.press('Enter');

    await expect(page).toHaveURL(/\/conceptos\/\d+\/editar$/);
    await expect(page.getByLabel('Nombre', { exact: true })).toHaveValue('Heladera');
  });

  test('por la API: una categoría inexistente es 404 y categoría con sin categoría es 400', async () => {
    const missing = await api.get('/api/budget-items?categoryId=999999999');
    expect(missing.status()).toBe(404);
    expect((await missing.json()).code).toBe('NOT_FOUND');

    const both = await api.get(`/api/budget-items?categoryId=${taxesId}&withoutCategory=true`);
    expect(both.status()).toBe(400);
    expect((await both.json()).code).toBe('VALIDATION_ERROR');

    const invalid = await api.get('/api/budget-items?kind=OTRO');
    expect(invalid.status()).toBe(400);
  });

  test('un error del backend en la lista se muestra en pantalla', async () => {
    await page.route(/\/api\/budget-items(\?.*)?$/, (route) =>
      route.fulfill({
        status: 404,
        contentType: 'application/problem+json',
        json: { code: 'NOT_FOUND', detail: 'La categoría no existe.' },
      }),
    );
    await page.goto('/conceptos');

    await expect(page.getByRole('alert')).toHaveText('La categoría no existe.');
    await expect(table()).toHaveCount(0);
    await page.unroute(/\/api\/budget-items(\?.*)?$/);
  });
});

test.describe('HU-15 · ver el presupuesto de un mes', () => {
  test.describe.configure({ mode: 'serial' });

  let page: Page;
  let api: APIRequestContext;

  // Los Conceptos se cargan por la API: sus formularios ya los cubren HU-10 y HU-11. Todos empiezan este mes.
  test.beforeAll(async ({ browser, playwright }) => {
    page = await browser.newPage();
    const { token } = await startSession(page, playwright);
    api = await playwright.request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${token}` },
    });
    const create = async (path: string, data: object) => {
      const response = await api.post(path, { data });
      expect(response.status(), `alta en ${path}: ${JSON.stringify(data)}`).toBe(201);
      return (await response.json()).id as number;
    };
    const account = (name: string, currency: string) =>
      create('/api/accounts', { name, type: 'BANK', currency, openingDate: `${startPeriod()}-01`, initialBalance: 0 });
    const pesos = await account('Banco Nación', 'ARS');
    const dollars = await account('Caja en dólares', 'USD');
    const home = await create('/api/categories', { name: 'Hogar' });
    const item = {
      periodicity: 'MONTHLY',
      dueMonthOffset: 0,
      startPeriod: periodValue(month(0)),
      estimationRule: 'LAST_VALUE',
    };
    // Un ingreso mensual.
    await create('/api/budget-items', {
      ...item, name: 'Sueldo', kind: 'INCOME', defaultAccountId: pesos, dueDay: 5, currentAmount: 1200000,
    });
    // Un gasto con desfase: vence el último día del mes anterior a su período.
    await create('/api/budget-items', {
      ...item, name: 'Alquiler', kind: 'EXPENSE', defaultAccountId: pesos, categoryId: home, dueDay: 31,
      dueMonthOffset: -1, currentAmount: 450000,
    });
    // Un gasto bimestral.
    await create('/api/budget-items', {
      ...item, name: 'Seguro', kind: 'EXPENSE', defaultAccountId: pesos, periodicity: 'BIMONTHLY', dueDay: 15,
      currentAmount: 20000.5,
    });
    // La Heladera: 12 cuotas, primera 4.
    await create('/api/budget-items', {
      ...item, name: 'Heladera', kind: 'EXPENSE', defaultAccountId: pesos, categoryId: home, dueDay: 10,
      currentAmount: 85000, installmentsTotal: 12, firstInstallmentNumber: 4,
    });
    // Un Concepto en dólares.
    await create('/api/budget-items', {
      ...item, name: 'Alquiler cobrado', kind: 'INCOME', defaultAccountId: dollars, dueDay: 28, currentAmount: 1000,
    });
    await page.goto('/presupuesto');
  });

  test.afterAll(async () => {
    await api.dispose();
    await page.close();
  });

  const heading = (offset: number) =>
    page.getByRole('heading', { level: 1, name: `Presupuesto de ${periodText(month(offset))}`, exact: true });
  const section = (name: string) => page.getByRole('region', { name, exact: true });
  const names = (name: string) => section(name).getByTestId('name');
  const row = (sectionName: string, entry: string) =>
    section(sectionName).locator('tbody tr').filter({ has: page.getByRole('rowheader', { name: entry, exact: true }) });
  const totals = (name: string) => section(name).getByTestId('total');
  const results = () => section('Resultado').getByTestId('result');
  const cells = (locator: ReturnType<typeof row>) => locator.locator('th, td');
  /** El último día del mes corrido `offset` meses, como `dd/MM/yyyy`. */
  const lastDay = (offset: number) => {
    const date = month(offset + 1);
    date.setDate(0);
    return dateText(date, date.getDate());
  };

  test('abre en el mes actual, con las partidas en orden de vencimiento y sus datos', async () => {
    await expect(heading(0)).toBeVisible();
    await expect(page).toHaveURL(/\/presupuesto$/);
    await expect(page.getByTestId('current')).toHaveText('Mes actual');
    await expect(page.getByTestId('closed')).toHaveCount(0);

    await expect(names('Ingresos')).toHaveText(['Sueldo', 'Alquiler cobrado']);
    // El Alquiler, con desfase, vence el mes anterior: va primero.
    await expect(names('Gastos')).toHaveText(['Alquiler', 'Heladera', 'Seguro']);

    await expect(cells(row('Gastos', 'Heladera'))).toHaveText([
      dateText(month(0), 10),
      'Heladera',
      'Hogar',
      'Banco Nación',
      'Cuota 4 de 12',
      '$ 85.000,00',
      '$ 0,00',
      '$ 85.000,00',
      'Estimada',
      'Registrar pago Editar monto Eliminar',
    ]);
    await expect(cells(row('Ingresos', 'Alquiler cobrado'))).toHaveText([
      new RegExp(`^\\s*${dateText(month(0), 28)}`),
      'Alquiler cobrado',
      '—',
      'Caja en dólares',
      '—',
      'US$ 1.000,00',
      'US$ 0,00',
      'US$ 1.000,00',
      'Estimada',
      'Registrar cobro Editar monto Eliminar',
    ]);
    await expect(row('Gastos', 'Seguro').getByTestId('budgeted')).toHaveText('$ 20.000,50');
    await expect(row('Gastos', 'Seguro').getByTestId('installment')).toHaveText('—');
  });

  test('la partida con desfase vence el último día del mes anterior y está marcada como vencida', async () => {
    const due = row('Gastos', 'Alquiler').getByTestId('due');

    await expect(due).toContainText(lastDay(-1));
    await expect(due.getByText('Vencida', { exact: true })).toBeVisible();
    // Recién creadas: ninguna partida está editada.
    await expect(page.getByText('Editada', { exact: true })).toHaveCount(0);
  });

  test('los totales y el resultado van separados por moneda', async () => {
    await expect(totals('Ingresos')).toHaveCount(2);
    await expect(cells(totals('Ingresos').nth(0))).toHaveText([
      'Total en pesos', '$ 1.200.000,00', '$ 0,00', '$ 1.200.000,00', '', '',
    ]);
    await expect(cells(totals('Ingresos').nth(1))).toHaveText([
      'Total en dólares', 'US$ 1.000,00', 'US$ 0,00', 'US$ 1.000,00', '', '',
    ]);
    // No hay gastos en dólares: Gastos solo tiene el total en pesos.
    await expect(totals('Gastos')).toHaveCount(1);
    await expect(cells(totals('Gastos'))).toHaveText(['Total en pesos', '$ 555.000,50', '$ 0,00', '$ 555.000,50', '', '']);

    await expect(results()).toHaveCount(2);
    await expect(cells(results().nth(0))).toHaveText(['Pesos', '$ 1.200.000,00', '$ 555.000,50', '$ 644.999,50']);
    await expect(cells(results().nth(1))).toHaveText(['Dólares', 'US$ 1.000,00', 'US$ 0,00', 'US$ 1.000,00']);
  });

  test('«Mes siguiente»: el bimestral no aparece, la Heladera es la cuota 5 y nada está vencido', async () => {
    await page.getByRole('button', { name: 'Mes siguiente' }).click();

    await expect(heading(1)).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`/presupuesto/${periodValue(month(1))}$`));
    await expect(page.getByTestId('current')).toHaveCount(0);
    await expect(names('Gastos')).toHaveText(['Alquiler', 'Heladera']);
    await expect(row('Gastos', 'Heladera').getByTestId('installment')).toHaveText('Cuota 5 de 12');
    await expect(row('Gastos', 'Alquiler').getByTestId('due')).toHaveText(lastDay(0));
    await expect(page.getByText('Vencida', { exact: true })).toHaveCount(0);
    await expect(cells(totals('Gastos'))).toHaveText(['Total en pesos', '$ 535.000,00', '$ 0,00', '$ 535.000,00', '', '']);
  });

  test('dos meses después el bimestral vuelve a aparecer', async () => {
    await page.getByRole('button', { name: 'Mes siguiente' }).click();

    await expect(heading(2)).toBeVisible();
    await expect(names('Gastos')).toHaveText(['Alquiler', 'Heladera', 'Seguro']);
    await expect(row('Gastos', 'Seguro').getByTestId('due')).toHaveText(dateText(month(2), 15));
    await expect(row('Gastos', 'Heladera').getByTestId('installment')).toHaveText('Cuota 6 de 12');
  });

  test('el selector de mes lleva a la última cuota de la Heladera, y al mes siguiente ya no está', async () => {
    await chooseOption(page, 'Mes', periodText(month(8)));

    await expect(heading(8)).toBeVisible();
    await expect(row('Gastos', 'Heladera').getByTestId('installment')).toHaveText('Cuota 12 de 12');

    await page.getByRole('button', { name: 'Mes siguiente' }).click();

    await expect(heading(9)).toBeVisible();
    await expect(names('Gastos')).toHaveText(['Alquiler']);
    await expect(names('Ingresos')).toHaveText(['Sueldo', 'Alquiler cobrado']);
  });

  test('«Hoy» vuelve al mes actual y «Mes anterior» retrocede hasta el período inicial, que está vacío', async () => {
    await page.getByRole('link', { name: 'Hoy' }).click();
    await expect(heading(0)).toBeVisible();
    await expect(page).toHaveURL(/\/presupuesto$/);

    await page.getByRole('button', { name: 'Mes anterior' }).click();
    await expect(heading(-1)).toBeVisible();
    await page.getByRole('button', { name: 'Mes anterior' }).click();
    await expect(heading(-2)).toBeVisible();

    // El período inicial del usuario de prueba: no hay nada antes y los Conceptos empiezan después.
    await expect(page.getByRole('button', { name: 'Mes anterior' })).toBeDisabled();
    await expect(page.getByTestId('empty-month')).toContainText('Este mes no tiene partidas.');
    await expect(section('Ingresos')).toContainText('No hay ingresos en este mes.');
    await expect(section('Gastos')).toContainText('No hay gastos en este mes.');
    await expect(section('Resultado')).toHaveCount(0);
  });

  test('en el horizonte están las partidas generadas y no se puede seguir', async () => {
    await page.goto(`/presupuesto/${periodValue(month(24))}`);

    await expect(heading(24)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Mes siguiente' })).toBeDisabled();
    await expect(names('Ingresos')).toHaveText(['Sueldo', 'Alquiler cobrado']);
    // 24 meses después le toca al bimestral.
    await expect(names('Gastos')).toHaveText(['Alquiler', 'Seguro']);
  });

  test('se recorre con el teclado: el botón conserva el foco al cambiar de mes', async () => {
    // Desde /presupuesto, que es donde abre la aplicación: el primer cambio de mes pasa a /presupuesto/:period.
    await page.goto('/presupuesto');
    await expect(heading(0)).toBeVisible();

    await page.getByRole('button', { name: 'Mes siguiente' }).focus();
    await page.keyboard.press('Enter');
    await expect(heading(1)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Mes siguiente' })).toBeFocused();

    await page.keyboard.press('Enter');
    await expect(heading(2)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Mes siguiente' })).toBeFocused();

    // «Hoy» es el siguiente en el orden de tabulación y vuelve al mes actual.
    await page.keyboard.press('Tab');
    await expect(page.getByRole('link', { name: 'Hoy' })).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(heading(0)).toBeVisible();
    await expect(page).toHaveURL(/\/presupuesto$/);
  });

  test('un período fuera de rango muestra el mensaje y ofrece ir al mes actual', async () => {
    for (const offset of [25, -3]) {
      await page.goto(`/presupuesto/${periodValue(month(offset))}`);

      await expect(page.getByRole('alert')).toContainText('Ese mes no existe en tu presupuesto');
      await expect(page.getByRole('region', { name: 'Ingresos' })).toHaveCount(0);
      await expect(page.getByRole('navigation', { name: 'Meses' })).toHaveCount(0);
    }

    await page.getByRole('link', { name: 'Ir al mes actual' }).click();

    await expect(heading(0)).toBeVisible();
    await expect(names('Gastos')).toHaveText(['Alquiler', 'Heladera', 'Seguro']);
  });

  test('por la API: formato inválido es 400, fuera de rango es 404 y otro usuario no ve estas partidas', async ({
    playwright,
  }) => {
    const invalid = await api.get('/api/periods/2026-13');
    expect(invalid.status()).toBe(400);
    expect((await invalid.json()).code).toBe('VALIDATION_ERROR');

    const outside = await api.get(`/api/periods/${periodValue(month(25))}`);
    expect(outside.status()).toBe(404);
    expect((await outside.json()).code).toBe('NOT_FOUND');

    const view = await (await api.get('/api/periods/current')).json();
    expect(view).toMatchObject({
      period: periodValue(month(0)),
      status: 'OPEN',
      startPeriod: startPeriod(),
      currentPeriod: periodValue(month(0)),
      horizon: periodValue(month(24)),
    });

    // Otro usuario recién creado tiene los mismos meses, vacíos.
    const otherPage = await page.context().browser()!.newPage();
    const other = await startSession(otherPage, playwright);
    await otherPage.close();
    const otherApi = await playwright.request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${other.token}` },
    });
    const otherView = await (await otherApi.get(`/api/periods/${periodValue(month(0))}`)).json();
    expect(otherView.incomes).toEqual([]);
    expect(otherView.expenses).toEqual([]);
    expect(otherView.totals).toEqual([]);
    await otherApi.dispose();
  });
});
