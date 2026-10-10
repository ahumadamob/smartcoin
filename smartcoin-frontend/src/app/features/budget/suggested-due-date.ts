import { isPeriod } from './period-nav';

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

/**
 * Vencimiento que se sugiere al agregar una partida, siempre dentro del período que se está viendo (HU-16): en el mes
 * actual, el día de hoy; en cualquier otro, el día 1. Solo toma el día de `today`, no su mes, así nunca sale del
 * período aunque el reloj del navegador y el del backend no coincidan cerca de un cambio de mes. No valida nada: el
 * rango lo decide el backend (RN-19).
 *
 * @param period `YYYY-MM` del mes que se mira
 * @param currentPeriod `YYYY-MM` del período actual, que informa el backend
 */
export function suggestedDueDate(period: string, currentPeriod: string, today: Date): string {
  if (!isPeriod(period)) {
    throw new Error(`Período inválido: ${period}`);
  }
  if (period !== currentPeriod) {
    return `${period}-01`;
  }
  const [year, month] = period.split('-').map(Number);
  const lastDay = new Date(year, month, 0).getDate();
  return `${period}-${pad(Math.min(today.getDate(), lastDay))}`;
}
