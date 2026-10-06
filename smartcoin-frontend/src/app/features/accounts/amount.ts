/**
 * Saldo escrito por el usuario: coma decimal (hasta 2 decimales), punto opcional como separador de miles y signo
 * menos opcional. `1.234,50`, `-1500,5`, `0`. Un punto solo (`1.5`) no vale: sería ambiguo.
 */
const AMOUNT_PATTERN = /^-?(\d+|\d{1,3}(\.\d{3})+)(,\d{1,2})?$/;

/** Cantidad máxima de dígitos enteros para que el número no pierda precisión al enviarlo. */
const MAX_INTEGER_DIGITS = 15;

/** Convierte lo que escribió el usuario en un número, o `null` si no tiene el formato. No hace cuentas con montos. */
export function parseAmount(text: string): number | null {
  const value = text.trim();
  if (!AMOUNT_PATTERN.test(value)) {
    return null;
  }
  const [integer, decimals] = value.replace(/\./g, '').split(',');
  if (integer.replace('-', '').length > MAX_INTEGER_DIGITS) {
    return null;
  }
  return Number(decimals === undefined ? integer : `${integer}.${decimals}`);
}

/** Texto de un monto que vino del backend, para mostrarlo en el campo al editar: `-1500,50`. Solo da formato. */
export function formatAmountInput(amount: number): string {
  return amount.toFixed(2).replace('.', ',');
}
