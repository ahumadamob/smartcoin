import { UrlMatchResult, UrlSegment } from '@angular/router';

/**
 * `/presupuesto` y `/presupuesto/:period` como una sola ruta. Con dos rutas, Angular destruye y vuelve a crear la
 * pantalla al pasar de una a otra, y el botón «Mes siguiente» pierde el foco del teclado en el primer cambio de mes.
 */
export function budgetMonthMatcher(segments: UrlSegment[]): UrlMatchResult | null {
  if (segments.length === 0 || segments.length > 2 || segments[0].path !== 'presupuesto') {
    return null;
  }
  return { consumed: segments, posParams: segments.length === 2 ? { period: segments[1] } : {} };
}
