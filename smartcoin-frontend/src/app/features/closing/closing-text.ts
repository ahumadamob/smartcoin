import { HttpErrorResponse } from '@angular/common/http';
import {
  ClosingAccount,
  ClosingBlocker,
  ClosingNewEntry,
  ClosingPendingEntry,
  ClosingPreview,
  ClosingResolutionChoice,
} from '../../api';
import { FormatMoney } from '../budget/movement-text';

/** Los pasos del asistente de cierre (Épica 8): pendientes, saldos y confirmación. */
export interface ClosingStep {
  label: string;
  /** Si el paso ya tiene contenido. Los que no, se muestran deshabilitados. */
  available: boolean;
}

/** HU-30 y HU-31 hacen el primer paso, HU-32 el segundo y HU-33 la confirmación, con el cierre efectivo. */
export const CLOSING_STEPS: readonly ClosingStep[] = [
  { label: 'Pendientes', available: true },
  { label: 'Saldos', available: true },
  { label: 'Confirmación', available: true },
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

// Saldos reales y diferencias (HU-32, RN-40 paso 3).

export const BACK_STEP_LABEL = 'Volver a Pendientes';

export const CONFIRMATION_STEP_LABEL = 'Siguiente: Confirmación';

/** Qué es el saldo real, en una línea. `lastDay` ya viene con formato de fecha. */
export function balancesIntro(lastDay: string): string {
  return (
    `El saldo real es el que figura en el banco o la billetera al último día del mes (${lastDay}). ` +
    'No se guarda nada hasta confirmar el cierre.'
  );
}

export const BALANCE_FORMAT_ERROR = 'Escribilo con coma decimal y hasta 2 decimales, por ejemplo 1500,50.';

/**
 * El efecto de la diferencia de una cuenta, tal como lo informa el backend: «Coincide.» o la partida de diferencia que
 * aparece en el mes siguiente. Sin saldo real en la simulación, nada. Solo arma el texto: no resta nada.
 */
export function differenceText(account: ClosingAccount, closingPeriod: string, money: FormatMoney): string | null {
  if (account.realBalance === null || account.realBalance === undefined) {
    return null;
  }
  const entry = account.differenceEntry;
  if (!entry) {
    return 'Coincide.';
  }
  const kind = entry.kind === 'INCOME' ? 'un ingreso' : 'un gasto';
  return (
    `En ${monthName(entry.period, closingPeriod)} aparece ${kind} «${entry.name}» por ` +
    `${money(entry.budgetedAmount, entry.currency)}.`
  );
}

export const CONFIRMATION_PENDING_TEXT = 'La confirmación estará disponible más adelante.';

/** Por qué todavía no se puede pasar a «Confirmación». */
export function confirmationHint(accountCount: number, missingCount: number, nextAvailable: boolean): string {
  if (missingCount > 0) {
    return missingCount === 1
      ? 'Falta cargar el saldo real de 1 cuenta.'
      : `Falta cargar el saldo real de ${missingCount} cuentas.`;
  }
  const done = accountCount === 0 ? 'No hay cuentas para conciliar.' : 'Todos los saldos reales están cargados.';
  return nextAvailable ? done : `${done} ${CONFIRMATION_PENDING_TEXT}`;
}

// Confirmación y cierre efectivo (HU-33, RN-40, D-45).

export const BACK_TO_BALANCES_LABEL = 'Volver a Saldos';

/** El aviso de que no hay vuelta atrás (D-23). `periodName` ya viene con formato («agosto 2026»). */
export function irreversibleWarning(periodName: string): string {
  return (
    `Al cerrar, ${periodName} queda cerrado para siempre: no se puede reabrir, y ya no se pueden crear, editar ni ` +
    'eliminar sus partidas, ni movimientos o transferencias con fecha en ese mes.'
  );
}

/** La casilla que habilita el botón de cierre (D-45). */
export function acknowledgeLabel(periodName: string): string {
  return `Entiendo que ${periodName} queda cerrado para siempre y no se puede reabrir.`;
}

export function closeButtonLabel(periodName: string): string {
  return `Cerrar ${periodName}`;
}

export const RESOLUTION_LABELS: Record<Resolution, string> = {
  CARRY_OVER: 'Postergar saldo',
  CLOSE_AS_IS: 'Cerrar con lo registrado',
};

/** Una partida pendiente en el resumen: con qué resolución y qué monto se consolida, según la simulación. */
export function consolidationText(entry: ClosingPendingEntry, money: FormatMoney): string {
  const resolution = entry.resolution ? RESOLUTION_LABELS[entry.resolution] : 'Sin resolución';
  const amount = entry.consolidatedAmount ?? entry.actualAmount;
  return `${resolution}: se consolida en ${money(amount, entry.currency)}.`;
}

/** Una partida que crea el cierre en el mes siguiente, en el resumen. */
export function newEntryText(entry: ClosingNewEntry, money: FormatMoney): string {
  const kind = entry.kind === 'INCOME' ? 'Ingreso' : 'Gasto';
  return `${kind} por ${money(entry.budgetedAmount, entry.currency)}, con vencimiento el ${dayAndMonth(entry.dueDate)}.`;
}

/** «Agosto 2026 quedó cerrado.»: `periodName` ya viene con formato. */
export function closedMessage(periodName: string): string {
  return `${periodName.charAt(0).toUpperCase()}${periodName.slice(1)} quedó cerrado.`;
}

/** El paso al que vuelve el asistente ante un error del cierre, según su `code` (D-45). */
export type CloseErrorStep = 0 | 1 | 2;

/**
 * Pendientes (0): las precondiciones, el mes ya cerrado y lo que tiene que ver con las resoluciones. Saldos (1): lo
 * que tiene que ver con los saldos reales. Cualquier otro error (sin conexión, 500) deja el asistente en Confirmación.
 */
export function stepForCloseError(error: unknown): CloseErrorStep {
  if (!(error instanceof HttpErrorResponse) || typeof error.error !== 'object' || error.error === null) {
    return 2;
  }
  const body = error.error as { code?: string; errors?: { field?: string }[] };
  switch (body.code) {
    case 'PERIOD_CLOSED':
    case 'PREVIOUS_PERIOD_OPEN':
    case 'PERIOD_NOT_FINISHED':
    case 'UNRESOLVED_PENDING_ENTRIES':
      return 0;
    case 'MISSING_REAL_BALANCE':
      return 1;
    case 'VALIDATION_ERROR':
      return body.errors?.[0]?.field === 'realBalances' ? 1 : 0;
    default:
      return 2;
  }
}
