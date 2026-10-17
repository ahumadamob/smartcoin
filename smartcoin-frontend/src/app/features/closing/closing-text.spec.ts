import { HttpErrorResponse } from '@angular/common/http';
import { ClosingAccount, ClosingNewEntry, ClosingPendingEntry, ClosingPreview } from '../../api';
import {
  acknowledgeLabel,
  accountsByCurrency,
  balancesIntro,
  blockerText,
  CLOSING_STEPS,
  closeButtonLabel,
  closedMessage,
  closingSummary,
  consolidationText,
  confirmationHint,
  dayAndMonth,
  differenceText,
  effectText,
  irreversibleWarning,
  monthName,
  newEntryText,
  nextStepHint,
  sameEffectNote,
  stepForCloseError,
} from './closing-text';

const preview = (overrides: Partial<ClosingPreview> = {}): ClosingPreview => ({
  period: '2027-02',
  status: 'OPEN',
  lastDay: '2027-02-28',
  firstOpenPeriod: '2027-01',
  preconditionsMet: false,
  blockers: [],
  pendingEntries: [],
  accounts: [],
  newEntries: [],
  unresolvedEntryIds: [],
  invalidResolutions: [],
  missingRealBalanceAccountIds: [],
  invalidBalances: [],
  ...overrides,
});

/** La luz de RN-40: 45.000,00 presupuestados, 20.000,00 pagados. */
const power: ClosingPendingEntry = {
  id: 1,
  name: 'Luz',
  kind: 'EXPENSE',
  accountId: 12,
  accountName: 'Banco',
  currency: 'ARS',
  dueDate: '2026-10-18',
  budgetedAmount: 45000,
  actualAmount: 20000,
  pendingAmount: 25000,
  status: 'PARTIAL',
};

/** Un formato simple: el real lo da el pipe `money`, probado aparte. */
const money = (amount: number, currency: 'ARS' | 'USD') => `${currency === 'USD' ? 'US$' : '$'} ${amount}`;

