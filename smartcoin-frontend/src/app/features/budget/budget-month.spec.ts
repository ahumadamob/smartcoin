import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, of, Subject, throwError } from 'rxjs';
import { PeriodEntry, PeriodView, PerodosService } from '../../api';
import { provideLocale } from '../../core/locale';
import { BudgetMonth } from './budget-month';
import { EntryDeleteDialogData, EntryDeleteDialogResult } from './entry-delete-dialog';
import { EntryFormDialogData, EntryFormDialogResult } from './entry-form-dialog';
import { MovementDialogData, MovementDialogResult } from './movement-dialog';
import { budgetMonthMatcher } from './budget-month.matcher';

const entry = (overrides: Partial<PeriodEntry>): PeriodEntry => ({
  id: 1,
  budgetItemId: 31,
  origin: 'RECURRING',
  kind: 'EXPENSE',
  name: 'Luz',
  categoryId: null,
  categoryName: null,
  accountId: 12,
  accountName: 'Banco Nación',
  currency: 'ARS',
  dueDate: '2026-11-18',
  installmentNumber: null,
  installmentsTotal: null,
  budgetedAmount: 45000,
  actualAmount: 0,
  pendingAmount: 45000,
  forecastAmount: 45000,
  status: 'ESTIMATED',
  manual: false,
  overdue: false,
  ...overrides,
});

const side = (entryCount: number, budgeted: number, actual: number, pending: number, forecast: number) => ({
  entryCount,
  budgetedAmount: budgeted,
  actualAmount: actual,
  pendingAmount: pending,
  forecastAmount: forecast,
});

/** El ejemplo del criterio 6 de HU-15, más un ingreso en dólares y la Heladera en cuotas. */
const NOVEMBER: PeriodView = {
  period: '2026-11',
  status: 'OPEN',
  startPeriod: '2026-08',
  currentPeriod: '2026-10',
  horizon: '2028-10',
  incomes: [
    entry({
      id: 1,
      kind: 'INCOME',
      name: 'Sueldo A',
      dueDate: '2026-10-25',
      budgetedAmount: 1200000,
      actualAmount: 1200000,
      pendingAmount: 0,
      forecastAmount: 1200000,
      status: 'CONSOLIDATED',
    }),
    entry({
      id: 2,
      kind: 'INCOME',
      name: 'Sueldo B',
      dueDate: '2026-10-30',
      budgetedAmount: 650000,
      pendingAmount: 650000,
      forecastAmount: 650000,
    }),
    entry({
      id: 3,
      kind: 'INCOME',
      name: 'Alquiler cobrado',
      accountName: 'Caja en dólares',
      currency: 'USD',
      dueDate: '2026-11-05',
      budgetedAmount: 1000,
      pendingAmount: 1000,
      forecastAmount: 1000,
    }),
  ],
  expenses: [
    entry({
      id: 4,
      name: 'Alquiler',
      categoryId: 5,
      categoryName: 'Hogar',
      dueDate: '2026-11-05',
      budgetedAmount: 450000,
      actualAmount: 450000,
      pendingAmount: 0,
      forecastAmount: 450000,
      status: 'CONSOLIDATED',
    }),
    entry({
      id: 5,
      name: 'Resumen Visa',
      dueDate: '2026-11-10',
      budgetedAmount: 240000,
      pendingAmount: 240000,
      forecastAmount: 240000,
      manual: true,
    }),
    entry({
      id: 6,
      name: 'Luz',
      dueDate: '2026-11-18',
      actualAmount: 20000,
      pendingAmount: 25000,
      status: 'PARTIAL',
      overdue: true,
    }),
    entry({
      id: 7,
      name: 'Heladera',
      dueDate: '2026-11-20',
      installmentNumber: 5,
      installmentsTotal: 12,
      budgetedAmount: 0,
      pendingAmount: 0,
      forecastAmount: 0,
    }),
  ],
  totals: [
    {
      currency: 'ARS',
      income: side(2, 1850000, 1200000, 650000, 1850000),
      expense: side(4, 735000, 470000, 265000, 735000),
      result: 1115000,
    },
    { currency: 'USD', income: side(1, 1000, 0, 1000, 1000), expense: side(0, 0, 0, 0, 0), result: 1000 },
  ],
};

const EMPTY_MONTH: PeriodView = { ...NOVEMBER, incomes: [], expenses: [], totals: [] };

const at = (period: string, overrides: Partial<PeriodView> = {}): PeriodView => ({
  ...EMPTY_MONTH,
  period,
  ...overrides,
});

