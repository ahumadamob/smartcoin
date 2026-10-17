import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, of, Subject, throwError } from 'rxjs';
import { CierreDeMesService, ClosingPendingEntry, ClosingPreview, ClosingPreviewRequest } from '../../api';
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
  newEntries: [],
  unresolvedEntryIds: [1, 2],
  invalidResolutions: [],
  missingRealBalanceAccountIds: [12, 14, 13],
  invalidBalances: [],
};

/**
 * Lo que respondería el backend para agosto con esas resoluciones (solo para el test: el asistente no calcula nada).
 * La luz posterga 25.000,00 a septiembre; el alquiler cobrado, 1.000,00.
 */
function augustWith(request?: ClosingPreviewRequest): ClosingPreview {
  const chosen = new Map((request?.resolutions ?? []).map((r) => [r.entryId, r.resolution]));
  const carried: Record<number, ClosingPendingEntry['carriedOverEntry']> = {
    1: {
      period: '2026-09', origin: 'CARRIED_OVER', name: 'Saldo pendiente: Luz', kind: 'EXPENSE', accountId: 12,
      accountName: 'Banco', currency: 'ARS', dueDate: '2026-09-18', budgetedAmount: 25000, sourceEntryId: 1,
    },
    2: {
      period: '2026-09', origin: 'CARRIED_OVER', name: 'Saldo pendiente: Alquiler cobrado', kind: 'INCOME',
      accountId: 13, accountName: 'Dólares', currency: 'USD', dueDate: '2026-09-20', budgetedAmount: 1000,
      sourceEntryId: 2,
    },
  };
  const pendingEntries = AUGUST.pendingEntries.map((entry) => {
    const resolution = chosen.get(entry.id);
    return resolution
      ? {
          ...entry,
          resolution,
          consolidatedAmount: entry.actualAmount,
          carriedOverEntry: resolution === 'CARRY_OVER' ? carried[entry.id] : undefined,
        }
      : entry;
  });
  // Solo para el test: el backend resta real menos calculado y arma la partida de diferencia; el asistente no.
  const real = new Map((request?.realBalances ?? []).map((b) => [b.accountId, b.amount]));
  const accounts = AUGUST.accounts.map((account) => {
    const balance = real.get(account.id);
    if (balance === undefined) {
      return account;
    }
    const difference = Math.round((balance - account.computedBalance) * 100) / 100;
    return {
      ...account,
      realBalance: balance,
      difference,
      differenceEntry:
        difference === 0
          ? undefined
          : {
              period: '2026-09',
              origin: 'CLOSING_DIFFERENCE' as const,
              name: `Diferencia de cierre: ${account.name} (ago 2026)`,
              kind: difference > 0 ? ('INCOME' as const) : ('EXPENSE' as const),
              accountId: account.id,
              accountName: account.name,
              currency: account.currency,
              dueDate: '2026-09-01',
              budgetedAmount: Math.abs(difference),
              sourceAccountId: account.id,
            },
    };
  });
  return {
    ...AUGUST,
    pendingEntries,
    accounts,
    newEntries: [
      ...pendingEntries.flatMap((entry) => (entry.carriedOverEntry ? [entry.carriedOverEntry] : [])),
      ...accounts.flatMap((account) => (account.differenceEntry ? [account.differenceEntry] : [])),
    ],
    unresolvedEntryIds: pendingEntries.filter((entry) => !entry.resolution).map((entry) => entry.id),
    missingRealBalanceAccountIds: accounts.filter((account) => account.realBalance === undefined).map((a) => a.id),
  };
}

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
  unresolvedEntryIds: [],
};

