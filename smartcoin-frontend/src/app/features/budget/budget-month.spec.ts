import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, of, Subject, throwError } from 'rxjs';
import { PeriodEntry, PeriodView, PerodosService } from '../../api';
import { provideLocale } from '../../core/locale';
import { BudgetMonth } from './budget-month';
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

  /** Por defecto la API responde el mes pedido, vacío; `/current` responde octubre. */
  async function open(url: string, responses: Record<string, Observable<PeriodView>> = {}) {
    api = {
      getPeriod: vi.fn((period: string) => responses[period] ?? of(at(period))),
      getCurrentPeriod: vi.fn(() => responses['current'] ?? of(at('2026-10'))),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ matcher: budgetMonthMatcher, component: BudgetMonth }]),
        provideLocale(),
        { provide: PerodosService, useValue: api },
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
        'Vencimiento Nombre Categoría Cuenta Cuota Presupuestado Real Pendiente Estado',
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
        ['Total en pesos', '$ 1.850.000,00', '$ 1.200.000,00', '$ 650.000,00', ''],
        ['Total en dólares', 'US$ 1.000,00', 'US$ 0,00', 'US$ 1.000,00', ''],
      ]);
      // Los dólares no tienen gastos: Gastos no muestra una fila en cero.
      expect(totals('Gastos')).toEqual([['Total en pesos', '$ 735.000,00', '$ 470.000,00', '$ 265.000,00', '']]);
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
});