describe('BudgetMonth', () => {
  let harness: RouterTestingHarness;
  let api: { getPeriod: ReturnType<typeof vi.fn>; getCurrentPeriod: ReturnType<typeof vi.fn> };
  /** El diálogo de HU-16 se simula: lo que importa acá es cuándo se abre, con qué, y qué hace la pantalla al cerrarse. */
  let dialog: { open: ReturnType<typeof vi.fn> };
  let dialogResult: EntryFormDialogResult | EntryDeleteDialogResult | MovementDialogResult | undefined;
  let snackBar: { open: ReturnType<typeof vi.fn> };

  /** Por defecto la API responde el mes pedido, vacío; `/current` responde octubre. */
  async function open(url: string, responses: Record<string, Observable<PeriodView>> = {}) {
    api = {
      getPeriod: vi.fn((period: string) => responses[period] ?? of(at(period))),
      getCurrentPeriod: vi.fn(() => responses['current'] ?? of(at('2026-10'))),
    };
    dialogResult = undefined;
    dialog = { open: vi.fn(() => ({ afterClosed: () => of(dialogResult) })) };
    snackBar = { open: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ matcher: budgetMonthMatcher, component: BudgetMonth }]),
        provideLocale(),
        { provide: PerodosService, useValue: api },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    harness = await RouterTestingHarness.create(url);
    await refresh();
  }

  async function refresh() {
    harness.detectChanges();
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  const root = () => harness.routeNativeElement as HTMLElement;
  const text = (el: Element | null | undefined) =>
    (el?.innerHTML ?? '').replace(/<!--[\s\S]*?-->/g, '').replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
  const title = () => text(root().querySelector('h1'));
  const button = (label: string) =>
    Array.from(root().querySelectorAll<HTMLButtonElement>('button')).find((b) => text(b).includes(label))!;
  const section = (label: string) => root().querySelector(`section[aria-label="${label}"]`)!;
  const rows = (label: string) => Array.from(section(label).querySelectorAll('tbody tr'));
  const cells = (row: Element) => Array.from(row.querySelectorAll('th, td')).map((cell) => text(cell));
  const totals = (label: string) =>
    Array.from(section(label).querySelectorAll('[data-testid="total"]')).map((row) => cells(row));
  const url = () => TestBed.inject(Router).url;

  describe('navegación', () => {
    it('sin período en la URL pide el actual al backend y lo muestra en palabras', async () => {
      await open('/presupuesto');

      expect(api.getCurrentPeriod).toHaveBeenCalledTimes(1);
      expect(api.getPeriod).not.toHaveBeenCalled();
      expect(title()).toBe('Presupuesto de octubre 2026');
      expect(root().querySelector('[data-testid="current"]')).not.toBeNull();
    });

    it('con período en la URL pide ese mes', async () => {
      await open('/presupuesto/2026-11');

      expect(api.getPeriod).toHaveBeenCalledWith('2026-11');
      expect(api.getCurrentPeriod).not.toHaveBeenCalled();
      expect(title()).toBe('Presupuesto de noviembre 2026');
      expect(root().querySelector('[data-testid="current"]')).toBeNull();
    });

    it('«Mes siguiente» y «Mes anterior» cambian la URL y piden el mes nuevo', async () => {
      await open('/presupuesto/2026-12');

      button('Mes siguiente').click();
      await refresh();
      expect(url()).toBe('/presupuesto/2027-01');
      expect(api.getPeriod).toHaveBeenLastCalledWith('2027-01');
      expect(title()).toBe('Presupuesto de enero 2027');

      button('Mes anterior').click();
      await refresh();
      button('Mes anterior').click();
      await refresh();
      expect(url()).toBe('/presupuesto/2026-11');
      expect(title()).toBe('Presupuesto de noviembre 2026');
    });

    it('en el período inicial no se puede ir al mes anterior', async () => {
      await open('/presupuesto/2026-08');

      expect(button('Mes anterior').disabled).toBe(true);
      expect(button('Mes siguiente').disabled).toBe(false);
    });

    it('en el horizonte no se puede ir al mes siguiente', async () => {
      await open('/presupuesto/2028-10');

      expect(button('Mes siguiente').disabled).toBe(true);
      expect(button('Mes anterior').disabled).toBe(false);
    });

    it('el selector ofrece todos los meses del rango y navega al elegido', async () => {
      await open('/presupuesto/2026-11');
      const component = harness.routeDebugElement!.componentInstance as {
        periods(): string[];
        go(period: string): void;
      };

      expect(component.periods()).toHaveLength(27);
      expect(component.periods()[0]).toBe('2026-08');
      expect(component.periods().at(-1)).toBe('2028-10');

      component.go('2027-06');
      await refresh();
      expect(url()).toBe('/presupuesto/2027-06');
      expect(title()).toBe('Presupuesto de junio 2027');
    });

    it('«Hoy» lleva a /presupuesto, que vuelve a pedir el período actual', async () => {
      await open('/presupuesto/2027-03');
      const today = Array.from(root().querySelectorAll('a')).find((a) => text(a) === 'Hoy')!;

      today.click();
      await refresh();

      expect(url()).toBe('/presupuesto');
      expect(api.getCurrentPeriod).toHaveBeenCalledTimes(1);
      expect(title()).toBe('Presupuesto de octubre 2026');
    });

    it('de /presupuesto a un mes y de vuelta con «Hoy» no recrea la pantalla: el foco del teclado se conserva', async () => {
      await open('/presupuesto');
      const instance = harness.routeDebugElement!.componentInstance;
      const next = button('Mes siguiente');
      next.focus();

      next.click();
      await refresh();

      expect(url()).toBe('/presupuesto/2026-11');
      expect(harness.routeDebugElement!.componentInstance).toBe(instance);
      expect(document.activeElement).toBe(button('Mes siguiente'));

      Array.from(root().querySelectorAll('a')).find((a) => text(a) === 'Hoy')!.click();
      await refresh();
      expect(url()).toBe('/presupuesto');
      expect(harness.routeDebugElement!.componentInstance).toBe(instance);
    });

    it.each(['/presupuesto/2026-11/extra', '/presupuestos', '/otra'])('%s no es la pantalla del mes', async (path) => {
      await open('/presupuesto');

      await expect(TestBed.inject(Router).navigateByUrl(path)).rejects.toThrow();
    });

    it('mientras carga el mes siguiente conserva la navegación y avisa', async () => {
      const pending = new Subject<PeriodView>();
      await open('/presupuesto/2026-11', { '2026-12': pending });

      button('Mes siguiente').click();
      await refresh();

      expect(title()).toBe('Presupuesto de diciembre 2026');
      expect(text(root())).toContain('Cargando el mes…');
      expect(button('Mes siguiente')).toBeDefined();
      expect(root().querySelector('section')).toBeNull();

      pending.next(at('2026-12'));
      await refresh();
      expect(text(root())).not.toContain('Cargando el mes…');
    });
  });

  describe('período fuera de rango', () => {
    const notFound = throwError(
      () => new HttpErrorResponse({ status: 404, error: { code: 'NOT_FOUND', detail: 'El período 2030-01 no existe.' } }),
    );

    it('un 404 muestra el mensaje y ofrece ir al mes actual, sin navegación ni tablas', async () => {
      await open('/presupuesto/2030-01', { '2030-01': notFound });

      const problem = root().querySelector('[role="alert"]')!;
      expect(text(problem)).toContain('Ese mes no existe en tu presupuesto');
      const link = problem.querySelector('a')!;
      expect(text(link)).toBe('Ir al mes actual');
      expect(root().querySelector('nav')).toBeNull();
      expect(root().querySelector('section')).toBeNull();

      link.click();
      await refresh();
      expect(url()).toBe('/presupuesto');
      expect(title()).toBe('Presupuesto de octubre 2026');
    });

    it('una URL que no es un mes no llama a la API y ofrece ir al mes actual', async () => {
      await open('/presupuesto/noviembre');

      expect(api.getPeriod).not.toHaveBeenCalled();
      expect(text(root().querySelector('[role="alert"]'))).toContain('no corresponde a un mes válido');
      expect(text(root().querySelector('[role="alert"] a'))).toBe('Ir al mes actual');
    });

    it('otro error de la API muestra su mensaje sin ofrecer el mes actual', async () => {
      await open('/presupuesto/2026-11', {
        '2026-11': throwError(() => new HttpErrorResponse({ status: 0 })),
      });

      const problem = root().querySelector('[role="alert"]')!;
      expect(text(problem)).toContain('No se pudo conectar con el servidor');
      expect(problem.querySelector('a')).toBeNull();
    });
  });

  describe('partidas y marcas', () => {
    beforeEach(() => open('/presupuesto/2026-11', { '2026-11': of(NOVEMBER) }));

    it('muestra dos secciones con sus columnas, en el orden del backend', () => {
      expect(text(section('Ingresos').querySelector('thead'))).toBe(
        'Vencimiento Nombre Categoría Cuenta Cuota Presupuestado Real Pendiente Estado Acciones',
      );
      expect(rows('Ingresos').map((row) => cells(row)[1])).toEqual(['Sueldo A', 'Sueldo B', 'Alquiler cobrado']);
      expect(rows('Gastos').map((row) => cells(row)[1])).toEqual(['Alquiler', 'Resumen Visa', 'Luz', 'Heladera']);
      expect(cells(rows('Gastos')[0])).toEqual([
        '05/11/2026',
        'Alquiler',
        'Hogar',
        'Banco Nación',
        '—',
        '$ 450.000,00',
        '$ 450.000,00',
        '$ 0,00',
        'Consolidada',
        '',
      ]);
    });

    it('muestra el estado en palabras, la cuota y los dólares con su símbolo', () => {
      expect(rows('Ingresos').map((row) => cells(row)[8])).toEqual(['Consolidada', 'Estimada', 'Estimada']);
      expect(cells(rows('Gastos')[2])[8]).toBe('Parcial');
      expect(cells(rows('Gastos')[3])[4]).toBe('Cuota 5 de 12');
      expect(cells(rows('Gastos')[3])[5]).toBe('$ 0,00');
      expect(cells(rows('Ingresos')[2])[5]).toBe('US$ 1.000,00');
    });

    it('marca con texto las partidas vencidas y las editadas, y solo esas', () => {
      const marks = (label: string) =>
        rows(label).map((row) => Array.from(row.querySelectorAll('.mark')).map((mark) => text(mark)));

      expect(marks('Gastos')).toEqual([[], ['Editada'], ['Vencida'], []]);
      expect(marks('Ingresos')).toEqual([[], [], []]);
      expect(cells(rows('Gastos')[2])[0]).toBe('18/11/2026 Vencida');
      expect(cells(rows('Gastos')[1])[5]).toBe('$ 240.000,00 Editada');
    });

    it('un período abierto no dice «Cerrado»', () => {
      expect(root().querySelector('[data-testid="closed"]')).toBeNull();
    });
  });

  describe('totales', () => {
    beforeEach(() => open('/presupuesto/2026-11', { '2026-11': of(NOVEMBER) }));

    it('al pie de cada sección hay un total por moneda con partidas en esa sección', () => {
      expect(totals('Ingresos')).toEqual([
        ['Total en pesos', '$ 1.850.000,00', '$ 1.200.000,00', '$ 650.000,00', '', ''],
        ['Total en dólares', 'US$ 1.000,00', 'US$ 0,00', 'US$ 1.000,00', '', ''],
      ]);
      // Los dólares no tienen gastos: Gastos no muestra una fila en cero.
      expect(totals('Gastos')).toEqual([['Total en pesos', '$ 735.000,00', '$ 470.000,00', '$ 265.000,00', '', '']]);
    });

    it('el resultado muestra todas las monedas del período, cada una por separado', () => {
      const result = Array.from(section('Resultado').querySelectorAll('[data-testid="result"]')).map((row) =>
        cells(row),
      );

      expect(result).toEqual([
        ['Pesos', '$ 1.850.000,00', '$ 735.000,00', '$ 1.115.000,00'],
        ['Dólares', 'US$ 1.000,00', 'US$ 0,00', 'US$ 1.000,00'],
      ]);
    });

    it('un resultado negativo se ve con signo', async () => {
      TestBed.resetTestingModule();
      await open('/presupuesto/2026-11', {
        '2026-11': of({
          ...NOVEMBER,
          incomes: [],
          totals: [{ currency: 'ARS', income: side(0, 0, 0, 0, 0), expense: side(4, 735000, 470000, 265000, 735000), result: -735000 }],
        }),
      });

      expect(cells(section('Resultado').querySelector('[data-testid="result"]')!)).toEqual([
        'Pesos',
        '$ 0,00',
        '$ 735.000,00',
        '-$ 735.000,00',
      ]);
      expect(text(section('Ingresos'))).toContain('No hay ingresos en este mes.');
      expect(totals('Ingresos')).toEqual([]);
    });
  });

  describe('mes vacío y mes cerrado', () => {
    it('un mes sin partidas lo dice, sin totales ni resultado', async () => {
      await open('/presupuesto/2026-11');

      expect(text(root().querySelector('[data-testid="empty-month"]'))).toContain('Este mes no tiene partidas.');
      expect(text(section('Ingresos'))).toContain('No hay ingresos en este mes.');
      expect(text(section('Gastos'))).toContain('No hay gastos en este mes.');
      expect(root().querySelector('table')).toBeNull();
      expect(root().querySelector('section[aria-label="Resultado"]')).toBeNull();
    });

    it('un período cerrado se ve igual, con la indicación «Cerrado»', async () => {
      await open('/presupuesto/2026-08', { '2026-08': of({ ...NOVEMBER, period: '2026-08', status: 'CLOSED' }) });

      expect(text(root().querySelector('[data-testid="closed"]'))).toBe('Cerrado');
      expect(rows('Gastos')).toHaveLength(4);
      expect(totals('Gastos')).toHaveLength(1);
    });
  });

  describe('acciones (HU-16)', () => {
    const oneOff = (overrides: Partial<PeriodEntry> = {}) =>
      entry({
        id: 20,
        budgetItemId: null,
        origin: 'ONE_OFF',
        name: 'Service del auto',
        categoryId: 5,
        categoryName: 'Hogar',
        dueDate: '2026-11-12',
        budgetedAmount: 85000,
        pendingAmount: 85000,
        forecastAmount: 85000,
        ...overrides,
      });
    const withEntries = (status: 'OPEN' | 'CLOSED', expenses: PeriodEntry[]): PeriodView => ({
      ...NOVEMBER,
      status,
      incomes: [],
      expenses,
      totals: [{ currency: 'ARS', income: side(0, 0, 0, 0, 0), expense: side(expenses.length, 0, 0, 0, 0), result: 0 }],
    });
    const editButtons = () => Array.from(root().querySelectorAll<HTMLButtonElement>('[data-testid="edit-entry"]'));
    const addButton = () => root().querySelector<HTMLButtonElement>('[data-testid="add-entry"]');
    const header = (label: string) => text(section(label).querySelector('thead'));

    it('un período abierto tiene «Agregar partida» en la cabecera, junto al título', async () => {
      await open('/presupuesto/2026-11', { '2026-11': of(NOVEMBER) });

      expect(addButton()).not.toBeNull();
      expect(text(addButton())).toBe('Agregar partida');
      expect(root().querySelector('header')!.contains(addButton())).toBe(true);
    });

    it('un período cerrado no tiene botón ni columna de acciones, aunque tenga partidas puntuales', async () => {
      await open('/presupuesto/2026-08', {
        '2026-08': of({ ...withEntries('CLOSED', [oneOff(), oneOff({ id: 21, origin: 'CARRIED_OVER' })]), period: '2026-08' }),
      });

      expect(text(root().querySelector('[data-testid="closed"]'))).toBe('Cerrado');
      expect(addButton()).toBeNull();
      expect(editButtons()).toHaveLength(0);
      expect(header('Gastos')).not.toContain('Acciones');
    });

    it('«Editar» solo está en las partidas sin Concepto y pendientes', async () => {
      await open('/presupuesto/2026-11', {
        '2026-11': of(
          withEntries('OPEN', [
            entry({ id: 10, name: 'Luz' }),
            oneOff({ id: 20 }),
            oneOff({ id: 21, name: 'Saldo pendiente: Luz', origin: 'CARRIED_OVER' }),
            oneOff({ id: 22, name: 'Diferencia de cierre: Banco', origin: 'CLOSING_DIFFERENCE' }),
            oneOff({ id: 23, name: 'Ya consolidada', status: 'CONSOLIDATED' }),
          ]),
        ),
      });

      expect(header('Gastos')).toContain('Acciones');
      const rowOf = (id: number) => section('Gastos').querySelector(`[data-entry-id="${id}"]`)!;
      expect(rowOf(10).querySelector('[data-testid="edit-entry"]')).toBeNull();
      expect(rowOf(20).querySelector('[data-testid="edit-entry"]')).not.toBeNull();
      expect(rowOf(21).querySelector('[data-testid="edit-entry"]')).not.toBeNull();
      expect(rowOf(22).querySelector('[data-testid="edit-entry"]')).not.toBeNull();
      expect(rowOf(23).querySelector('[data-testid="edit-entry"]')).toBeNull();
      expect(editButtons().map((b) => b.getAttribute('aria-label'))).toEqual([
        'Editar Service del auto',
        'Editar Saldo pendiente: Luz',
        'Editar Diferencia de cierre: Banco',
      ]);
    });

    it('una sección sin ninguna acción posible (todo consolidado) no dibuja la columna de acciones vacía', async () => {
      await open('/presupuesto/2026-11', {
        '2026-11': of(withEntries('OPEN', [entry({ id: 10, status: 'CONSOLIDATED' }), oneOff({ id: 11, status: 'CONSOLIDATED' })])),
      });

      expect(header('Gastos')).not.toContain('Acciones');
      expect(editButtons()).toHaveLength(0);
      // El botón del período sí está: no depende de las partidas.
      expect(addButton()).not.toBeNull();
    });

    it('«Agregar partida» abre el diálogo con el mes que se ve y un vencimiento dentro de él', async () => {
      await open('/presupuesto/2026-11', { '2026-11': of(NOVEMBER) });

      addButton()!.click();

      expect(dialog.open).toHaveBeenCalledTimes(1);
      const data = dialog.open.mock.calls[0][1].data as EntryFormDialogData;
      expect(data).toEqual({ period: '2026-11', suggestedDueDate: '2026-11-01' });
    });

    it('en el mes actual sugiere el día de hoy', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date(2026, 9, 8, 12));
      try {
        await open('/presupuesto');

        addButton()!.click();

        const data = dialog.open.mock.calls[0][1].data as EntryFormDialogData;
        expect(data).toEqual({ period: '2026-10', suggestedDueDate: '2026-10-08' });
      } finally {
        vi.useRealTimers();
      }
    });

    it('«Editar» abre el diálogo con la partida', async () => {
      const puntual = oneOff({ id: 20 });
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [puntual])) });

      editButtons()[0].click();

      const data = dialog.open.mock.calls[0][1].data as EntryFormDialogData;
      expect(data.period).toBe('2026-11');
      expect(data.entry).toEqual(puntual);
    });

    const amountButtons = () => Array.from(root().querySelectorAll<HTMLButtonElement>('[data-testid="edit-amount"]'));

    it('«Editar monto» está solo en las recurrentes pendientes (estimadas o parciales) de un período abierto', async () => {
      await open('/presupuesto/2026-11', {
        '2026-11': of(
          withEntries('OPEN', [
            entry({ id: 10, name: 'Luz' }),
            entry({ id: 11, name: 'Gas', status: 'PARTIAL' }),
            entry({ id: 12, name: 'Agua', status: 'CONSOLIDATED' }),
            oneOff({ id: 20 }),
          ]),
        ),
      });

      const rowOf = (id: number) => section('Gastos').querySelector(`[data-entry-id="${id}"]`)!;
      expect(header('Gastos')).toContain('Acciones');
      expect(rowOf(10).querySelector('[data-testid="edit-amount"]')).not.toBeNull();
      expect(rowOf(11).querySelector('[data-testid="edit-amount"]')).not.toBeNull();
      expect(rowOf(12).querySelector('[data-testid="edit-amount"]')).toBeNull();
      // Una partida ofrece una acción u otra, nunca las dos.
      expect(rowOf(10).querySelector('[data-testid="edit-entry"]')).toBeNull();
      expect(rowOf(20).querySelector('[data-testid="edit-amount"]')).toBeNull();
      expect(amountButtons().map((b) => b.getAttribute('aria-label'))).toEqual([
        'Editar monto de Luz',
        'Editar monto de Gas',
      ]);
    });

    it('un período cerrado no ofrece «Editar monto»', async () => {
      await open('/presupuesto/2026-08', {
        '2026-08': of({ ...withEntries('CLOSED', [entry({ id: 10 })]), period: '2026-08' }),
      });

      expect(amountButtons()).toHaveLength(0);
      expect(header('Gastos')).not.toContain('Acciones');
    });

    it('«Editar monto» abre el diálogo en modo solo monto con la partida y el mes', async () => {
      const luz = entry({ id: 10, name: 'Luz' });
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [luz])) });

      amountButtons()[0].click();

      const data = dialog.open.mock.calls[0][1].data as EntryFormDialogData;
      expect(data).toEqual({ period: '2026-11', suggestedDueDate: luz.dueDate, entry: luz, amountOnly: true });
    });

    it('después de editar el monto, vuelve a pedir el mes y el foco queda en «Editar monto» de la partida', async () => {
      const luz = entry({ id: 10, name: 'Luz' });
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [luz])) });
      const edited = entry({ id: 10, name: 'Luz', budgetedAmount: 240000, manual: true });
      const getPeriod = vi.fn().mockReturnValue(of(withEntries('OPEN', [edited])));
      api.getPeriod = getPeriod;
      amountButtons()[0].focus();
      dialogResult = { entry: edited, created: false };

      amountButtons()[0].click();
      await refresh();
      await refresh();

      expect(getPeriod).toHaveBeenCalledWith('2026-11');
      expect(text(section('Gastos').querySelector('[data-entry-id="10"]'))).toContain('Editada');
      expect(document.activeElement).toBe(section('Gastos').querySelector('[data-entry-id="10"] [data-testid="edit-amount"]'));
    });

    it('al guardar vuelve a pedir el mes y muestra lo que responde el backend, sin vaciar la pantalla', async () => {
      const after = withEntries('OPEN', [oneOff({ id: 20 })]);
      await open('/presupuesto/2026-11', { '2026-11': of(NOVEMBER) });
      const getPeriod = vi.fn().mockReturnValue(of(after));
      api.getPeriod = getPeriod;
      const tableBefore = section('Gastos').querySelector('table');
      dialogResult = { entry: oneOff({ id: 20 }), created: true };

      addButton()!.click();
      await refresh();

      expect(getPeriod).toHaveBeenCalledWith('2026-11');
      expect(snackBar.open).toHaveBeenCalledWith('Partida «Service del auto» agregada.', undefined, expect.anything());
      expect(rows('Gastos').map((row) => cells(row)[1])).toEqual(['Service del auto']);
      expect(text(root())).not.toContain('Cargando el mes…');
      // Las tablas no se desmontan: no se pierde el foco ni parpadea la pantalla.
      expect(tableBefore).not.toBeNull();
    });

    it('al editar avisa que la partida se guardó', async () => {
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [oneOff({ id: 20 })])) });
      dialogResult = { entry: oneOff({ id: 20, name: 'Cambio de aceite' }), created: false };

      editButtons()[0].click();
      await refresh();

      expect(snackBar.open).toHaveBeenCalledWith('Partida «Cambio de aceite» guardada.', undefined, expect.anything());
    });

    it('si el diálogo avisa que la pantalla estaba desactualizada, vuelve a pedir el mes', async () => {
      await open('/presupuesto/2026-11', { '2026-11': of(NOVEMBER) });
      const calls = api.getPeriod.mock.calls.length;
      dialogResult = { stale: true };

      addButton()!.click();
      await refresh();

      expect(api.getPeriod.mock.calls.length).toBe(calls + 1);
      expect(snackBar.open).not.toHaveBeenCalled();
    });

    it('si se cancela el diálogo no se vuelve a pedir nada', async () => {
      await open('/presupuesto/2026-11', { '2026-11': of(NOVEMBER) });
      const calls = api.getPeriod.mock.calls.length;
      dialogResult = undefined;

      addButton()!.click();
      await refresh();

      expect(api.getPeriod.mock.calls.length).toBe(calls);
    });

    it('después de editar, el foco queda en el «Editar» de la partida aunque haya cambiado de lugar', async () => {
      const first = oneOff({ id: 20, name: 'Alfa', dueDate: '2026-11-05' });
      const second = oneOff({ id: 21, name: 'Beta', dueDate: '2026-11-20' });
      const moved = oneOff({ id: 20, name: 'Alfa', dueDate: '2026-11-28' });
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [first, second])) });
      api.getPeriod = vi.fn().mockReturnValue(of(withEntries('OPEN', [second, moved])));
      editButtons()[0].focus();
      dialogResult = { entry: moved, created: false };

      editButtons()[0].click();
      await refresh();
      await refresh();

      const target = section('Gastos').querySelector('[data-entry-id="20"] [data-testid="edit-entry"]');
      expect(rows('Gastos').map((row) => cells(row)[1])).toEqual(['Beta', 'Alfa']);
      expect(document.activeElement).toBe(target);
    });
  });
  describe('eliminar (HU-18)', () => {
    const oneOff = (overrides: Partial<PeriodEntry> = {}) =>
      entry({
        id: 20,
        budgetItemId: null,
        origin: 'ONE_OFF',
        name: 'Service del auto',
        dueDate: '2026-11-12',
        budgetedAmount: 85000,
        pendingAmount: 85000,
        forecastAmount: 85000,
        ...overrides,
      });
    const withEntries = (status: 'OPEN' | 'CLOSED', expenses: PeriodEntry[]): PeriodView => ({
      ...NOVEMBER,
      status,
      incomes: [],
      expenses,
      totals: [{ currency: 'ARS', income: side(0, 0, 0, 0, 0), expense: side(expenses.length, 0, 0, 0, 0), result: 0 }],
    });
    const deleteButtons = () => Array.from(root().querySelectorAll<HTMLButtonElement>('[data-testid="delete-entry"]'));
    const rowOf = (id: number) => section('Gastos').querySelector(`[data-entry-id="${id}"]`)!;

    it('«Eliminar» está en toda partida pendiente, con o sin Concepto, y no en las consolidadas', async () => {
      await open('/presupuesto/2026-11', {
        '2026-11': of(
          withEntries('OPEN', [
            entry({ id: 10, name: 'Luz' }),
            entry({ id: 11, name: 'Agua', status: 'PARTIAL', actualAmount: 100 }),
            oneOff({ id: 20 }),
            oneOff({ id: 21, name: 'Saldo pendiente: Luz', origin: 'CARRIED_OVER' }),
            entry({ id: 12, name: 'Gas', status: 'CONSOLIDATED' }),
            oneOff({ id: 22, name: 'Ya consolidada', status: 'CONSOLIDATED' }),
          ]),
        ),
      });

      expect(deleteButtons().map((b) => b.getAttribute('aria-label'))).toEqual([
        'Eliminar Luz',
        'Eliminar Agua',
        'Eliminar Service del auto',
        'Eliminar Saldo pendiente: Luz',
      ]);
      expect(rowOf(12).querySelector('[data-testid="delete-entry"]')).toBeNull();
      expect(rowOf(22).querySelector('[data-testid="delete-entry"]')).toBeNull();
    });

    it('un período cerrado no ofrece «Eliminar»', async () => {
      await open('/presupuesto/2026-08', {
        '2026-08': of({ ...withEntries('CLOSED', [oneOff()]), period: '2026-08' }),
      });

      expect(deleteButtons()).toHaveLength(0);
    });

    it('«Eliminar» abre el diálogo con la partida y el mes que se ve', async () => {
      const luz = entry({ id: 10, name: 'Luz' });
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [luz])) });

      deleteButtons()[0].click();

      expect(dialog.open).toHaveBeenCalledTimes(1);
      const data = dialog.open.mock.calls[0][1].data as EntryDeleteDialogData;
      expect(data).toEqual({ entry: luz, period: '2026-11' });
    });

    it('al eliminar avisa lo que se hizo y vuelve a pedir el mes: la fila ya no está y las tablas siguen montadas', async () => {
      const alfa = oneOff({ id: 20, name: 'Alfa', dueDate: '2026-11-05' });
      const beta = oneOff({ id: 21, name: 'Beta', dueDate: '2026-11-20' });
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [alfa, beta])) });
      const getPeriod = vi.fn().mockReturnValue(of(withEntries('OPEN', [beta])));
      api.getPeriod = getPeriod;
      const tableBefore = section('Gastos').querySelector('table');
      dialogResult = { deleted: true, message: 'Partida «Alfa» eliminada.' };

      deleteButtons()[0].click();
      await refresh();

      expect(getPeriod).toHaveBeenCalledWith('2026-11');
      expect(snackBar.open).toHaveBeenCalledWith('Partida «Alfa» eliminada.', undefined, expect.anything());
      expect(rows('Gastos').map((row) => cells(row)[1])).toEqual(['Beta']);
      expect(section('Gastos').querySelector('table')).toBe(tableBefore);
      expect(text(root())).not.toContain('Cargando el mes…');
    });

    it('el foco pasa a la acción de la fila que ocupa el lugar de la eliminada', async () => {
      const alfa = oneOff({ id: 20, name: 'Alfa', dueDate: '2026-11-05' });
      const beta = oneOff({ id: 21, name: 'Beta', dueDate: '2026-11-20' });
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [alfa, beta])) });
      api.getPeriod = vi.fn().mockReturnValue(of(withEntries('OPEN', [beta])));
      deleteButtons()[0].focus();
      dialogResult = { deleted: true, message: 'ok' };

      deleteButtons()[0].click();
      await refresh();
      await refresh();

      const next = rowOf(21).querySelector('[data-testid="actions"] button');
      expect(document.activeElement).toBe(next);
    });

    it('si eliminó la última fila de la lista, el foco pasa a la anterior', async () => {
      const alfa = oneOff({ id: 20, name: 'Alfa', dueDate: '2026-11-05' });
      const beta = oneOff({ id: 21, name: 'Beta', dueDate: '2026-11-20' });
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [alfa, beta])) });
      api.getPeriod = vi.fn().mockReturnValue(of(withEntries('OPEN', [alfa])));
      deleteButtons()[1].focus();
      dialogResult = { deleted: true, message: 'ok' };

      deleteButtons()[1].click();
      await refresh();
      await refresh();

      expect(document.activeElement).toBe(rowOf(20).querySelector('[data-testid="actions"] button'));
    });

    it('si la sección queda sin partidas, el foco pasa a su encabezado', async () => {
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [oneOff({ id: 20 })])) });
      api.getPeriod = vi.fn().mockReturnValue(of(withEntries('OPEN', [])));
      deleteButtons()[0].focus();
      dialogResult = { deleted: true, message: 'ok' };

      deleteButtons()[0].click();
      await refresh();
      await refresh();

      expect(rows('Gastos')).toHaveLength(0);
      expect(document.activeElement).toBe(section('Gastos').querySelector('h2'));
      expect(text(section('Gastos'))).toContain('No hay gastos en este mes.');
    });

    it('si el diálogo avisa que la pantalla estaba desactualizada, vuelve a pedir el mes sin avisar nada', async () => {
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [oneOff({ id: 20 })])) });
      const calls = api.getPeriod.mock.calls.length;
      dialogResult = { stale: true };

      deleteButtons()[0].click();
      await refresh();

      expect(api.getPeriod.mock.calls.length).toBe(calls + 1);
      expect(snackBar.open).not.toHaveBeenCalled();
    });

    it('si se cancela el diálogo no se vuelve a pedir nada ni se mueve el foco', async () => {
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [oneOff({ id: 20 })])) });
      const calls = api.getPeriod.mock.calls.length;
      deleteButtons()[0].focus();
      dialogResult = undefined;

      deleteButtons()[0].click();
      await refresh();

      expect(api.getPeriod.mock.calls.length).toBe(calls);
      expect(snackBar.open).not.toHaveBeenCalled();
      expect(document.activeElement).toBe(deleteButtons()[0]);
    });
  });

  describe('registrar cobros y pagos (HU-19)', () => {
    const registerButtons = () =>
      Array.from(root().querySelectorAll<HTMLButtonElement>('[data-testid="register-movement"]'));
    const withEntries = (status: 'OPEN' | 'CLOSED', incomes: PeriodEntry[], expenses: PeriodEntry[]): PeriodView => ({
      ...NOVEMBER,
      status,
      incomes,
      expenses,
      totals: [
        { currency: 'ARS', income: side(incomes.length, 0, 0, 0, 0), expense: side(expenses.length, 0, 0, 0, 0), result: 0 },
      ],
    });
    const expensas = (overrides: Partial<PeriodEntry> = {}) =>
      entry({
        id: 30,
        budgetItemId: null,
        origin: 'ONE_OFF',
        name: 'Expensas',
        dueDate: '2026-11-10',
        budgetedAmount: 120000,
        pendingAmount: 120000,
        forecastAmount: 120000,
        ...overrides,
      });
    const registered = (after: PeriodEntry, amount: number): MovementDialogResult => ({
      movement: {
        id: 500,
        entryId: after.id,
        date: '2026-11-05',
        amount,
        note: null,
        accountId: after.accountId,
        accountName: after.accountName,
        currency: after.currency,
      },
      entry: after,
    });

    it('un gasto ofrece «Registrar pago» y un ingreso, «Registrar cobro», en las pendientes de un período abierto', async () => {
      const income = entry({ id: 31, kind: 'INCOME', name: 'Sueldo', budgetItemId: null, origin: 'ONE_OFF' });
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [income], [expensas()])) });

      expect(registerButtons().map((b) => text(b))).toEqual(['Registrar cobro', 'Registrar pago']);
      expect(registerButtons().map((b) => b.getAttribute('aria-label'))).toEqual([
        'Registrar cobro de Sueldo',
        'Registrar pago de Expensas',
      ]);
    });

    it('lo ofrecen las Parciales y las recurrentes, y no las Consolidadas', async () => {
      const partial = expensas({ id: 32, name: 'Parcial', status: 'PARTIAL', actualAmount: 70000, pendingAmount: 50000 });
      const recurring = entry({ id: 33, name: 'Luz' });
      const done = expensas({ id: 34, name: 'Hecha', status: 'CONSOLIDATED', pendingAmount: 0 });
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [], [partial, recurring, done])) });

      const rowsWithAction = rows('Gastos')
        .filter((row) => row.querySelector('[data-testid="register-movement"]'))
        .map((row) => cells(row)[1]);
      expect(rowsWithAction).toEqual(['Parcial', 'Luz']);
    });

    it('un período cerrado no la ofrece', async () => {
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('CLOSED', [], [expensas()])) });

      expect(registerButtons()).toHaveLength(0);
    });

    it('abre el diálogo con la partida y el mes que se ve', async () => {
      const entryToPay = expensas();
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [], [entryToPay])) });

      registerButtons()[0].click();

      expect(dialog.open).toHaveBeenCalledTimes(1);
      const data = dialog.open.mock.calls[0][1].data as MovementDialogData;
      expect(data).toEqual({ entry: entryToPay, period: '2026-11' });
    });

    it('al guardar vuelve a pedir el mes, muestra real y pendiente del backend y no desmonta las tablas', async () => {
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [], [expensas()])) });
      const after = expensas({ status: 'PARTIAL', actualAmount: 70000, pendingAmount: 50000, forecastAmount: 120000 });
      const getPeriod = vi.fn().mockReturnValue(of(withEntries('OPEN', [], [after])));
      api.getPeriod = getPeriod;
      const tableBefore = section('Gastos').querySelector('table');
      dialogResult = registered(after, 70000);

      registerButtons()[0].click();
      await refresh();

      expect(getPeriod).toHaveBeenCalledWith('2026-11');
      expect(snackBar.open).toHaveBeenCalledWith(
        'Pago de $ 70.000,00 registrado en «Expensas».',
        undefined,
        expect.anything(),
      );
      const row = cells(rows('Gastos')[0]);
      expect(row[6]).toContain('70.000,00');
      expect(row[7]).toContain('50.000,00');
      expect(row[8]).toBe('Parcial');
      expect(text(root())).not.toContain('Cargando el mes…');
      expect(section('Gastos').querySelector('table')).toBe(tableBefore);
    });

    it('cuando el pendiente llega a 0, el aviso lo dice con lo que informa el backend y sigue Parcial', async () => {
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [], [expensas()])) });
      const after = expensas({ status: 'PARTIAL', actualAmount: 120000, pendingAmount: 0, forecastAmount: 120000 });
      api.getPeriod = vi.fn().mockReturnValue(of(withEntries('OPEN', [], [after])));
      dialogResult = registered(after, 120000);

      registerButtons()[0].click();
      await refresh();

      const message = snackBar.open.mock.calls[0][0] as string;
      expect(message).toContain('Pago de $ 120.000,00 registrado en «Expensas».');
      expect(message).toContain('«Expensas» ya está cubierta: pendiente $ 0,00. Sigue Parcial hasta que se consolide.');
      expect(cells(rows('Gastos')[0])[8]).toBe('Parcial');
    });

    it('el foco vuelve al «Registrar pago» de la partida', async () => {
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [], [expensas()])) });
      const after = expensas({ status: 'PARTIAL', actualAmount: 70000, pendingAmount: 50000 });
      api.getPeriod = vi.fn().mockReturnValue(of(withEntries('OPEN', [], [after])));
      registerButtons()[0].focus();
      dialogResult = registered(after, 70000);

      registerButtons()[0].click();
      await refresh();
      await refresh();

      expect(document.activeElement).toBe(
        section('Gastos').querySelector('[data-entry-id="30"] [data-testid="register-movement"]'),
      );
    });

    it('si el diálogo avisa que la pantalla estaba desactualizada, vuelve a pedir el mes sin avisar nada', async () => {
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [], [expensas()])) });
      const calls = api.getPeriod.mock.calls.length;
      dialogResult = { stale: true };

      registerButtons()[0].click();
      await refresh();

      expect(api.getPeriod.mock.calls.length).toBe(calls + 1);
      expect(snackBar.open).not.toHaveBeenCalled();
    });

    it('si se cancela el diálogo no se vuelve a pedir nada', async () => {
      await open('/presupuesto/2026-11', { '2026-11': of(withEntries('OPEN', [], [expensas()])) });
      const calls = api.getPeriod.mock.calls.length;
      dialogResult = undefined;

      registerButtons()[0].click();
      await refresh();

      expect(api.getPeriod.mock.calls.length).toBe(calls);
      expect(snackBar.open).not.toHaveBeenCalled();
    });
  });
});
