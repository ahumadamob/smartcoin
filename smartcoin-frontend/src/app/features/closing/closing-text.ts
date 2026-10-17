import { ClosingBlocker, ClosingPreview } from '../../api';

/** Los pasos del asistente de cierre (Épica 8): pendientes, saldos y confirmación. */
export interface ClosingStep {
  label: string;
  /** Si el paso ya tiene contenido. Los que no, se muestran deshabilitados. */
  available: boolean;
}

/** HU-30 hace el primer paso; los otros dos llegan con la resolución de pendientes, la conciliación y el cierre. */
export const CLOSING_STEPS: readonly ClosingStep[] = [
  { label: 'Pendientes', available: true },
  { label: 'Saldos', available: false },
  { label: 'Confirmación', available: false },
];

export const UNAVAILABLE_STEP_TEXT = 'Disponible más adelante';

/** «31/10» a partir de una fecha ISO del backend. Solo arma el texto: no calcula fechas. */
export function dayAndMonth(isoDate: string): string {
  const [, month, day] = isoDate.split('-');
  return `${day}/${month}`;
}

/** Si se cumplen las precondiciones de RN-38, según la simulación del backend. */
export function closingSummary(preview: ClosingPreview): string {
  return preview.preconditionsMet ? 'Se puede cerrar el mes.' : 'Todavía no se puede cerrar el mes.';
}

/** Un motivo en palabras. Un código desconocido muestra la explicación del backend. */
export function blockerText(blocker: ClosingBlocker, preview: ClosingPreview): string {
  switch (blocker.code) {
    case 'PERIOD_CLOSED':
      return 'El mes ya está cerrado.';
    case 'PREVIOUS_PERIOD_OPEN':
      return 'El mes anterior todavía está abierto.';
    case 'PERIOD_NOT_FINISHED':
      return `El mes termina el ${dayAndMonth(preview.lastDay)}: se puede cerrar desde ese día.`;
    default:
      return blocker.detail;
  }
}

/** Las cuentas a conciliar de una moneda, para mostrarlas agrupadas. No suma nada. */
export interface AccountGroup {
  currency: ClosingPreview['accounts'][number]['currency'];
  label: string;
  accounts: ClosingPreview['accounts'];
}

/** Agrupa por moneda conservando el orden del backend (ARS, USD). */
export function accountsByCurrency(accounts: ClosingPreview['accounts']): AccountGroup[] {
  const groups: AccountGroup[] = [];
  for (const account of accounts) {
    let group = groups.find((g) => g.currency === account.currency);
    if (!group) {
      group = { currency: account.currency, label: account.currency === 'USD' ? 'Dólares' : 'Pesos', accounts: [] };
      groups.push(group);
    }
    group.accounts.push(account);
  }
  return groups;
}
