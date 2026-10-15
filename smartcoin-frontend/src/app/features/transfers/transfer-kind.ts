import { Transfer } from '../../api';

type Currency = Transfer['sourceCurrency'];

/**
 * Texto de interfaz de una transferencia entre monedas distintas (HU-28, D-40): de pesos a dólares es una compra
 * y de dólares a pesos, una venta. Se deriva de las monedas; no es un dato. Nulo si las monedas son iguales.
 */
export function exchangeKind(source: Currency, target: Currency): string | null {
  if (source === target) {
    return null;
  }
  return source === 'ARS' ? 'Compra de dólares' : 'Venta de dólares';
}
