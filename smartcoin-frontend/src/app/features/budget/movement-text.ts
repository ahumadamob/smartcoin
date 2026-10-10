import { MovementDates, PeriodEntry } from '../../api';
import { CurrencyCode } from '../../shared/pipes/money.pipe';

/** Cobro de un ingreso, pago de un gasto (docs/glosario.md: el movimiento es «cobro o pago»). */
export type MovementWord = 'cobro' | 'pago';

/** Da formato a un monto que ya viene calculado del backend (el pipe `money`). */
export type FormatMoney = (amount: number, currency: CurrencyCode) => string;

export function movementWord(kind: PeriodEntry.KindEnum): MovementWord {
  return kind === 'INCOME' ? 'cobro' : 'pago';
}

/** «Registrar cobro» o «Registrar pago»: el texto de la acción y del botón del diálogo. */
export function registerLabel(kind: PeriodEntry.KindEnum): string {
  return `Registrar ${movementWord(kind)}`;
}

/**
 * Aviso cuando el pendiente llegó a 0 (HU-19, criterio 6, RN-23). Es solo texto: la acción «Consolidar» llega con
 * HU-23. Dice únicamente lo que es cierto hoy: la partida está cubierta y sigue Parcial hasta que se consolide. El
 * monto es el que informa el backend; acá solo se le da formato.
 */
export function coveredNotice(name: string, pendingAmount: number, currency: CurrencyCode, money: FormatMoney): string {
  return `«${name}» ya está cubierta: pendiente ${money(pendingAmount, currency)}. Sigue Parcial hasta que se consolide.`;
}

/** Mensaje al guardar: qué se registró y, si el pendiente llegó a 0, el aviso. `entry` es la partida ya actualizada. */
export function registeredMessage(entry: PeriodEntry, amount: number, money: FormatMoney): string {
  const word = movementWord(entry.kind) === 'cobro' ? 'Cobro' : 'Pago';
  const done = `${word} de ${money(amount, entry.currency)} registrado en «${entry.name}».`;
  return entry.pendingAmount === 0
    ? `${done} ${coveredNotice(entry.name, entry.pendingAmount, entry.currency, money)}`
    : done;
}

/**
 * La ventana todavía no abrió: la fecha más temprana que informa el backend es posterior a la más tardía (hoy).
 * Solo lee el rango que informó el backend; las fechas ISO `YYYY-MM-DD` se comparan como texto.
 */
export function windowNotOpenYet(dates: MovementDates): boolean {
  return dates.earliestDate > dates.latestDate;
}

/** La fecha con la que arranca el campo: hoy, según el reloj del backend. */
export function suggestedDate(dates: MovementDates): string {
  return dates.latestDate;
}

/**
 * Una línea que explica hasta cuándo hacia atrás se puede fechar el movimiento (HU-20, RN-21). Los días vienen del
 * backend porque la ventana es configurable. `period` y `earliest` ya vienen con formato («diciembre 2026»,
 * «21/11/2026»); `earliest` es la fecha más temprana con la cuenta elegida, que puede ser la apertura de la cuenta.
 */
export function windowNotice(earlyDays: number, period: string, earliest: string): string {
  const days = earlyDays === 1 ? '1 día' : `${earlyDays} días`;
  const rule =
    earlyDays === 0
      ? `No se puede fechar antes del inicio de ${period}.`
      : `Se puede fechar hasta ${days} antes del inicio de ${period}.`;
  return `${rule} Fecha más temprana con esta cuenta: ${earliest}.`;
}

/** Cuando la ventana no abrió: dice desde cuándo se admiten movimientos (D-33). */
export function notOpenYetNotice(earliest: string): string {
  return `Todavía no se pueden registrar movimientos de esta partida: se admiten desde el ${earliest}.`;
}
