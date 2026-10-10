import { MovementDates, PeriodEntry } from '../../api';
import {
  coveredNotice,
  movementWord,
  notOpenYetNotice,
  registeredMessage,
  registerLabel,
  suggestedDate,
  windowNotice,
  windowNotOpenYet,
} from './movement-text';

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

describe('fecha sugerida y ventana de anticipación (HU-20)', () => {
  const dates = (earliestDate: string, latestDate: string): MovementDates => ({ earliestDate, latestDate, earlyDays: 10 });

  it('la fecha sugerida es hoy según el backend', () => {
    expect(suggestedDate(dates('2026-11-21', '2026-11-30'))).toBe('2026-11-30');
    expect(suggestedDate(dates('2026-10-22', '2026-11-20'))).toBe('2026-11-20');
  });

  it('la ventana abrió cuando la fecha más temprana no es posterior a hoy (el día exacto cuenta)', () => {
    expect(windowNotOpenYet(dates('2026-11-21', '2026-11-20'))).toBe(true);
    expect(windowNotOpenYet(dates('2026-11-21', '2026-11-21'))).toBe(false);
    expect(windowNotOpenYet(dates('2026-11-21', '2026-11-30'))).toBe(false);
    // Cruce de año.
    expect(windowNotOpenYet(dates('2026-12-22', '2026-12-21'))).toBe(true);
    expect(windowNotOpenYet(dates('2026-12-22', '2027-01-02'))).toBe(false);
  });

  it('el texto de la ventana usa los días que informa el backend', () => {
    expect(windowNotice(10, 'diciembre 2026', '21/11/2026')).toBe(
      'Se puede fechar hasta 10 días antes del inicio de diciembre 2026. Fecha más temprana con esta cuenta: 21/11/2026.',
    );
    expect(windowNotice(3, 'diciembre 2026', '28/11/2026')).toContain('hasta 3 días antes');
    expect(windowNotice(1, 'diciembre 2026', '30/11/2026')).toContain('hasta 1 día antes');
  });

  it('con ventana 0 no habla de días antes', () => {
    const text = windowNotice(0, 'diciembre 2026', '01/12/2026');

    expect(text).toBe('No se puede fechar antes del inicio de diciembre 2026. Fecha más temprana con esta cuenta: 01/12/2026.');
  });

  it('el aviso de ventana sin abrir dice desde cuándo', () => {
    expect(notOpenYetNotice('21/11/2026')).toBe(
      'Todavía no se pueden registrar movimientos de esta partida: se admiten desde el 21/11/2026.',
    );
  });
});