describe('ClosingWizard', () => {
  let harness: RouterTestingHarness;
  let api: { previewClosing: ReturnType<typeof vi.fn> };

  async function open(url: string, responses: Record<string, Observable<ClosingPreview>> = {}) {
    api = {
      previewClosing: vi.fn((period: string, request?: ClosingPreviewRequest) =>
        responses[period] ?? of({ ...augustWith(request), period }),
      ),
    };
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
        '2 Saldos',
        '3 Confirmación Disponible más adelante',
      ]);
      expect(steps[0].getAttribute('aria-current')).toBe('step');
      expect(steps[0].getAttribute('aria-disabled')).toBeNull();
    });

    it('solo la confirmación está deshabilitada; ningún paso tiene enlaces ni botones propios', () => {
      const steps = all('[data-testid="step"]');
      expect(steps.map((s) => s.getAttribute('aria-disabled'))).toEqual([null, null, 'true']);
      expect(steps.every((s) => s.querySelector('a, button') === null)).toBe(true);
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
      const entries = all('[data-testid="pending-entry"]');
      expect(entries.map((e) => text(e.querySelector('legend')))).toEqual(['Luz', 'Alquiler cobrado']);
      expect(text(entries[0].querySelector('.entry-details'))).toBe('Gasto · Banco · vence el 18/08/2026 · Parcial');
      expect(text(entries[0].querySelector('[data-testid="amounts"]'))).toBe(
        'Presupuestado $ 45.000,00 Real $ 20.000,00 Pendiente $ 25.000,00',
      );
      expect(text(entries[1].querySelector('.entry-details'))).toBe(
        'Ingreso · Dólares · vence el 20/08/2026 · Estimada',
      );
      expect(text(entries[1].querySelector('[data-testid="amounts"]'))).toBe(
        'Presupuestado US$ 1.000,00 Real US$ 0,00 Pendiente US$ 1.000,00',
      );
    });

    it('las cuentas se conciliaron en el paso «Saldos»: «Pendientes» no las lista', () => {
      expect(all('[data-testid="account-group"]')).toHaveLength(0);
      expect(root().querySelector('[data-testid="accounts-hint"]')).toBeNull();
      expect(root().querySelector('[data-testid="balances-step"]')).toBeNull();
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

    it('bloqueado, las pendientes se listan sin elegir nada y no ofrece avanzar (D-43)', async () => {
      await open('/cierre/2026-10', {
        '2026-10': of({ ...OCTOBER, pendingEntries: AUGUST.pendingEntries, unresolvedEntryIds: [1, 2] }),
      });

      const rows = all('[data-testid="pending-table"] tbody tr').map(cells);
      expect(rows).toEqual([
        ['18/08/2026', 'Luz', 'Gasto', 'Banco', '$ 45.000,00', '$ 20.000,00', '$ 25.000,00', 'Parcial'],
        ['20/08/2026', 'Alquiler cobrado', 'Ingreso', 'Dólares', 'US$ 1.000,00', 'US$ 0,00', 'US$ 1.000,00', 'Estimada'],
      ]);
      expect(text(root().querySelector('[data-testid="choose-later"]'))).toBe(
        'Cuando se pueda cerrar el mes, acá vas a elegir qué hacer con cada una.',
      );
      expect(root().querySelector('mat-radio-group')).toBeNull();
      expect(root().querySelector('[data-testid="all-carry-over"]')).toBeNull();
      expect(root().querySelector('[data-testid="next-step"]')).toBeNull();
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
      expect(root().querySelector('[data-testid="pending-table"]')).toBeNull();
      expect(root().querySelector('[data-testid="no-pending"]')).toBeNull();
      expect(root().querySelector('[data-testid="next-step"]')).toBeNull();
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

  describe('pendientes: elegir qué hacer con cada una (HU-31)', () => {
    beforeEach(() => open('/cierre/2026-08'));

    const entry = (id: number) => root().querySelector(`[data-testid="pending-entry"][data-entry-id="${id}"]`)!;
    const radio = (id: number, value: string) =>
      entry(id).querySelector<HTMLInputElement>(`[data-testid="choice-${value}"] input`)!;
    const effect = (id: number) => text(entry(id).querySelector('[data-testid="effect"]'));
    const next = () => root().querySelector<HTMLButtonElement>('[data-testid="next-step"]')!;
    const hint = () => text(root().querySelector('[data-testid="next-hint"]'));
    async function pick(id: number, value: string) {
      radio(id, value).click();
      await refresh();
    }

    it('arrancan sin elegir, con las dos opciones y la explicación de cada una', () => {
      expect(text(root().querySelector('[data-testid="resolutions-intro"]'))).toContain(
        'Ninguna de las dos opciones cambia la estimación de los meses siguientes',
      );
      expect(all('mat-radio-group')).toHaveLength(2);
      expect(all('input[type="radio"]').every((input) => !(input as HTMLInputElement).checked)).toBe(true);
      const options = Array.from(entry(1).querySelectorAll('.option')).map((o) => text(o));
      expect(options).toEqual([
        'Postergar saldo Se consolida con lo registrado y lo que falta pasa al mes siguiente como una partida nueva.',
        'Cerrar con lo registrado Se consolida con lo registrado, aunque sea 0, y lo que falta se descarta.',
      ]);
      expect(effect(1)).toBe('');
      expect(api.previewClosing).toHaveBeenCalledTimes(1);
    });

    it('el grupo de opciones se nombra con la partida y cada opción se describe con su explicación', () => {
      const group = entry(1).querySelector('mat-radio-group')!;
      const legend = entry(1).querySelector('legend')!;
      expect(group.getAttribute('aria-labelledby')).toBe(legend.id);
      const described = radio(1, 'CARRY_OVER').getAttribute('aria-describedby')!;
      expect(text(document.getElementById(described))).toContain('pasa al mes siguiente');
    });

    it('al elegir Postergar vuelve a pedir la simulación y muestra lo que va a pasar según el backend', async () => {
      await pick(1, 'CARRY_OVER');

      expect(api.previewClosing).toHaveBeenLastCalledWith('2026-08', {
        resolutions: [{ entryId: 1, resolution: 'CARRY_OVER' }],
        realBalances: [],
      });
      expect(effect(1)).toBe(
        'Queda consolidada en $ 20.000,00. En septiembre aparece «Saldo pendiente: Luz» por $ 25.000,00, ' +
          'con vencimiento el 18/09.',
      );
      expect(effect(2)).toBe('');
    });

    it('al elegir Cerrar con lo registrado muestra el monto consolidado y el pendiente que se descarta', async () => {
      await pick(2, 'CLOSE_AS_IS');

      expect(effect(2)).toBe('Queda consolidada en US$ 0,00. Se descarta el pendiente de US$ 1.000,00.');
    });

    it('envía todas las elecciones hechas, y cambiar una la reemplaza', async () => {
      await pick(1, 'CARRY_OVER');
      await pick(2, 'CLOSE_AS_IS');
      await pick(1, 'CLOSE_AS_IS');

      expect(api.previewClosing).toHaveBeenLastCalledWith('2026-08', {
        resolutions: [
          { entryId: 1, resolution: 'CLOSE_AS_IS' },
          { entryId: 2, resolution: 'CLOSE_AS_IS' },
        ],
        realBalances: [],
      });
      expect(effect(1)).toBe('Queda consolidada en $ 20.000,00. Se descarta el pendiente de $ 25.000,00.');
    });

    it('no se puede avanzar hasta resolver todas; con todas resueltas, se pasa a «Saldos»', async () => {
      expect(next().disabled).toBe(true);
      expect(text(next())).toBe('Siguiente: Saldos');
      expect(hint()).toBe('Falta elegir qué hacer con 2 partidas pendientes.');

      await pick(1, 'CARRY_OVER');
      expect(next().disabled).toBe(true);
      expect(hint()).toBe('Falta elegir qué hacer con 1 partida pendiente.');

      await pick(2, 'CLOSE_AS_IS');
      expect(next().disabled).toBe(false);
      expect(hint()).toBe('Todas las partidas pendientes tienen una resolución.');
      expect(next().getAttribute('aria-describedby')).toBe('next-hint');
    });

    it('«en todas» elige la misma para todas, y después se puede cambiar una', async () => {
      (root().querySelector('[data-testid="all-carry-over"]') as HTMLButtonElement).click();
      await refresh();

      expect(api.previewClosing).toHaveBeenLastCalledWith('2026-08', {
        resolutions: [
          { entryId: 1, resolution: 'CARRY_OVER' },
          { entryId: 2, resolution: 'CARRY_OVER' },
        ],
        realBalances: [],
      });
      expect(radio(1, 'CARRY_OVER').checked).toBe(true);
      expect(radio(2, 'CARRY_OVER').checked).toBe(true);
      expect(effect(2)).toContain('En septiembre aparece «Saldo pendiente: Alquiler cobrado» por US$ 1.000,00');

      await pick(2, 'CLOSE_AS_IS');
      expect(radio(2, 'CLOSE_AS_IS').checked).toBe(true);
      expect(radio(1, 'CARRY_OVER').checked).toBe(true);

      (root().querySelector('[data-testid="all-close-as-is"]') as HTMLButtonElement).click();
      await refresh();
      expect(radio(1, 'CLOSE_AS_IS').checked).toBe(true);
    });
  });

  describe('pendientes: casos particulares (HU-31)', () => {
    const entry = (id: number) => root().querySelector(`[data-testid="pending-entry"][data-entry-id="${id}"]`)!;
    const effect = (id: number) => text(entry(id).querySelector('[data-testid="effect"]'));

    it('sin pendientes lo dice, no pide nada y no hay nada que resolver', async () => {
      await open('/cierre/2026-08', {
        '2026-08': of({ ...AUGUST, pendingEntries: [], unresolvedEntryIds: [] }),
      });

      expect(text(root().querySelector('[data-testid="no-pending"]'))).toBe(
        'No hay partidas pendientes en este mes: no hay nada que resolver.',
      );
      expect(root().querySelector('mat-radio-group')).toBeNull();
      expect(root().querySelector('[data-testid="all-carry-over"]')).toBeNull();
      expect(text(root().querySelector('[data-testid="next-hint"]'))).toBe('No hay nada que resolver.');
      expect(root().querySelector<HTMLButtonElement>('[data-testid="next-step"]')!.disabled).toBe(false);
    });

    it('con pendiente 0 aclara que las dos opciones dan lo mismo y Postergar no crea nada', async () => {
      const covered = { ...AUGUST.pendingEntries[0], id: 5, actualAmount: 50000, pendingAmount: 0 };
      const base = { ...AUGUST, pendingEntries: [covered], unresolvedEntryIds: [5] };
      const responses = new Subject<ClosingPreview>();
      await open('/cierre/2026-08', { '2026-08': of(base) });
      api.previewClosing.mockReturnValue(responses);

      expect(text(entry(5).querySelector('[data-testid="same-effect"]'))).toBe(
        'No queda nada pendiente: las dos opciones tienen el mismo efecto.',
      );

      entry(5).querySelector<HTMLInputElement>('[data-testid="choice-CARRY_OVER"] input')!.click();
      await refresh();
      // Mientras la simulación no llega, no se anuncia un efecto que todavía no calculó el backend.
      expect(effect(5)).toBe('Calculando lo que va a pasar…');
      expect(root().querySelector<HTMLButtonElement>('[data-testid="next-step"]')!.disabled).toBe(true);

      responses.next({
        ...base,
        pendingEntries: [{ ...covered, resolution: 'CARRY_OVER', consolidatedAmount: 50000 }],
        unresolvedEntryIds: [],
      });
      await refresh();
      expect(effect(5)).toBe('Queda consolidada en $ 50.000,00. No queda nada pendiente: no se crea ninguna partida.');
    });

    it('si una partida elegida dejó de estar pendiente, se quita y se avisa', async () => {
      await open('/cierre/2026-08');
      api.previewClosing.mockReturnValue(
        of({
          ...AUGUST,
          pendingEntries: [AUGUST.pendingEntries[1]],
          unresolvedEntryIds: [2],
          invalidResolutions: [{ entryId: 1, reason: 'NOT_PENDING' }],
        }),
      );

      entry(1).querySelector<HTMLInputElement>('[data-testid="choice-CARRY_OVER"] input')!.click();
      await refresh();

      expect(all('[data-testid="pending-entry"]').map((e) => e.getAttribute('data-entry-id'))).toEqual(['2']);
      expect(text(root().querySelector('[data-testid="removed-entries"]'))).toContain(
        'Algunas partidas dejaron de estar pendientes',
      );
      expect(text(root().querySelector('[data-testid="next-hint"]'))).toBe(
        'Falta elegir qué hacer con 1 partida pendiente.',
      );
    });

    it('si la simulación falla al elegir, muestra el error, no anuncia efecto y no deja avanzar', async () => {
      await open('/cierre/2026-08');
      api.previewClosing.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 500, error: { code: 'INTERNAL_ERROR' } })),
      );

      entry(1).querySelector<HTMLInputElement>('[data-testid="choice-CARRY_OVER"] input')!.click();
      await refresh();

      expect(root().querySelector('[data-testid="update-error"]')).not.toBeNull();
      expect(effect(1)).toBe('');
      expect(all('[data-testid="pending-entry"]')).toHaveLength(2);
      expect(root().querySelector<HTMLButtonElement>('[data-testid="next-step"]')!.disabled).toBe(true);
    });

    it('al ir a otro mes se descartan las elecciones', async () => {
      await open('/cierre/2026-10', {
        '2026-10': of(OCTOBER),
      });
      link('Ir a cerrar')!.click();
      await refresh();
      entry(1).querySelector<HTMLInputElement>('[data-testid="choice-CARRY_OVER"] input')!.click();
      await refresh();
      expect(effect(1)).toContain('Saldo pendiente: Luz');

      await TestBed.inject(Router).navigateByUrl('/cierre/2026-09');
      await refresh();

      expect(api.previewClosing).toHaveBeenLastCalledWith('2026-09');
      expect(all('input[type="radio"]').every((input) => !(input as HTMLInputElement).checked)).toBe(true);
    });
  });

  describe('saldos: cargar el saldo real de cada cuenta (HU-32)', () => {
    const next = () => root().querySelector<HTMLButtonElement>('[data-testid="next-step"]')!;
    const confirm = () => root().querySelector<HTMLButtonElement>('[data-testid="confirm-step"]')!;
    const confirmHint = () => text(root().querySelector('[data-testid="confirm-hint"]'));
    const row = (id: number) => root().querySelector(`[data-testid="balance-row"][data-account-id="${id}"]`)!;
    const input = (id: number) => root().querySelector<HTMLInputElement>(`[data-testid="real-balance-${id}"]`)!;
    const difference = (id: number) => text(row(id).querySelector('[data-testid="difference"]'));
    const effect = (id: number) => text(row(id).querySelector('[data-testid="difference-effect"]'));
    const lastBody = () => api.previewClosing.mock.lastCall![1] as ClosingPreviewRequest;

    /** Escribe el saldo y lo da por terminado, como al salir del campo. */
    async function enter(id: number, value: string) {
      input(id).value = value;
      input(id).dispatchEvent(new Event('input'));
      input(id).dispatchEvent(new Event('change'));
      await refresh();
    }

    async function toBalances() {
      for (const id of [1, 2]) {
        root().querySelector<HTMLInputElement>(
          `[data-testid="pending-entry"][data-entry-id="${id}"] [data-testid="choice-CLOSE_AS_IS"] input`,
        )!.click();
        await refresh();
      }
      next().click();
      await refresh();
    }

    beforeEach(async () => {
      await open('/cierre/2026-08');
      await toBalances();
    });

    it('«Siguiente: Saldos» lleva al segundo paso y el foco pasa al título', () => {
      const steps = all('[data-testid="step"]');
      expect(steps[1].getAttribute('aria-current')).toBe('step');
      expect(steps[0].getAttribute('aria-current')).toBeNull();
      expect(root().querySelector('[data-testid="balances-step"]')).not.toBeNull();
      expect(root().querySelector('[data-testid="pending-entries"]')).toBeNull();
      expect(root().querySelector('[data-testid="diagnosis"]')).toBeNull();
    });

    it('explica en una línea qué es el saldo real', () => {
      expect(text(root().querySelector('[data-testid="balances-intro"]'))).toBe(
        'El saldo real es el que figura en el banco o la billetera al último día del mes (31/08/2026). ' +
          'No se guarda nada hasta confirmar el cierre.',
      );
    });

    it('lista las cuentas agrupadas por moneda con su saldo calculado y un campo para el real, sin subtotales', () => {
      const groups = all('[data-testid="account-group"]');
      expect(groups.map((g) => text(g.querySelector('caption')))).toEqual(['Pesos', 'Dólares']);
      expect(groups[0].querySelectorAll('[data-testid="balance-row"]')).toHaveLength(2);
      expect(Array.from(groups[0].querySelectorAll('thead th')).map((th) => text(th))).toEqual([
        'Cuenta', 'Saldo calculado', 'Saldo real', 'Diferencia',
      ]);
      expect(text(row(12).querySelector('th'))).toBe('Banco');
      expect(text(row(12).querySelector('td'))).toBe('$ 255.000,00');
      expect(text(row(14).querySelector('td'))).toBe('-$ 1.500,50');
      expect(text(row(13).querySelector('td'))).toBe('US$ 600,00');
      expect(row(14).querySelector('.negative')).not.toBeNull();
      expect(root().textContent).not.toContain('Total');
      expect(root().textContent).not.toContain('Subtotal');
    });

    it('cada campo se nombra con su cuenta, admite coma decimal y arranca vacío', () => {
      expect(input(12).getAttribute('aria-label')).toBe('Saldo real de Banco');
      expect(input(13).getAttribute('aria-label')).toBe('Saldo real de Dólares');
      expect(input(12).getAttribute('inputmode')).toBe('decimal');
      expect(all('[data-testid="balance-row"] input').every((i) => (i as HTMLInputElement).value === '')).toBe(true);
      expect(effect(12)).toBe('');
      expect(difference(12)).toBe('');
    });

    it('no pidió nada más que la simulación: cargar la pantalla no guarda ni calcula', () => {
      expect(api.previewClosing.mock.calls.every(([period]) => period === '2026-08')).toBe(true);
      expect(Object.keys(api)).toEqual(['previewClosing']);
    });

    it('una diferencia negativa anuncia el gasto en el mes siguiente, con lo que informa el backend', async () => {
      await enter(12, '250.000,00');

      expect(lastBody()).toEqual({
        resolutions: [
          { entryId: 1, resolution: 'CLOSE_AS_IS' },
          { entryId: 2, resolution: 'CLOSE_AS_IS' },
        ],
        realBalances: [{ accountId: 12, amount: 250000 }],
      });
      expect(difference(12)).toBe('-$ 5.000,00');
      expect(effect(12)).toBe(
        'En septiembre aparece un gasto «Diferencia de cierre: Banco (ago 2026)» por $ 5.000,00.',
      );
    });

    it('una diferencia positiva en dólares anuncia el ingreso en dólares', async () => {
      await enter(13, '650');

      expect(lastBody().realBalances).toEqual([{ accountId: 13, amount: 650 }]);
      expect(difference(13)).toBe('US$ 50,00');
      expect(effect(13)).toBe(
        'En septiembre aparece un ingreso «Diferencia de cierre: Dólares (ago 2026)» por US$ 50,00.',
      );
    });

    it('con el mismo saldo calculado dice que coincide y no anuncia ninguna partida', async () => {
      await enter(12, '255000');

      expect(difference(12)).toBe('$ 0,00');
      expect(effect(12)).toBe('Coincide.');
    });

    it('admite saldos negativos y centavos', async () => {
      await enter(14, '-2.000,50');

      expect(lastBody().realBalances).toEqual([{ accountId: 14, amount: -2000.5 }]);
      expect(difference(14)).toBe('-$ 500,00');
      expect(effect(14)).toContain('un gasto «Diferencia de cierre: Efectivo (ago 2026)» por $ 500,00');
    });

    it('cada saldo cargado vuelve a pedir la simulación con todos los anteriores', async () => {
      await enter(12, '255000');
      await enter(13, '600');

      expect(lastBody().realBalances).toEqual([
        { accountId: 12, amount: 255000 },
        { accountId: 13, amount: 600 },
      ]);
      expect(effect(12)).toBe('Coincide.');
      expect(effect(13)).toBe('Coincide.');
    });

    it('cambiar un saldo reemplaza el anterior y borrarlo lo quita de la simulación', async () => {
      await enter(12, '250000');
      await enter(12, '255000');
      expect(lastBody().realBalances).toEqual([{ accountId: 12, amount: 255000 }]);
      expect(effect(12)).toBe('Coincide.');

      await enter(12, '');
      expect(lastBody().realBalances).toEqual([]);
      expect(effect(12)).toBe('');
      expect(difference(12)).toBe('');
    });

    it('si el campo no cambió no vuelve a pedir la simulación', async () => {
      await enter(12, '250000');
      const calls = api.previewClosing.mock.calls.length;

      await enter(12, '250000');
      expect(api.previewClosing.mock.calls).toHaveLength(calls);
    });

    it('un saldo con formato inválido muestra el error, no pide nada y lo cuenta como faltante', async () => {
      const calls = api.previewClosing.mock.calls.length;

      await enter(12, '25.5');

      expect(api.previewClosing.mock.calls).toHaveLength(calls);
      expect(text(row(12).querySelector('mat-error'))).toBe(
        'Escribilo con coma decimal y hasta 2 decimales, por ejemplo 1500,50.',
      );
      expect(effect(12)).toBe('');
      expect(confirmHint()).toBe('Revisá el saldo real con formato inválido.');
    });

    it('un saldo válido que pasa a inválido se quita de la simulación', async () => {
      await enter(12, '250000');
      await enter(12, '2500,001');

      expect(lastBody().realBalances).toEqual([]);
      expect(effect(12)).toBe('');
    });

    it('mientras llega la simulación dice que está calculando y no anuncia nada', async () => {
      const pending = new Subject<ClosingPreview>();
      api.previewClosing.mockReturnValueOnce(pending);

      await enter(12, '250000');
      expect(effect(12)).toBe('Calculando la diferencia…');
      expect(difference(12)).toBe('');

      pending.next(
        augustWith({
          resolutions: [
            { entryId: 1, resolution: 'CLOSE_AS_IS' },
            { entryId: 2, resolution: 'CLOSE_AS_IS' },
          ],
          realBalances: [{ accountId: 12, amount: 250000 }],
        }),
      );
      pending.complete();
      await refresh();
      expect(effect(12)).toContain('un gasto');
    });

    it('si la simulación falla, muestra el error y no anuncia efecto ni deja avanzar', async () => {
      api.previewClosing.mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ status: 500, error: { detail: 'Falló.' } })),
      );

      await enter(12, '250000');

      expect(root().querySelector('[data-testid="update-error"]')).not.toBeNull();
      expect(effect(12)).toBe('');
      expect(confirm().disabled).toBe(true);
    });

    it('avanzar a «Confirmación» exige todos los saldos, y sigue deshabilitado hasta HU-33', async () => {
      expect(confirm().disabled).toBe(true);
      expect(text(confirm())).toBe('Siguiente: Confirmación');
      expect(confirmHint()).toBe('Falta cargar el saldo real de 3 cuentas.');
      expect(confirm().getAttribute('aria-describedby')).toBe('confirm-hint');

      await enter(12, '255000');
      await enter(14, '-1.500,50');
      expect(confirm().disabled).toBe(true);
      expect(confirmHint()).toBe('Falta cargar el saldo real de 1 cuenta.');

      await enter(13, '600');
      expect(confirm().disabled).toBe(true);
      expect(confirmHint()).toBe(
        'Todos los saldos reales están cargados. La confirmación estará disponible más adelante.',
      );
    });

    it('se puede volver a «Pendientes» sin perder las elecciones ni los saldos', async () => {
      await enter(12, '250000');
      await enter(13, '650');

      root().querySelector<HTMLButtonElement>('[data-testid="back-step"]')!.click();
      await refresh();

      expect(all('[data-testid="step"]')[0].getAttribute('aria-current')).toBe('step');
      expect(root().querySelector('[data-testid="pending-entries"]')).not.toBeNull();
      const radio = (id: number) =>
        root().querySelector<HTMLInputElement>(
          `[data-testid="pending-entry"][data-entry-id="${id}"] [data-testid="choice-CLOSE_AS_IS"] input`,
        )!;
      expect(radio(1).checked).toBe(true);
      expect(radio(2).checked).toBe(true);
      expect(next().disabled).toBe(false);

      next().click();
      await refresh();
      expect(input(12).value).toBe('250000');
      expect(input(13).value).toBe('650');
      expect(effect(12)).toContain('un gasto');
      expect(effect(13)).toContain('un ingreso');
    });

    it('cambiar una resolución desde «Pendientes» conserva los saldos en el pedido', async () => {
      await enter(12, '250000');
      root().querySelector<HTMLButtonElement>('[data-testid="back-step"]')!.click();
      await refresh();

      root().querySelector<HTMLInputElement>(
        '[data-testid="pending-entry"][data-entry-id="1"] [data-testid="choice-CARRY_OVER"] input',
      )!.click();
      await refresh();

      expect(lastBody().resolutions).toEqual([
        { entryId: 1, resolution: 'CARRY_OVER' },
        { entryId: 2, resolution: 'CLOSE_AS_IS' },
      ]);
      expect(lastBody().realBalances).toEqual([{ accountId: 12, amount: 250000 }]);
    });
  });

  describe('saldos: casos particulares (HU-32)', () => {
    it('sin cuentas abiertas lo dice y no hay saldos que cargar', async () => {
      await open('/cierre/2026-08', {
        '2026-08': of({ ...AUGUST, accounts: [], pendingEntries: [], unresolvedEntryIds: [], missingRealBalanceAccountIds: [] }),
      });
      root().querySelector<HTMLButtonElement>('[data-testid="next-step"]')!.click();
      await refresh();

      expect(text(root().querySelector('[data-testid="no-accounts"]'))).toBe(
        'No hay cuentas abiertas al 31/08/2026: no hay saldos que conciliar.',
      );
      expect(root().querySelectorAll('input')).toHaveLength(0);
      expect(text(root().querySelector('[data-testid="confirm-hint"]'))).toBe(
        'No hay cuentas para conciliar. La confirmación estará disponible más adelante.',
      );
    });

    it('un mes bloqueado no ofrece cargar saldos: muestra el diagnóstico con las cuentas', async () => {
      await open('/cierre/2026-10', { '2026-10': of(OCTOBER) });

      expect(root().querySelector('[data-testid="next-step"]')).toBeNull();
      expect(all('[data-testid="account-group"]')).toHaveLength(2);
      expect(root().querySelector('[data-testid="balances-step"]')).toBeNull();
    });

    it('si el backend dice que dejó de estar todo resuelto, vuelve a «Pendientes»', async () => {
      await open('/cierre/2026-08');
      for (const id of [1, 2]) {
        root().querySelector<HTMLInputElement>(
          `[data-testid="pending-entry"][data-entry-id="${id}"] [data-testid="choice-CLOSE_AS_IS"] input`,
        )!.click();
        await refresh();
      }
      root().querySelector<HTMLButtonElement>('[data-testid="next-step"]')!.click();
      await refresh();
      api.previewClosing.mockReturnValueOnce(of({ ...augustWith(), unresolvedEntryIds: [1] }));

      const field = root().querySelector<HTMLInputElement>('[data-testid="real-balance-12"]')!;
      field.value = '1';
      field.dispatchEvent(new Event('input'));
      field.dispatchEvent(new Event('change'));
      await refresh();

      expect(root().querySelector('[data-testid="balances-step"]')).toBeNull();
      expect(all('[data-testid="step"]')[0].getAttribute('aria-current')).toBe('step');
    });

    it('al ir a otro mes se descartan los saldos y se vuelve al primer paso', async () => {
      await open('/cierre/2026-08');
      for (const id of [1, 2]) {
        root().querySelector<HTMLInputElement>(
          `[data-testid="pending-entry"][data-entry-id="${id}"] [data-testid="choice-CLOSE_AS_IS"] input`,
        )!.click();
        await refresh();
      }
      root().querySelector<HTMLButtonElement>('[data-testid="next-step"]')!.click();
      await refresh();

      await TestBed.inject(Router).navigateByUrl('/cierre/2026-09');
      await refresh();

      expect(all('[data-testid="step"]')[0].getAttribute('aria-current')).toBe('step');
      expect(root().querySelector('[data-testid="balances-step"]')).toBeNull();
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