describe('closing-text', () => {
  it('arma «día/mes» del último día que informa el backend', () => {
    expect(dayAndMonth('2026-10-31')).toBe('31/10');
    expect(dayAndMonth('2028-02-29')).toBe('29/02');
    expect(dayAndMonth('2027-01-31')).toBe('31/01');
  });

  it('dice si se puede cerrar según las precondiciones del backend', () => {
    expect(closingSummary(preview({ preconditionsMet: true }))).toBe('Se puede cerrar el mes.');
    expect(closingSummary(preview())).toBe('Todavía no se puede cerrar el mes.');
  });

  it('cada motivo en palabras; el fin de mes con el último día del período', () => {
    const p = preview();
    expect(blockerText({ code: 'PERIOD_CLOSED', detail: 'x' }, p)).toBe('El mes ya está cerrado.');
    expect(blockerText({ code: 'PREVIOUS_PERIOD_OPEN', detail: 'x' }, p)).toBe('El mes anterior todavía está abierto.');
    expect(blockerText({ code: 'PERIOD_NOT_FINISHED', detail: 'x' }, p)).toBe(
      'El mes termina el 28/02: se puede cerrar desde ese día.',
    );
  });

  it('agrupa las cuentas por moneda en el orden del backend, sin sumar', () => {
    const groups = accountsByCurrency([
      { id: 1, name: 'Banco', currency: 'ARS', openingDate: '2026-08-01', computedBalance: 10 },
      { id: 2, name: 'Efectivo', currency: 'ARS', openingDate: '2026-08-01', computedBalance: 20 },
      { id: 3, name: 'Dólares', currency: 'USD', openingDate: '2026-08-01', computedBalance: 30 },
    ]);

    expect(groups.map((g) => [g.label, g.accounts.map((a) => a.name)])).toEqual([
      ['Pesos', ['Banco', 'Efectivo']],
      ['Dólares', ['Dólares']],
    ]);
    expect(accountsByCurrency([])).toEqual([]);
    expect(groups.every((g) => !('total' in g))).toBe(true);
  });


  it('sin resolución en la simulación no anuncia ningún efecto', () => {
    expect(effectText(power, '2026-10', money)).toBeNull();
    expect(effectText({ ...power, resolution: 'CARRY_OVER' }, '2026-10', money)).toBeNull();
  });

  it('Postergar con pendiente: el monto consolidado y la partida que aparece, como la informa el backend', () => {
    const entry: ClosingPendingEntry = {
      ...power,
      resolution: 'CARRY_OVER',
      consolidatedAmount: 20000,
      carriedOverEntry: {
        period: '2026-11', origin: 'CARRIED_OVER', name: 'Saldo pendiente: Luz', kind: 'EXPENSE', accountId: 12,
        accountName: 'Banco', currency: 'ARS', dueDate: '2026-11-18', budgetedAmount: 25000, sourceEntryId: 1,
      },
    };

    expect(effectText(entry, '2026-10', money)).toBe(
      'Queda consolidada en $ 20000. En noviembre aparece «Saldo pendiente: Luz» por $ 25000, con vencimiento el 18/11.',
    );
  });

  it('Postergar en diciembre: el mes siguiente es de otro año y lo dice', () => {
    const entry: ClosingPendingEntry = {
      ...power,
      dueDate: '2026-12-31',
      resolution: 'CARRY_OVER',
      consolidatedAmount: 20000,
      carriedOverEntry: {
        period: '2027-01', origin: 'CARRIED_OVER', name: 'Saldo pendiente: Luz', kind: 'EXPENSE', accountId: 12,
        accountName: 'Banco', currency: 'ARS', dueDate: '2027-01-31', budgetedAmount: 25000, sourceEntryId: 1,
      },
    };

    expect(effectText(entry, '2026-12', money)).toContain('En enero 2027 aparece');
    expect(effectText(entry, '2026-12', money)).toContain('con vencimiento el 31/01.');
  });

  it('Postergar con pendiente 0: consolida y no crea nada', () => {
    const entry = { ...power, actualAmount: 50000, pendingAmount: 0, resolution: 'CARRY_OVER' as const, consolidatedAmount: 50000 };

    expect(effectText(entry, '2026-10', money)).toBe(
      'Queda consolidada en $ 50000. No queda nada pendiente: no se crea ninguna partida.',
    );
  });

  it('Cerrar con lo registrado: descarta el pendiente, sin movimientos queda en 0', () => {
    expect(effectText({ ...power, resolution: 'CLOSE_AS_IS', consolidatedAmount: 20000 }, '2026-10', money)).toBe(
      'Queda consolidada en $ 20000. Se descarta el pendiente de $ 25000.',
    );
    const unpaid = { ...power, actualAmount: 0, pendingAmount: 45000, resolution: 'CLOSE_AS_IS' as const, consolidatedAmount: 0 };
    expect(effectText(unpaid, '2026-10', money)).toBe('Queda consolidada en $ 0. Se descarta el pendiente de $ 45000.');
    const covered = { ...power, pendingAmount: 0, resolution: 'CLOSE_AS_IS' as const, consolidatedAmount: 20000 };
    expect(effectText(covered, '2026-10', money)).toBe('Queda consolidada en $ 20000.');
  });

  it('con pendiente 0 aclara que las dos opciones dan lo mismo', () => {
    expect(sameEffectNote({ ...power, pendingAmount: 0 })).toBe(
      'No queda nada pendiente: las dos opciones tienen el mismo efecto.',
    );
    expect(sameEffectNote(power)).toBeNull();
  });

  it('nombra el mes, con el año solo si cambia', () => {
    expect(monthName('2026-11', '2026-10')).toBe('noviembre');
    expect(monthName('2027-01', '2026-12')).toBe('enero 2027');
  });

  it('explica por qué no se puede avanzar a «Saldos»', () => {
    expect(nextStepHint(3, 2, false)).toBe('Falta elegir qué hacer con 2 partidas pendientes.');
    expect(nextStepHint(3, 1, false)).toBe('Falta elegir qué hacer con 1 partida pendiente.');
    expect(nextStepHint(3, 0, false)).toBe(
      'Todas las partidas pendientes tienen una resolución. El paso «Saldos» estará disponible más adelante.',
    );
    expect(nextStepHint(0, 0, false)).toBe('No hay nada que resolver. El paso «Saldos» estará disponible más adelante.');
    expect(nextStepHint(3, 0, true)).toBe('Todas las partidas pendientes tienen una resolución.');
  });

  // Saldos reales y diferencias (HU-32).

  const bank: ClosingAccount = {
    id: 12, name: 'Banco', currency: 'ARS', openingDate: '2026-08-01', computedBalance: 512300,
  };
  const difference = (overrides: Partial<ClosingNewEntry>): ClosingNewEntry => ({
    period: '2026-11', origin: 'CLOSING_DIFFERENCE', name: 'Diferencia de cierre: Banco (oct 2026)',
    kind: 'EXPENSE', accountId: 12, accountName: 'Banco', currency: 'ARS', dueDate: '2026-11-01',
    budgetedAmount: 2500, sourceAccountId: 12, ...overrides,
  });

  it('una diferencia negativa anuncia el gasto en el mes siguiente, tal como lo informa el backend', () => {
    const account: ClosingAccount = { ...bank, realBalance: 509800, difference: -2500, differenceEntry: difference({}) };

    expect(differenceText(account, '2026-10', money)).toBe(
      'En noviembre aparece un gasto «Diferencia de cierre: Banco (oct 2026)» por $ 2500.',
    );
  });

  it('una diferencia positiva anuncia el ingreso, en la moneda de la cuenta', () => {
    const dollars: ClosingAccount = {
      id: 13, name: 'Dólares', currency: 'USD', openingDate: '2026-08-01', computedBalance: 1000,
      realBalance: 1200, difference: 200,
      differenceEntry: difference({
        kind: 'INCOME', name: 'Diferencia de cierre: Dólares (oct 2026)', currency: 'USD', accountId: 13,
        accountName: 'Dólares', budgetedAmount: 200,
      }),
    };

    expect(differenceText(dollars, '2026-10', money)).toBe(
      'En noviembre aparece un ingreso «Diferencia de cierre: Dólares (oct 2026)» por US$ 200.',
    );
  });

  it('con diferencia 0 dice que coincide', () => {
    expect(differenceText({ ...bank, realBalance: 512300, difference: 0 }, '2026-10', money)).toBe('Coincide.');
  });

  it('sin saldo real no dice nada', () => {
    expect(differenceText(bank, '2026-10', money)).toBeNull();
    expect(differenceText({ ...bank, realBalance: null, difference: null }, '2026-10', money)).toBeNull();
  });

  it('en diciembre el mes siguiente es de otro año y lo dice', () => {
    const account: ClosingAccount = {
      ...bank, realBalance: 1, difference: -2500,
      differenceEntry: difference({ period: '2027-01', dueDate: '2027-01-01', name: 'Diferencia de cierre: Banco (dic 2026)' }),
    };

    expect(differenceText(account, '2026-12', money)).toBe(
      'En enero 2027 aparece un gasto «Diferencia de cierre: Banco (dic 2026)» por $ 2500.',
    );
  });

  it('explica qué es el saldo real', () => {
    expect(balancesIntro('31/10/2026')).toBe(
      'El saldo real es el que figura en el banco o la billetera al último día del mes (31/10/2026). ' +
        'No se guarda nada hasta confirmar el cierre.',
    );
  });

  it('explica por qué no se puede avanzar a «Confirmación»', () => {
    expect(confirmationHint(3, 3, false)).toBe('Falta cargar el saldo real de 3 cuentas.');
    expect(confirmationHint(3, 1, false)).toBe('Falta cargar el saldo real de 1 cuenta.');
    expect(confirmationHint(3, 0, false)).toBe(
      'Todos los saldos reales están cargados. La confirmación estará disponible más adelante.',
    );
    expect(confirmationHint(0, 0, false)).toBe(
      'No hay cuentas para conciliar. La confirmación estará disponible más adelante.',
    );
    expect(confirmationHint(3, 0, true)).toBe('Todos los saldos reales están cargados.');
  });

  // Confirmación (HU-33).

  it('los tres pasos están disponibles', () => {
    expect(CLOSING_STEPS.map((step) => step.available)).toEqual([true, true, true]);
  });

  it('avisa que el mes queda cerrado para siempre y lo pide confirmar', () => {
    expect(irreversibleWarning('agosto 2026')).toBe(
      'Al cerrar, agosto 2026 queda cerrado para siempre: no se puede reabrir, y ya no se pueden crear, editar ni ' +
        'eliminar sus partidas, ni movimientos o transferencias con fecha en ese mes.',
    );
    expect(acknowledgeLabel('diciembre 2026')).toBe(
      'Entiendo que diciembre 2026 queda cerrado para siempre y no se puede reabrir.',
    );
    expect(closeButtonLabel('agosto 2026')).toBe('Cerrar agosto 2026');
    expect(closedMessage('agosto 2026')).toBe('Agosto 2026 quedó cerrado.');
  });

  it('en el resumen, cada pendiente con su resolución y el monto con que se consolida', () => {
    expect(consolidationText({ ...power, resolution: 'CARRY_OVER', consolidatedAmount: 20000 }, money)).toBe(
      'Postergar saldo: se consolida en $ 20000.',
    );
    expect(
      consolidationText({ ...power, currency: 'USD', resolution: 'CLOSE_AS_IS', consolidatedAmount: 0 }, money),
    ).toBe('Cerrar con lo registrado: se consolida en US$ 0.');
  });

  it('en el resumen, cada partida nueva con su tipo, monto y vencimiento', () => {
    const created: ClosingNewEntry = {
      period: '2026-11', origin: 'CARRIED_OVER', name: 'Saldo pendiente: Luz', kind: 'EXPENSE', accountId: 12,
      accountName: 'Banco', currency: 'ARS', dueDate: '2026-11-18', budgetedAmount: 25000, sourceEntryId: 1,
    };
    expect(newEntryText(created, money)).toBe('Gasto por $ 25000, con vencimiento el 18/11.');
    expect(newEntryText({ ...created, kind: 'INCOME', currency: 'USD', dueDate: '2027-01-01' }, money)).toBe(
      'Ingreso por US$ 25000, con vencimiento el 01/01.',
    );
  });

  it('ante un error del cierre, elige el paso al que vuelve el asistente (D-45)', () => {
    const problem = (status: number, error: object | null) => new HttpErrorResponse({ status, error });
    expect(stepForCloseError(problem(409, { code: 'PERIOD_CLOSED' }))).toBe(0);
    expect(stepForCloseError(problem(409, { code: 'PREVIOUS_PERIOD_OPEN' }))).toBe(0);
    expect(stepForCloseError(problem(409, { code: 'PERIOD_NOT_FINISHED' }))).toBe(0);
    expect(stepForCloseError(problem(409, { code: 'UNRESOLVED_PENDING_ENTRIES', entries: [1] }))).toBe(0);
    expect(stepForCloseError(problem(409, { code: 'MISSING_REAL_BALANCE', accounts: [1] }))).toBe(1);
    expect(stepForCloseError(problem(400, { code: 'VALIDATION_ERROR', errors: [{ field: 'realBalances' }] }))).toBe(1);
    expect(stepForCloseError(problem(400, { code: 'VALIDATION_ERROR', errors: [{ field: 'resolutions' }] }))).toBe(0);
    expect(stepForCloseError(problem(400, { code: 'VALIDATION_ERROR' }))).toBe(0);
    expect(stepForCloseError(problem(500, { code: 'INTERNAL_ERROR' }))).toBe(2);
    expect(stepForCloseError(problem(0, null))).toBe(2);
    expect(stepForCloseError(new Error('x'))).toBe(2);
  });
});
