import { DeletionPlan, PeriodEntry } from '../../api';

/** Cómo se escribe un período `YYYY-MM` en pantalla («noviembre 2026»): el que usa el pipe `period`. */
export type PeriodFormatter = (period: string) => string;

/** El alcance de una eliminación (HU-18, RN-31). El backend lo define con este mismo vocabulario. */
export type Scope = 'ONLY_THIS' | 'THIS_AND_FUTURE';

export const SCOPE_LABELS: Record<Scope, string> = {
  ONLY_THIS: 'Solo este mes',
  THIS_AND_FUTURE: 'Este mes y los siguientes',
};

/** El botón de confirmar dice lo que va a hacer (HU-18). */
export function confirmLabel(scope: Scope | null, recurring: boolean): string {
  if (!recurring) {
    return 'Eliminar partida';
  }
  return scope === null ? 'Eliminar' : `Eliminar ${SCOPE_LABELS[scope].toLowerCase()}`;
}

/**
 * Qué hace «Solo este mes», con lo que informa la vista previa del backend: cuántas partidas se eliminan, y si el
 * Concepto sigue igual o desaparece (RN-31).
 */
export function onlyThisText(plan: DeletionPlan, name: string, month: PeriodFormatter): string {
  const base = `Se elimina solo la partida de ${month(plan.fromPeriod)}.`;
  if (plan.itemOutcome === 'REMOVES_ITEM') {
    return `${base} Es la única partida que le queda al Concepto «${name}» y no tiene nada más por generar: el Concepto también se elimina.`;
  }
  return `${base} El Concepto «${name}» sigue igual y esta partida no vuelve a aparecer.`;
}

/**
 * Qué hace «Este mes y los siguientes»: cuántas partidas se eliminan, desde qué mes, y si el Concepto termina (con
 * su fin nuevo) o desaparece.
 */
export function thisAndFutureText(plan: DeletionPlan, name: string, month: PeriodFormatter): string {
  const base =
    plan.entryCount === 1
      ? `Se elimina la partida de ${month(plan.fromPeriod)}.`
      : `Se eliminan las ${plan.entryCount} partidas de «${name}», de ${month(plan.fromPeriod)} a ${month(plan.toPeriod)}.`;
  if (plan.itemOutcome === 'REMOVES_ITEM') {
    return `${base} No queda ninguna partida, así que el Concepto «${name}» también se elimina.`;
  }
  const end = plan.newEndPeriod ? month(plan.newEndPeriod) : null;
  return end === null
    ? base
    : `${base} El Concepto «${name}» termina en ${end} y no genera más partidas; las anteriores no cambian.`;
}

/** Aviso de una partida sin Concepto según su origen: lo que significa eliminarla. */
export function originNote(origin: PeriodEntry.OriginEnum): string | null {
  switch (origin) {
    case 'CARRIED_OVER':
      return 'Es un saldo que quedó pendiente de un mes anterior: si la eliminás, deja de figurar en tu presupuesto.';
    case 'CLOSING_DIFFERENCE':
      return 'Es la diferencia que encontró el cierre de un mes: si la eliminás, deja de figurar en tu presupuesto. Si el saldo real de la cuenta sigue sin coincidir con el calculado, vuelve a aparecer al cerrar el mes.';
    default:
      return null;
  }
}

/** Aviso de lo que se hizo, para después de eliminar. */
export function successMessage(
  plan: DeletionPlan,
  scope: Scope | null,
  name: string,
  month: PeriodFormatter,
): string {
  if (scope === null) {
    return `Partida «${name}» eliminada.`;
  }
  if (scope === 'ONLY_THIS') {
    return plan.itemOutcome === 'REMOVES_ITEM'
      ? `Se eliminó «${name}» de ${month(plan.fromPeriod)} y el Concepto, que no tenía más partidas.`
      : `Se eliminó «${name}» de ${month(plan.fromPeriod)}.`;
  }
  const count =
    plan.entryCount === 1
      ? `la partida de «${name}» de ${month(plan.fromPeriod)}`
      : `${plan.entryCount} partidas de «${name}»`;
  const verb = plan.entryCount === 1 ? 'Se eliminó' : 'Se eliminaron';
  if (plan.itemOutcome === 'REMOVES_ITEM') {
    return `${verb} ${count} y el Concepto, que no tenía más partidas.`;
  }
  return plan.newEndPeriod
    ? `${verb} ${count}. El Concepto termina en ${month(plan.newEndPeriod)}.`
    : `${verb} ${count}.`;
}
