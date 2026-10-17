import { ClosingPreview } from '../../api';
import { accountsByCurrency, blockerText, closingSummary, dayAndMonth } from './closing-text';

const preview = (overrides: Partial<ClosingPreview> = {}): ClosingPreview => ({
  period: '2027-02',
  status: 'OPEN',
  lastDay: '2027-02-28',
  firstOpenPeriod: '2027-01',
  preconditionsMet: false,
  blockers: [],
  pendingEntries: [],
  accounts: [],
  ...overrides,
});

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
});
