/**
 * Navegación entre períodos (HU-15). Son cuentas con meses, no con montos. Los períodos viajan como `YYYY-MM`, que se
 * compara bien como texto. Los límites (período inicial y horizonte) vienen del backend.
 */

const PERIOD = /^(\d{4})-(0[1-9]|1[0-2])$/;

export interface PeriodRange {
  startPeriod: string;
  currentPeriod: string;
  horizon: string;
}

export function isPeriod(value: string | null | undefined): value is string {
  return PERIOD.test(value ?? '');
}

/** El período corrido `months` meses (negativo: hacia atrás). */
export function addMonths(period: string, months: number): string {
  const match = PERIOD.exec(period);
  if (!match) {
    throw new Error(`Período inválido: ${period}`);
  }
  const index = Number(match[1]) * 12 + (Number(match[2]) - 1) + months;
  const year = Math.floor(index / 12);
  return `${String(year).padStart(4, '0')}-${String(index - year * 12 + 1).padStart(2, '0')}`;
}

/** El mes anterior, o `null` si `period` ya es el período inicial (o anterior). */
export function previousPeriod(period: string, range: PeriodRange): string | null {
  return period > range.startPeriod ? addMonths(period, -1) : null;
}

/** El mes siguiente, o `null` si `period` ya es el horizonte (o posterior). */
export function nextPeriod(period: string, range: PeriodRange): string | null {
  return period < range.horizon ? addMonths(period, 1) : null;
}

/** Todos los períodos del rango, en orden: las opciones del selector de mes. */
export function periodsInRange(range: PeriodRange): string[] {
  const periods: string[] = [];
  for (let period = range.startPeriod; period <= range.horizon; period = addMonths(period, 1)) {
    periods.push(period);
  }
  return periods;
}
