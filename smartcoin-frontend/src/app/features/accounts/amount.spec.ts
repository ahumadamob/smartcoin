import { formatAmountInput, parseAmount } from './amount';

describe('parseAmount', () => {
  it.each([
    ['0', 0],
    ['150000', 150000],
    ['1500,5', 1500.5],
    ['1500,50', 1500.5],
    ['-250,75', -250.75],
    ['-0,5', -0.5],
    ['1.234,50', 1234.5],
    ['1.234.567', 1234567],
    ['  42,10  ', 42.1],
  ])('"%s" es %d', (text, expected) => {
    expect(parseAmount(text)).toBe(expected);
  });

  it.each([
    [''],
    ['abc'],
    ['1,234,5'],
    ['1,234'],
    ['1.5'],
    ['1.50'],
    ['12.34'],
    ['1500.50'],
    ['--5'],
    ['+5'],
    ['5,'],
    [',5'],
    ['1 000'],
    ['1234567890123456'],
  ])('"%s" no es un monto válido', (text) => {
    expect(parseAmount(text)).toBeNull();
  });
});

describe('formatAmountInput', () => {
  it.each([
    [1500.5, '1500,50'],
    [-1500.5, '-1500,50'],
    [0, '0,00'],
    [150000, '150000,00'],
  ])('%d se muestra como %s', (amount, expected) => {
    expect(formatAmountInput(amount)).toBe(expected);
  });

  it('lo que se muestra se vuelve a leer igual', () => {
    expect(parseAmount(formatAmountInput(-1500.5))).toBe(-1500.5);
  });
});
