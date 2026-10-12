import { PeriodEntry } from '../../api';

/**
 * Si una partida ofrece «Editar» (HU-16, RN-18): solo las que no vienen de un Concepto (los datos de una recurrente
 * se editan en su Concepto) y que no están consolidadas. Es solo lo que la pantalla ofrece: quien decide si la edición
 * vale es el backend, que además mira el período (`readonly`, RN-09).
 */
export function canEditEntry(entry: PeriodEntry): boolean {
  return entry.budgetItemId === null && entry.status !== 'CONSOLIDATED';
}

/**
 * Si una partida ofrece «Editar monto» (HU-17, RN-18): las recurrentes pendientes (Estimadas o Parciales). Cambian
 * solo su presupuestado y quedan marcadas como editadas. Complementa a {@link canEditEntry}: una partida ofrece una u
 * otra, nunca las dos.
 */
export function canEditAmount(entry: PeriodEntry): boolean {
  return entry.budgetItemId !== null && entry.status !== 'CONSOLIDATED';
}

/**
 * Si una partida ofrece «Eliminar» (HU-18, RN-30, RN-31): las pendientes, Estimadas o Parciales, con o sin Concepto.
 * Las Parciales tienen movimientos y el backend las rechaza (RN-32); el diálogo lo explica con la vista previa. Es
 * solo lo que la pantalla ofrece: quien decide es el backend, que además mira el período (`readonly`, RN-09).
 */
export function canDeleteEntry(entry: PeriodEntry): boolean {
  return entry.status !== 'CONSOLIDATED';
}

/**
 * Si una partida ofrece «Registrar pago» (gasto) o «Registrar cobro» (ingreso) (HU-19, RN-21): las pendientes,
 * Estimadas o Parciales, con o sin Concepto. Es solo lo que la pantalla ofrece: quien decide es el backend, que
 * además mira el período (`readonly`, RN-09), la cuenta y la fecha.
 */
export function canRegisterMovement(entry: PeriodEntry): boolean {
  return entry.status !== 'CONSOLIDATED';
}

/**
 * Si una partida ofrece «Consolidar» (HU-23, RN-25): las pendientes con al menos un movimiento, que son las Parciales.
 * Una Estimada no tiene movimientos (S-03). Es solo lo que la pantalla ofrece: quien decide es el backend, que además
 * mira el período (`readonly`, RN-09).
 */
export function canConsolidate(entry: PeriodEntry): boolean {
  return entry.status === 'PARTIAL';
}

/**
 * Si el diálogo de una partida ofrece «Editar» y «Eliminar» en sus movimientos (HU-21, RN-22): solo con la partida
 * pendiente en un período no cerrado. Es solo lo que la pantalla ofrece: quien decide es el backend, que además mira
 * el mes de la fecha del movimiento.
 */
export function canChangeMovements(entry: PeriodEntry, readonly: boolean): boolean {
  return !readonly && entry.status !== 'CONSOLIDATED';
}
