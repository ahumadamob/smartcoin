import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, of, throwError } from 'rxjs';
import { CierreDeMesService, ClosingPreview } from '../../api';
import { provideLocale } from '../../core/locale';
import { ClosingWizard } from './closing-wizard';

/** Agosto de 2026 (período inicial, terminado): se puede cerrar, con dos pendientes y tres cuentas. */
const AUGUST: ClosingPreview = {
  period: '2026-08',
  status: 'OPEN',
  lastDay: '2026-08-31',
  firstOpenPeriod: '2026-08',
  preconditionsMet: true,
  blockers: [],
  pendingEntries: [
    {
      id: 1,
      name: 'Luz',
      kind: 'EXPENSE',
      accountId: 12,
      accountName: 'Banco',
      currency: 'ARS',
      dueDate: '2026-08-18',
      budgetedAmount: 45000,
      actualAmount: 20000,
      pendingAmount: 25000,
      status: 'PARTIAL',
    },
    {
      id: 2,
      name: 'Alquiler cobrado',
      kind: 'INCOME',
      accountId: 13,
      accountName: 'Dólares',
      currency: 'USD',
      dueDate: '2026-08-20',
      budgetedAmount: 1000,
      actualAmount: 0,
      pendingAmount: 1000,
      status: 'ESTIMATED',
    },
  ],
  accounts: [
    { id: 12, name: 'Banco', currency: 'ARS', openingDate: '2026-08-01', computedBalance: 255000 },
    { id: 14, name: 'Efectivo', currency: 'ARS', openingDate: '2026-08-01', computedBalance: -1500.5 },
    { id: 13, name: 'Dólares', currency: 'USD', openingDate: '2026-08-01', computedBalance: 600 },
  ],
};

/** Octubre de 2026: el anterior abierto y el mes sin terminar. */
const OCTOBER: ClosingPreview = {
  ...AUGUST,
  period: '2026-10',
  lastDay: '2026-10-31',
  preconditionsMet: false,
  blockers: [
    { code: 'PREVIOUS_PERIOD_OPEN', detail: 'El período anterior, 2026-09, todavía está abierto.' },
    { code: 'PERIOD_NOT_FINISHED', detail: 'El período 2026-10 termina el 31/10/2026.' },
  ],
  pendingEntries: [],
};

