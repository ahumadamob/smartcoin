import { BudgetItemEntryCounts } from '../../api';

/**
 * Qué va a pasar al cambiar el monto vigente de un Concepto (RN-15, S-11): reemplaza el presupuestado de las
 * partidas pendientes no editadas de los períodos abiertos; las editadas, las consolidadas y las de períodos cerrados
 * no cambian. Los conteos los calcula el backend; acá solo se arma el texto.
 *
 * @param from monto vigente actual, ya formateado
 * @param to monto vigente nuevo, ya formateado
 */
export function amountChangeMessage(from: string, to: string, counts: BudgetItemEntryCounts): string {
  const { pendingNotManual: replaced, pendingManual: edited } = counts;
  return [
    `Monto vigente: de ${from} a ${to}.`,
    replacedText(replaced),
    editedText(edited),
    'Las consolidadas y las de períodos cerrados tampoco cambian.',
  ].join(' ');
}

function replacedText(count: number): string {
  if (count === 0) {
    return 'Hoy no hay partidas pendientes sin editar en los períodos abiertos: el monto nuevo se usará en las partidas que se generen de ahora en adelante.';
  }
  const partidas = count === 1 ? '1 partida pendiente' : `${count} partidas pendientes`;
  return `Se reemplazará el monto presupuestado de ${partidas} sin editar de los períodos abiertos.`;
}

function editedText(count: number): string {
  if (count === 0) {
    return 'Las partidas editadas a mano no cambian (hoy no hay ninguna).';
  }
  return count === 1 ? 'La partida editada a mano no cambia.' : `Las ${count} partidas editadas a mano no cambian.`;
}
