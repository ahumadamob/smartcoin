import { ConsolidateRequest, Consolidation, ConsolidationPreview, EstimationBasis, PeriodEntry } from '../../api';
import { FormatMoney } from './movement-text';

/** Texto en pantalla de cada regla de estimación (docs/glosario.md). */
export const RULE_LABELS: Record<NonNullable<ConsolidationPreview['estimationRule']>, string> = {
  LAST_VALUE: 'Último valor',
  AVERAGE_LAST_3: 'Promedio de los últimos 3',
};

/** Elección que se envía como `manualEntriesPolicy` (glosario: Respetar → `KEEP`, Pisar → `OVERWRITE`). */
export type ManualPolicy = NonNullable<ConsolidateRequest['manualEntriesPolicy']>;

/** Texto en pantalla de cada opción (docs/glosario.md). */
export const POLICY_LABELS: Record<ManualPolicy, string> = { KEEP: 'Respetar', OVERWRITE: 'Pisar' };

const plural = (count: number, one: string, many: string) => `${count} ${count === 1 ? one : many}`;

/** Lo que pide el diálogo cuando hay partidas editadas a mano en los meses siguientes. */
export function manualPolicyText(count: number): string {
  const edited = count === 1 ? 'Hay 1 partida editada a mano' : `Hay ${count} partidas editadas a mano`;
  return (
    `${edited} en los meses siguientes. Elegí qué hacer con ${count === 1 ? 'ella' : 'ellas'}: ` +
    'la elección vale para todas.'
  );
}

/**
 * Qué hace «Respetar». Los recuentos salen de los indicadores del backend: `edited` son las editadas y
 * `othersChanging` las no editadas cuyo presupuestado cambia (esas cambian con las dos opciones).
 */
export function keepText(edited: number, othersChanging: number): string {
  const kept = edited === 1 ? 'La partida editada conserva su monto y sigue editada' : `Las ${edited} partidas editadas conservan su monto y siguen editadas`;
  return `${kept}. ${othersCountText(othersChanging)}`;
}

/**
 * Qué hace «Pisar». `editedChanging` son las editadas cuyo monto es distinto de la estimación: las demás ya tienen
 * ese monto y solo pierden la marca (D-31).
 */
export function overwriteText(edited: number, editedChanging: number, othersChanging: number): string {
  const sameAmount = edited - editedChanging;
  let what: string;
  if (edited === 1) {
    what =
      sameAmount === 0
        ? 'La partida editada toma la nueva estimación y deja de estar editada'
        : 'La partida editada ya tiene ese monto: solo deja de estar editada';
  } else {
    what = `Las ${edited} partidas editadas toman la nueva estimación y dejan de estar editadas`;
    if (sameAmount > 0) {
      what += ` (${plural(sameAmount, 'ya tiene ese monto y solo pierde', 'ya tienen ese monto y solo pierden')} la marca)`;
    }
  }
  return `${what}. ${othersCountText(othersChanging)}`;
}

function othersCountText(othersChanging: number): string {
  return othersChanging === 0
    ? 'Ninguna otra partida cambia de monto.'
    : `${plural(othersChanging, 'otra partida cambia', 'otras partidas cambian')} de monto.`;
}

/** Texto bajo la lista cuando ninguna partida de los meses siguientes cambia. */
export function noChangesText(targetCount: number): string {
  return targetCount === 0
    ? 'No hay partidas pendientes en los meses siguientes: no cambia ningún otro mes.'
    : 'Las partidas de los meses siguientes ya tienen ese valor: no cambia ningún otro mes.';
}

/** Aviso al consolidar. Todos los montos y la cuenta salen de la respuesta del backend. */
export function consolidatedMessage(result: Consolidation, money: FormatMoney): string {
  const entry: PeriodEntry = result.entry;
  const done = `«${entry.name}» consolidada por ${money(entry.forecastAmount, entry.currency)}.`;
  if (result.estimate === null || result.estimate === undefined) {
    return done;
  }
  return result.updatedEntries === 0
    ? `${done} No cambió ningún mes siguiente.`
    : `${done} Se actualizaron ${result.updatedEntries} ${result.updatedEntries === 1 ? 'partida' : 'partidas'} de los meses siguientes.`;
}

/** Cuántas consolidaciones promedia como máximo «Promedio de los últimos 3». */
const AVERAGE_SIZE = 3;

/**
 * Una línea que explica cómo se obtuvo la nueva estimación con «Promedio de los últimos 3». Los períodos son los que
 * informa el backend (`estimateBasis`); acá solo se nombran. Con menos de 3 aclara que promedia las que hay.
 * `formatPeriod` da «agosto 2026»; el año se omite si todos son del mismo.
 */
export function averageBasisText(basis: EstimationBasis[], formatPeriod: (period: string) => string): string {
  if (basis.length === 0) {
    return '';
  }
  const periods = [...basis].map((entry) => entry.period).sort();
  const sameYear = new Set(periods.map((period) => period.slice(0, 4))).size === 1;
  const names = periods.map((period) => {
    const label = formatPeriod(period);
    return sameYear ? label.replace(/\s+\d{4}$/, '') : label;
  });
  const list = names.length === 1 ? names[0] : `${names.slice(0, -1).join(', ')} y ${names[names.length - 1]}`;
  if (basis.length >= AVERAGE_SIZE) {
    return `Promedio de ${list}.`;
  }
  const only = basis.length === 1 ? 'la única consolidada' : 'las únicas consolidadas';
  return (
    `Promedio de ${list}: ${only} hasta ahora. Se promedian las que hay y, con más consolidaciones, ` +
    `las ${AVERAGE_SIZE} más recientes.`
  );
}
