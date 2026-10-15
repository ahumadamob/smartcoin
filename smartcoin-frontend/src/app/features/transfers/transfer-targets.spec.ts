import { AccountResponse } from '../../api';
import { targetAccounts } from './transfer-targets';

const FREE = { editable: true, reason: null };

function account(id: number, name: string, currency: 'ARS' | 'USD'): AccountResponse {
  return {
    id,
    name,
    type: 'BANK',
    currency,
    openingDate: '2026-08-01',
    initialBalance: 0,
    currentBalance: 0,
    editability: { currency: FREE, initialBalance: FREE, openingDate: FREE },
  };
}

describe('targetAccounts', () => {
  const bank = account(1, 'Banco', 'ARS');
  const cash = account(2, 'Efectivo', 'ARS');
  const wallet = account(3, 'Billetera', 'ARS');
  const dollars = account(4, 'Dólares', 'USD');
  const all = [bank, cash, wallet, dollars];

  it('ofrece las cuentas de la misma moneda y distintas del origen', () => {
    expect(targetAccounts(all, 1)).toEqual([cash, wallet]);
  });

  it('no ofrece las de otra moneda (compra y venta de dólares son de HU-28)', () => {
    expect(targetAccounts(all, 1)).not.toContain(dollars);
    expect(targetAccounts(all, 4)).toEqual([]);
  });

  it('sin origen, o con un origen que no existe, no ofrece ninguna', () => {
    expect(targetAccounts(all, null)).toEqual([]);
    expect(targetAccounts(all, 99)).toEqual([]);
  });

  it('con una sola cuenta de esa moneda no hay destino', () => {
    expect(targetAccounts([bank, dollars], 1)).toEqual([]);
  });
});
