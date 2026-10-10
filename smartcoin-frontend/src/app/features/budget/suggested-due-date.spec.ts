import { suggestedDueDate } from './suggested-due-date';

describe('suggestedDueDate (HU-16)', () => {
  it('en el mes actual sugiere el día de hoy', () => {
    expect(suggestedDueDate('2026-10', '2026-10', new Date(2026, 9, 8))).toBe('2026-10-08');
    expect(suggestedDueDate('2026-10', '2026-10', new Date(2026, 9, 31))).toBe('2026-10-31');
  });

  it('en cualquier otro mes, anterior o posterior, sugiere el día 1', () => {
    expect(suggestedDueDate('2026-11', '2026-10', new Date(2026, 9, 28))).toBe('2026-11-01');
    expect(suggestedDueDate('2026-08', '2026-10', new Date(2026, 9, 28))).toBe('2026-08-01');
    expect(suggestedDueDate('2027-01', '2026-10', new Date(2026, 9, 28))).toBe('2027-01-01');
  });

  it('solo toma el día: si el mes del navegador difiere del actual, igual cae dentro del período', () => {
    // El reloj del navegador ya está en noviembre, pero el período actual del backend sigue siendo octubre.
    expect(suggestedDueDate('2026-10', '2026-10', new Date(2026, 10, 1))).toBe('2026-10-01');
    // Un 31 en un mes de 30 días se acota al último día.
    expect(suggestedDueDate('2026-11', '2026-11', new Date(2026, 9, 31))).toBe('2026-11-30');
    expect(suggestedDueDate('2027-02', '2027-02', new Date(2027, 0, 31))).toBe('2027-02-28');
    expect(suggestedDueDate('2028-02', '2028-02', new Date(2028, 0, 31))).toBe('2028-02-29');
  });

  it('rechaza un período que no tiene el formato', () => {
    expect(() => suggestedDueDate('noviembre', '2026-10', new Date())).toThrow();
  });
});
