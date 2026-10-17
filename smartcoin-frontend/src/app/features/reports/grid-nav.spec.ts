import { nextCell } from './grid-nav';

describe('nextCell', () => {
  const at = (row: number, column: number) => ({ row, column });

  it('las flechas mueven una celda', () => {
    expect(nextCell(at(1, 1), 'ArrowRight', false, 3, 4)).toEqual(at(1, 2));
    expect(nextCell(at(1, 1), 'ArrowLeft', false, 3, 4)).toEqual(at(1, 0));
    expect(nextCell(at(1, 1), 'ArrowDown', false, 3, 4)).toEqual(at(2, 1));
    expect(nextCell(at(1, 1), 'ArrowUp', false, 3, 4)).toEqual(at(0, 1));
  });

  it('se detiene en los bordes', () => {
    expect(nextCell(at(0, 0), 'ArrowLeft', false, 3, 4)).toEqual(at(0, 0));
    expect(nextCell(at(0, 0), 'ArrowUp', false, 3, 4)).toEqual(at(0, 0));
    expect(nextCell(at(2, 3), 'ArrowRight', false, 3, 4)).toEqual(at(2, 3));
    expect(nextCell(at(2, 3), 'ArrowDown', false, 3, 4)).toEqual(at(2, 3));
  });

  it('Inicio y Fin van a los extremos de la fila; con Ctrl, de la grilla', () => {
    expect(nextCell(at(1, 2), 'Home', false, 3, 4)).toEqual(at(1, 0));
    expect(nextCell(at(1, 2), 'End', false, 3, 4)).toEqual(at(1, 3));
    expect(nextCell(at(1, 2), 'Home', true, 3, 4)).toEqual(at(0, 0));
    expect(nextCell(at(1, 2), 'End', true, 3, 4)).toEqual(at(2, 3));
  });

  it('otras teclas no mueven el foco', () => {
    expect(nextCell(at(1, 1), 'Enter', false, 3, 4)).toBeNull();
    expect(nextCell(at(1, 1), 'a', false, 3, 4)).toBeNull();
  });

  it('una grilla vacía no tiene a dónde ir', () => {
    expect(nextCell(at(0, 0), 'ArrowDown', false, 0, 0)).toBeNull();
  });
});
