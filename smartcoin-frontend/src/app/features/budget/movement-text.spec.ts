import { PeriodEntry } from '../../api';
import { coveredNotice, movementWord, registeredMessage, registerLabel } from './movement-text';

const money = (amount: number, currency: 'ARS' | 'USD') =>
  `${currency === 'USD' ? 'US$' : '$'} ${amount.toFixed(2).replace('.', ',')}`;

const entry = (overrides: Partial<PeriodEntry>): PeriodEntry =>
  ({ id: 1, kind: 'EXPENSE', name: 'Expensas', currency: 'ARS', pendingAmount: 50000, ...overrides }) as PeriodEntry;

describe('textos de los movimientos (HU-19)', () => {
  it('un ingreso se cobra y un gasto se paga', () => {
    expect(movementWord('INCOME')).toBe('cobro');
    expect(movementWord('EXPENSE')).toBe('pago');
    expect(registerLabel('INCOME')).toBe('Registrar cobro');
    expect(registerLabel('EXPENSE')).toBe('Registrar pago');
  });

  it('el aviso de cubierta usa el monto que informa el backend y no promete una acción', () => {
    const text = coveredNotice('Expensas', 0, 'ARS', money);

    expect(text).toBe('«Expensas» ya está cubierta: pendiente $ 0,00. Sigue Parcial hasta que se consolide.');
    expect(text).not.toContain('Consolidar');
  });

  it('el mensaje de un pago con pendiente dice solo lo que se registró', () => {
    expect(registeredMessage(entry({}), 70000, money)).toBe('Pago de $ 70000,00 registrado en «Expensas».');
  });

  it('el mensaje de un cobro en dólares lleva el símbolo de la moneda', () => {
    const income = entry({ kind: 'INCOME', currency: 'USD', name: 'Alquiler' });

    expect(registeredMessage(income, 500, money)).toBe('Cobro de US$ 500,00 registrado en «Alquiler».');
  });

  it('si el pendiente llegó a 0, el mensaje suma el aviso', () => {
    const message = registeredMessage(entry({ pendingAmount: 0 }), 120000, money);

    expect(message).toBe(
      'Pago de $ 120000,00 registrado en «Expensas». «Expensas» ya está cubierta: pendiente $ 0,00. Sigue Parcial hasta que se consolide.',
    );
  });
});
