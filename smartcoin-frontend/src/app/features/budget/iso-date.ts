import { AbstractControl, ValidationErrors } from '@angular/forms';

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * Fecha `YYYY-MM-DD` que existe en el calendario. Solo el formato: el rango lo decide el backend (RN-21). Lo usan los
 * diálogos que piden la fecha de un movimiento (registrar y pago rápido).
 */
export function isoDate(control: AbstractControl): ValidationErrors | null {
  const text = control.value as string;
  if (text === '') {
    return null;
  }
  const match = ISO_DATE.exec(text);
  if (match) {
    const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
    const date = new Date(year, month - 1, day);
    if (date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day) {
      return null;
    }
  }
  return { date: true };
}
