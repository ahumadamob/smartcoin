import { AccountRequest } from '../../api';

/** Texto en pantalla de cada tipo de cuenta (docs/glosario.md). */
export const ACCOUNT_TYPE_LABELS: Record<AccountRequest.TypeEnum, string> = {
  BANK: 'Banco',
  DIGITAL_WALLET: 'Billetera virtual',
  CASH: 'Efectivo',
};

export const ACCOUNT_TYPES = Object.keys(ACCOUNT_TYPE_LABELS) as AccountRequest.TypeEnum[];

/** Monedas con su símbolo (docs/glosario.md), en el orden en que se agrupan en la lista. */
export const CURRENCY_LABELS: Record<AccountRequest.CurrencyEnum, string> = {
  ARS: '$ (ARS)',
  USD: 'US$ (USD)',
};

export const CURRENCIES = Object.keys(CURRENCY_LABELS) as AccountRequest.CurrencyEnum[];
