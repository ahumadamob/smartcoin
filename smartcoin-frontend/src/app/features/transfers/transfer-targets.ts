import { AccountResponse } from '../../api';

/**
 * Cuentas que se ofrecen como destino de una transferencia de la misma moneda (HU-27): las de la moneda del origen y
 * distintas de él. Las de otra moneda son una compra o venta de dólares (HU-28). Solo filtra; el backend vuelve a
 * decidir (RN-36).
 */
export function targetAccounts(accounts: AccountResponse[], sourceId: number | null): AccountResponse[] {
  const source = accounts.find((account) => account.id === sourceId);
  if (!source) {
    return [];
  }
  return accounts.filter((account) => account.currency === source.currency && account.id !== source.id);
}
