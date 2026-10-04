import { formatDate } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { DATE_FORMAT, provideLocale } from '../../core/locale';
import { MoneyPipe } from './money.pipe';
import { PeriodPipe } from './period.pipe';

describe('formatos es-AR', () => {
  let money: MoneyPipe;
  let period: PeriodPipe;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideLocale(), MoneyPipe, PeriodPipe] });
    money = TestBed.inject(MoneyPipe);
    period = TestBed.inject(PeriodPipe);
  });

  describe('MoneyPipe', () => {
    it('formatea pesos como "$ 1.234,50"', () => {
      expect(money.transform(1234.5, 'ARS')).toBe('$ 1.234,50');
    });

    it('formatea dólares como "US$ 1.234,50"', () => {
      expect(money.transform(1234.5, 'USD')).toBe('US$ 1.234,50');
    });

    it('siempre muestra 2 decimales y separa los miles', () => {
      expect(money.transform(0, 'ARS')).toBe('$ 0,00');
      expect(money.transform(1234567.1, 'ARS')).toBe('$ 1.234.567,10');
    });

    it('formatea negativos', () => {
      expect(money.transform(-1234.5, 'ARS')).toBe('-$ 1.234,50');
    });

    it('acepta el monto como texto', () => {
      expect(money.transform('1234.50', 'USD')).toBe('US$ 1.234,50');
    });

    it('devuelve vacío sin monto', () => {
      expect(money.transform(null, 'ARS')).toBe('');
      expect(money.transform(undefined, 'USD')).toBe('');
    });
  });

  describe('fechas', () => {
    it('usa dd/MM/yyyy', () => {
      expect(formatDate('2026-11-05T12:00:00', DATE_FORMAT, 'es-AR')).toBe('05/11/2026');
    });
  });

  describe('PeriodPipe', () => {
    it('muestra el mes en español', () => {
      expect(period.transform('2026-11')).toBe('noviembre 2026');
      expect(period.transform('2027-01')).toBe('enero 2027');
    });

    it('devuelve el valor tal cual si no es un período', () => {
      expect(period.transform('abc')).toBe('abc');
      expect(period.transform(null)).toBe('');
    });
  });
});