describe('ClosingWizard', () => {
  let harness: RouterTestingHarness;
  let api: { previewClosing: ReturnType<typeof vi.fn> };

  async function open(url: string, responses: Record<string, Observable<ClosingPreview>> = {}) {
    api = { previewClosing: vi.fn((period: string) => responses[period] ?? of({ ...AUGUST, period })) };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'cierre/:period', component: ClosingWizard },
          { path: 'presupuesto/:period', children: [] },
          { path: 'presupuesto', children: [] },
        ]),
        provideLocale(),
        { provide: CierreDeMesService, useValue: api },
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
  const all = (selector: string) => Array.from(root().querySelectorAll(selector));
  const cells = (row: Element) => Array.from(row.querySelectorAll('th, td')).map((cell) => text(cell));
  const link = (label: string) =>
    Array.from(root().querySelectorAll<HTMLAnchorElement>('a')).find((a) => text(a).includes(label));

  describe('pasos', () => {
    beforeEach(() => open('/cierre/2026-08'));

    it('muestra los tres pasos y el primero es el actual', () => {
      const steps = all('[data-testid="step"]');
      expect(steps.map((s) => text(s))).toEqual([
        '1 Pendientes',
        '2 Saldos Disponible más adelante',
        '3 Confirmación Disponible más adelante',
      ]);
      expect(steps[0].getAttribute('aria-current')).toBe('step');
      expect(steps[0].getAttribute('aria-disabled')).toBeNull();
    });

    it('los pasos de saldos y confirmación están deshabilitados y no tienen contenido ni botones', () => {
      const steps = all('[data-testid="step"]');
      expect(steps.slice(1).map((s) => s.getAttribute('aria-disabled'))).toEqual(['true', 'true']);
      expect(steps.slice(1).every((s) => s.querySelector('a, button') === null)).toBe(true);
      expect(all('button')).toHaveLength(0);
    });
  });

  describe('se puede cerrar', () => {
    beforeEach(() => open('/cierre/2026-08'));

    it('pide la simulación del período de la URL y lo dice', () => {
      expect(api.previewClosing).toHaveBeenCalledWith('2026-08');
      expect(text(root().querySelector('h1'))).toBe('Cierre de agosto 2026');
      expect(text(root().querySelector('[data-testid="summary"]'))).toBe('Se puede cerrar el mes.');
      expect(all('[data-testid="blocker"]')).toHaveLength(0);
    });

    it('lista las partidas pendientes con real y pendiente, en su moneda', () => {
      const rows = all('[data-testid="pending-entries"] tbody tr').map(cells);
      expect(rows).toEqual([
        ['18/08/2026', 'Luz', 'Gasto', 'Banco', '$ 45.000,00', '$ 20.000,00', '$ 25.000,00', 'Parcial'],
        ['20/08/2026', 'Alquiler cobrado', 'Ingreso', 'Dólares', 'US$ 1.000,00', 'US$ 0,00', 'US$ 1.000,00', 'Estimada'],
      ]);
    });

    it('lista las cuentas agrupadas por moneda con el saldo calculado al último día, sin subtotales', () => {
      expect(text(root().querySelector('.hint'))).toContain('Saldo calculado al 31/08/2026');
      const groups = all('[data-testid="account-group"]');
      expect(groups.map((g) => text(g.querySelector('caption')))).toEqual(['Pesos', 'Dólares']);
      expect(Array.from(groups[0].querySelectorAll('tbody tr')).map(cells)).toEqual([
        ['Banco', '$ 255.000,00'],
        ['Efectivo', '-$ 1.500,50'],
      ]);
      expect(Array.from(groups[1].querySelectorAll('tbody tr')).map(cells)).toEqual([['Dólares', 'US$ 600,00']]);
      expect(groups[0].querySelector('.negative')).not.toBeNull();
      expect(root().textContent).not.toContain('Total');
      expect(root().textContent).not.toContain('Subtotal');
    });

    it('vuelve al presupuesto del mes', () => {
      expect(link('Volver al presupuesto del mes')!.getAttribute('href')).toBe('/presupuesto/2026-08');
    });
  });

  describe('motivos', () => {
    it('con el anterior abierto y el mes sin terminar muestra los dos, en orden, con el enlace al primer abierto', async () => {
      await open('/cierre/2026-10', { '2026-10': of(OCTOBER) });

      expect(text(root().querySelector('[data-testid="summary"]'))).toBe('Todavía no se puede cerrar el mes.');
      expect(all('[data-testid="blocker"]').map((b) => b.getAttribute('data-code'))).toEqual([
        'PREVIOUS_PERIOD_OPEN',
        'PERIOD_NOT_FINISHED',
      ]);
      const [previous, notFinished] = all('[data-testid="blocker"]');
      expect(text(previous)).toBe('El mes anterior todavía está abierto. Ir a cerrar agosto 2026');
      expect(text(notFinished)).toBe('El mes termina el 31/10: se puede cerrar desde ese día.');
      expect(link('Ir a cerrar')!.getAttribute('href')).toBe('/cierre/2026-08');
    });

    it('bloqueado igual muestra lo que falta: pendientes y cuentas', async () => {
      await open('/cierre/2026-10', { '2026-10': of(OCTOBER) });

      expect(text(root().querySelector('[data-testid="no-pending"]'))).toBe('No hay partidas pendientes en este mes.');
      expect(all('[data-testid="account-group"]')).toHaveLength(2);
    });

    it('solo el mes sin terminar: sin enlace', async () => {
      await open('/cierre/2026-10', {
        '2026-10': of({ ...OCTOBER, blockers: [OCTOBER.blockers[1]] }),
      });

      expect(all('[data-testid="blocker"]').map((b) => text(b))).toEqual([
        'El mes termina el 31/10: se puede cerrar desde ese día.',
      ]);
      expect(link('Ir a cerrar')).toBeUndefined();
    });

    it('un mes cerrado lo dice y no lista nada', async () => {
      await open('/cierre/2026-08', {
        '2026-08': of({
          ...AUGUST,
          status: 'CLOSED',
          preconditionsMet: false,
          firstOpenPeriod: '2026-09',
          blockers: [{ code: 'PERIOD_CLOSED', detail: 'El período 2026-08 ya está cerrado.' }],
          pendingEntries: [],
          accounts: [],
        }),
      });

      expect(all('[data-testid="blocker"]').map((b) => text(b))).toEqual(['El mes ya está cerrado.']);
      expect(root().querySelector('[data-testid="pending-entries"]')).toBeNull();
      expect(root().querySelector('[data-testid="no-pending"]')).toBeNull();
      expect(all('[data-testid="account-group"]')).toHaveLength(0);
    });

    it('un código desconocido muestra la explicación del backend', async () => {
      await open('/cierre/2026-10', {
        '2026-10': of({
          ...OCTOBER,
          blockers: [{ code: 'OTRO' as ClosingPreview['blockers'][number]['code'], detail: 'Explicación del backend.' }],
        }),
      });

      expect(all('[data-testid="blocker"]').map((b) => text(b))).toEqual(['Explicación del backend.']);
    });

    it('«Ir a cerrar» lleva al primer mes abierto, pide su simulación y pone el foco en el título', async () => {
      await open('/cierre/2026-10', { '2026-10': of(OCTOBER) });

      link('Ir a cerrar')!.click();
      await refresh();

      expect(TestBed.inject(Router).url).toBe('/cierre/2026-08');
      expect(api.previewClosing).toHaveBeenLastCalledWith('2026-08');
      expect(text(root().querySelector('h1'))).toBe('Cierre de agosto 2026');
      expect(document.activeElement).toBe(root().querySelector('h1'));
    });
  });

  describe('errores', () => {
    it('una dirección que no es un mes no llama a la API', async () => {
      await open('/cierre/agosto');

      expect(api.previewClosing).not.toHaveBeenCalled();
      expect(text(root().querySelector('[role="alert"]'))).toContain('La dirección no corresponde a un mes válido.');
      expect(link('Ir al mes actual')).toBeDefined();
    });

    it('un mes fuera de rango (404) lo dice y ofrece ir al mes actual', async () => {
      await open('/cierre/2030-01', {
        '2030-01': throwError(() => new HttpErrorResponse({ status: 404, error: { code: 'NOT_FOUND' } })),
      });

      expect(text(root().querySelector('[role="alert"]'))).toContain('Ese mes no existe en tu presupuesto');
      expect(link('Ir al mes actual')!.getAttribute('href')).toBe('/presupuesto');
      expect(root().querySelector('[data-testid="diagnosis"]')).toBeNull();
    });

    it('otro error muestra su mensaje, sin ofrecer ir al mes actual', async () => {
      await open('/cierre/2026-08', {
        '2026-08': throwError(() => new HttpErrorResponse({ status: 500, error: { code: 'INTERNAL_ERROR' } })),
      });

      expect(root().querySelector('[role="alert"]')).not.toBeNull();
      expect(link('Ir al mes actual')).toBeUndefined();
    });
  });
});
