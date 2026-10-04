import { formatDate } from '@angular/common';
import { inject, LOCALE_ID, Pipe, PipeTransform } from '@angular/core';

/** Muestra un período `YYYY-MM` como "noviembre 2026". */
@Pipe({ name: 'period' })
export class PeriodPipe implements PipeTransform {
  private readonly locale = inject(LOCALE_ID);

  transform(value: string | null | undefined): string {
    const match = /^(\d{4})-(0[1-9]|1[0-2])$/.exec(value ?? '');
    if (!match) {
      return value ?? '';
    }
    // Se arma en UTC para que la zona horaria del navegador no mueva el mes.
    const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, 1));
    return formatDate(date, "MMMM yyyy", this.locale, 'UTC');
  }
}
