import { PeriodEntry } from '../../api';
import { canEditAmount, canEditEntry } from './entry-actions';

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
});
