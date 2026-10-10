import { PeriodEntry } from '../../api';
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
