import { Consolidation, ConsolidationPreview, PeriodEntry } from '../../api';
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
