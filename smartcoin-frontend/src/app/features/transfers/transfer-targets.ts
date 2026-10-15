import { AccountResponse } from '../../api';

/**
 * Cuentas que se ofrecen como destino de una transferencia (HU-27, HU-28): todas las del usuario menos el origen,
 * de cualquier moneda. Con una de otra moneda es una compra o venta de dólares (RN-37). Solo filtra; el backend
 * vuelve a decidir (RN-36).
 */
export function targetAccounts(accounts: AccountResponse[], sourceId: number | null): AccountResponse[] {
  const source = accounts.find((account) => account.id === sourceId);
  if (!source) {
    return [];
  }
  return accounts.filter((account) => account.id !== source.id);
}
