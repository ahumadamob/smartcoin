import { formatCurrency } from '@angular/common';
import { inject, LOCALE_ID, Pipe, PipeTransform } from '@angular/core';

export type CurrencyCode = 'ARS' | 'USD';

const SYMBOLS: Record<CurrencyCode, string> = { ARS: '$', USD: 'US$' };

/**
 * Formatea un monto que ya viene calculado del backend: `$ 1.234,50` o `US$ 1.234,50`.
 * Solo da formato; no hace cuentas.
 */
@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  private readonly locale = inject(LOCALE_ID);

  transform(value: number | string | null | undefined, currency: CurrencyCode): string {
    if (value === null || value === undefined || value === '') {
      return '';
    }
    return formatCurrency(Number(value), this.locale, SYMBOLS[currency], currency, '1.2-2')
      .replace(/ /g, ' ');
  }
}
