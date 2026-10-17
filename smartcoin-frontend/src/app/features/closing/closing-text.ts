import { ClosingBlocker, ClosingPendingEntry, ClosingPreview, ClosingResolutionChoice } from '../../api';
import { FormatMoney } from '../budget/movement-text';

/** Los pasos del asistente de cierre (Épica 8): pendientes, saldos y confirmación. */
export interface ClosingStep {
  label: string;
  /** Si el paso ya tiene contenido. Los que no, se muestran deshabilitados. */
  available: boolean;
}

/** HU-30 y HU-31 hacen el primer paso; los otros dos llegan con la conciliación (HU-32) y el cierre (HU-33). */
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

// Resoluciones de las partidas pendientes (HU-31, RN-40 pasos 1 y 2).

export type Resolution = ClosingResolutionChoice.ResolutionEnum;

export interface ResolutionOption {
  value: Resolution;
  label: string;
  description: string;
}

/** Las dos resoluciones, con una línea que explica cada una. */
export const RESOLUTION_OPTIONS: readonly ResolutionOption[] = [
  {
    value: 'CARRY_OVER',
    label: 'Postergar saldo',
    description: 'Se consolida con lo registrado y lo que falta pasa al mes siguiente como una partida nueva.',
  },
  {
    value: 'CLOSE_AS_IS',
    label: 'Cerrar con lo registrado',
    description: 'Se consolida con lo registrado, aunque sea 0, y lo que falta se descarta.',
  },
];

export const RESOLUTIONS_INTRO =
  'Elegí qué hacer con cada partida pendiente. Ninguna de las dos opciones cambia la estimación de los meses ' +
  'siguientes, y no se guarda nada hasta confirmar el cierre.';

/** Con pendiente 0 las dos opciones hacen lo mismo: consolidar con lo registrado sin crear nada. */
export function sameEffectNote(entry: ClosingPendingEntry): string | null {
  return entry.pendingAmount === 0 ? 'No queda nada pendiente: las dos opciones tienen el mismo efecto.' : null;
}

const MONTHS = [
  'enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio',
  'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre',
];

/** «noviembre», o «enero 2027» si el mes es de otro año que el que se cierra. Solo arma el texto. */
export function monthName(period: string, closingPeriod: string): string {
  const [year, month] = period.split('-');
  const name = MONTHS[Number(month) - 1];
  return year === closingPeriod.split('-')[0] ? name : `${name} ${year}`;
}

/**
 * Lo que va a pasar con una partida según la simulación del backend: con qué monto queda consolidada y, al postergar
 * con pendiente, la partida que aparece en el mes siguiente. Sin resolución en la simulación, nada.
 */
export function effectText(entry: ClosingPendingEntry, closingPeriod: string, money: FormatMoney): string | null {
  if (!entry.resolution || entry.consolidatedAmount === null || entry.consolidatedAmount === undefined) {
    return null;
  }
  const consolidated = `Queda consolidada en ${money(entry.consolidatedAmount, entry.currency)}.`;
  const created = entry.carriedOverEntry;
  if (entry.resolution === 'CARRY_OVER') {
    return created
      ? `${consolidated} En ${monthName(created.period, closingPeriod)} aparece «${created.name}» por ` +
          `${money(created.budgetedAmount, created.currency)}, con vencimiento el ${dayAndMonth(created.dueDate)}.`
      : `${consolidated} No queda nada pendiente: no se crea ninguna partida.`;
  }
  return entry.pendingAmount > 0
    ? `${consolidated} Se descarta el pendiente de ${money(entry.pendingAmount, entry.currency)}.`
    : consolidated;
}

export const NEXT_STEP_LABEL = 'Siguiente: Saldos';

/** Por qué todavía no se puede pasar a «Saldos». */
export function nextStepHint(pendingCount: number, unresolvedCount: number, nextAvailable: boolean): string {
  if (unresolvedCount > 0) {
    return unresolvedCount === 1
      ? 'Falta elegir qué hacer con 1 partida pendiente.'
      : `Falta elegir qué hacer con ${unresolvedCount} partidas pendientes.`;
  }
  const done = pendingCount === 0 ? 'No hay nada que resolver.' : 'Todas las partidas pendientes tienen una resolución.';
  return nextAvailable ? done : `${done} El paso «Saldos» estará disponible más adelante.`;
}

export const REMOVED_ENTRIES_NOTICE =
  'Algunas partidas dejaron de estar pendientes en este mes (por ejemplo, se consolidaron en otra pestaña) y se ' +
  'quitaron de la lista.';
