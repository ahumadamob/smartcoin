import { addMonths, isPeriod, nextPeriod, periodsInRange, previousPeriod } from './period-nav';

const range = { startPeriod: '2026-08', currentPeriod: '2026-10', horizon: '2028-10' };

describe('period-nav', () => {
  it.each([
    ['2026-10', 1, '2026-11'],
    ['2026-12', 1, '2027-01'],
    ['2027-01', -1, '2026-12'],
    ['2026-10', 24, '2028-10'],
    ['2026-10', -14, '2025-08'],
    ['2026-10', 0, '2026-10'],
  ])('addMonths(%s, %i) es %s', (period, months, expected) => {
    expect(addMonths(period, months)).toBe(expected);
  });

  it('reconoce solo el formato YYYY-MM', () => {
    expect(isPeriod('2026-11')).toBe(true);
    for (const value of ['2026-13', '2026-00', '2026-1', '202611', '2026-11-01', 'noviembre', '', null, undefined]) {
      expect(isPeriod(value)).toBe(false);
    }
  });

  it('no hay mes anterior al período inicial', () => {
    expect(previousPeriod('2026-09', range)).toBe('2026-08');
    expect(previousPeriod('2026-08', range)).toBeNull();
    expect(previousPeriod('2026-07', range)).toBeNull();
  });

  it('no hay mes siguiente al horizonte', () => {
    expect(nextPeriod('2028-09', range)).toBe('2028-10');
    expect(nextPeriod('2028-10', range)).toBeNull();
    expect(nextPeriod('2028-11', range)).toBeNull();
  });

  it('lista todos los períodos del rango, con los dos extremos', () => {
    const periods = periodsInRange(range);

    expect(periods).toHaveLength(27);
    expect(periods[0]).toBe('2026-08');
    expect(periods[5]).toBe('2027-01');
    expect(periods.at(-1)).toBe('2028-10');
  });

  it('un rango de un solo período tiene una opción', () => {
    expect(periodsInRange({ startPeriod: '2026-10', currentPeriod: '2026-10', horizon: '2026-10' })).toEqual([
      '2026-10',
    ]);
  });
});
