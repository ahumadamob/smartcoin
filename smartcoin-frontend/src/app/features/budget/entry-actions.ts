import { PeriodEntry } from '../../api';

/**
 * Si una partida ofrece «Editar» (HU-16, RN-18): solo las que no vienen de un Concepto (las recurrentes se editan en
 * su Concepto; el monto de una recurrente llega con HU-17) y que no están consolidadas. Es solo lo que la pantalla
 * ofrece: quien decide si la edición vale es el backend, que además mira el período (`readonly`, RN-09).
 */
export function canEditEntry(entry: PeriodEntry): boolean {
  return entry.budgetItemId === null && entry.status !== 'CONSOLIDATED';
}
