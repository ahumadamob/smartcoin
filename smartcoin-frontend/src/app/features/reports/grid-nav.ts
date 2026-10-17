/** Posición de una celda de la grilla: fila y columna, desde 0. */
export interface CellPosition {
  row: number;
  column: number;
}

/**
 * A dónde va el foco con una tecla dentro de una grilla de `rows` × `columns` celdas (HU-36). Flechas: una celda; Inicio
 * y Fin: primera y última de la fila; Ctrl+Inicio y Ctrl+Fin: primera y última de la grilla. Se detiene en los bordes
 * y devuelve `null` si la tecla no mueve el foco. Función pura, para probarla sin el DOM.
 */
export function nextCell(
  from: CellPosition,
  key: string,
  ctrl: boolean,
  rows: number,
  columns: number,
): CellPosition | null {
  if (rows === 0 || columns === 0) {
    return null;
  }
  const lastRow = rows - 1;
  const lastColumn = columns - 1;
  switch (key) {
    case 'ArrowLeft':
      return { row: from.row, column: Math.max(from.column - 1, 0) };
    case 'ArrowRight':
      return { row: from.row, column: Math.min(from.column + 1, lastColumn) };
    case 'ArrowUp':
      return { row: Math.max(from.row - 1, 0), column: from.column };
    case 'ArrowDown':
      return { row: Math.min(from.row + 1, lastRow), column: from.column };
    case 'Home':
      return { row: ctrl ? 0 : from.row, column: 0 };
    case 'End':
      return { row: ctrl ? lastRow : from.row, column: lastColumn };
    default:
      return null;
  }
}
