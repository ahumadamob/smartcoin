import { Consolidation, ConsolidationPreview, EstimationBasis, PeriodEntry } from '../../api';
import { FormatMoney } from './movement-text';

/** Texto en pantalla de cada regla de estimación (docs/glosario.md). */
export const RULE_LABELS: Record<NonNullable<ConsolidationPreview['estimationRule']>, string> = {
  LAST_VALUE: 'Último valor',
  AVERAGE_LAST_3: 'Promedio de los últimos 3',
};

/** Lo que explica por qué el diálogo no deja confirmar cuando hay partidas editadas en los meses siguientes. */
export function manualPolicyText(count: number): string {
  const edited = count === 1 ? 'Hay 1 partida editada a mano' : `Hay ${count} partidas editadas a mano`;
  return (
    `${edited} en los meses siguientes y consolidar tendría que decidir qué hacer con ` +
    `${count === 1 ? 'ella' : 'ellas'}. Todavía no se puede elegir desde esta pantalla, así que no se puede ` +
    'consolidar esta partida por ahora.'
  );
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
