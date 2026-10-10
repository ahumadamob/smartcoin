import { PeriodEntry } from '../../api';
import { canDeleteEntry, canEditAmount, canEditEntry, canRegisterMovement } from './entry-actions';

const entry = (overrides: Partial<PeriodEntry>): PeriodEntry =>
  ({ id: 1, budgetItemId: null, status: 'ESTIMATED', ...overrides }) as PeriodEntry;

describe('acciones de una partida', () => {
  it('«Editar monto» es de las recurrentes pendientes', () => {
    expect(canEditAmount(entry({ budgetItemId: 31 }))).toBe(true);
    expect(canEditAmount(entry({ budgetItemId: 31, status: 'PARTIAL' }))).toBe(true);
    expect(canEditAmount(entry({ budgetItemId: 31, status: 'CONSOLIDATED' }))).toBe(false);
    expect(canEditAmount(entry({ budgetItemId: null }))).toBe(false);
  });

  it('una partida ofrece «Editar» o «Editar monto», nunca las dos', () => {
    for (const budgetItemId of [null, 31]) {
      for (const status of ['ESTIMATED', 'PARTIAL', 'CONSOLIDATED'] as const) {
        const e = entry({ budgetItemId, status });
        expect(canEditEntry(e) && canEditAmount(e)).toBe(false);
      }
    }
  });

  it('«Eliminar» es de las pendientes, con o sin Concepto: Estimadas y Parciales, no Consolidadas (HU-18)', () => {
    for (const budgetItemId of [null, 31]) {
      expect(canDeleteEntry(entry({ budgetItemId, status: 'ESTIMATED' }))).toBe(true);
      expect(canDeleteEntry(entry({ budgetItemId, status: 'PARTIAL' }))).toBe(true);
      expect(canDeleteEntry(entry({ budgetItemId, status: 'CONSOLIDATED' }))).toBe(false);
    }
  });

  it('«Registrar cobro» y «Registrar pago» son de las pendientes, con o sin Concepto: Estimadas y Parciales, no Consolidadas (HU-19)', () => {
    for (const budgetItemId of [null, 31]) {
      for (const kind of ['INCOME', 'EXPENSE'] as const) {
        expect(canRegisterMovement(entry({ budgetItemId, kind, status: 'ESTIMATED' }))).toBe(true);
        expect(canRegisterMovement(entry({ budgetItemId, kind, status: 'PARTIAL' }))).toBe(true);
        expect(canRegisterMovement(entry({ budgetItemId, kind, status: 'CONSOLIDATED' }))).toBe(false);
      }
    }
  });
});
