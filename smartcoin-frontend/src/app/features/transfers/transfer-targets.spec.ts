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

  it('ofrece todas las cuentas distintas del origen, de cualquier moneda', () => {
    expect(targetAccounts(all, 1)).toEqual([cash, wallet, dollars]);
    expect(targetAccounts(all, 4)).toEqual([bank, cash, wallet]);
  });

  it('sin origen, o con un origen que no existe, no ofrece ninguna', () => {
    expect(targetAccounts(all, null)).toEqual([]);
    expect(targetAccounts(all, 99)).toEqual([]);
  });

  it('con una sola cuenta no hay destino', () => {
    expect(targetAccounts([bank], 1)).toEqual([]);
  });
});
