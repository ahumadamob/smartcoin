import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BudgetGrid, GridCell } from '../../api';
import { BudgetGridTable } from './budget-grid-table';

function cell(overrides: Partial<GridCell> = {}): GridCell {
  return {
    entryId: 1,
    entryCount: 1,
    currency: 'ARS',
    forecast: 180000,
    status: 'ESTIMATED',
    manual: false,
    consolidated: false,
    ...overrides,
  };
}

function grid(overrides: Partial<BudgetGrid> = {}): BudgetGrid {
  return {
    from: '2026-10',
    to: '2026-12',
    currentPeriod: '2026-10',
    startPeriod: '2026-08',
    horizon: '2028-10',
    months: [
      { period: '2026-10', status: 'CLOSED' },
      { period: '2026-11', status: 'OPEN' },
      { period: '2026-12', status: 'OPEN' },
    ],
    rows: [
      {
        budgetItemId: 1,
        name: 'Sueldo',
        kind: 'INCOME',
        currency: 'ARS',
        cells: [cell({ entryId: 10, forecast: 1000000 }), cell({ entryId: 11, forecast: 1000000 }), null as never],
      },
      {
        budgetItemId: 2,
        name: 'Resumen Visa',
        kind: 'EXPENSE',
        currency: 'ARS',
        cells: [
          cell({ entryId: 20, forecast: 240000, manual: true }),
          cell({ entryId: 21, forecast: 180000, status: 'CONSOLIDATED', consolidated: true }),
          cell({ entryId: 22, forecast: 90000, status: 'PARTIAL' }),
        ],
      },
      {
        budgetItemId: 3,
        name: 'Hosting',
        kind: 'EXPENSE',
        currency: 'USD',
        cells: [null as never, cell({ entryId: 30, currency: 'USD', forecast: 25.5 }), null as never],
      },
      {
        origin: 'ONE_OFF',
        kind: 'EXPENSE',
        currency: 'ARS',
        cells: [
          cell({ entryId: null as never, entryCount: 2, forecast: 15000.5, status: null as never }),
          null as never,
          null as never,
        ],
      },
    ] as BudgetGrid['rows'],
    totals: [
      {
        period: '2026-10',
        currencies: [{ currency: 'ARS', incomeForecast: 1000000, expenseForecast: 255000.5, result: 744999.5 }],
      },
      {
        period: '2026-11',
        currencies: [
          { currency: 'ARS', incomeForecast: 1000000, expenseForecast: 180000, result: 820000 },
          { currency: 'USD', incomeForecast: 0, expenseForecast: 25.5, result: -25.5 },
        ],
      },
      { period: '2026-12', currencies: [{ currency: 'ARS', incomeForecast: 0, expenseForecast: 90000, result: -90000 }] },
    ],
    ...overrides,
  };
}

describe('BudgetGridTable', () => {
  let fixture: ComponentFixture<BudgetGridTable>;
  let opened: string[];

  async function setup(value: BudgetGrid = grid()) {
    registerLocaleData(localeEsAr, 'es-AR');
    await TestBed.configureTestingModule({
      imports: [BudgetGridTable],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    fixture = TestBed.createComponent(BudgetGridTable);
    fixture.componentRef.setInput('grid', value);
    opened = [];
    fixture.componentInstance.openMonth.subscribe((period) => opened.push(period));
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  // Los textos de cada elemento se separan con un espacio, como los leería un lector de pantalla.
  const text = (el: Element | null | undefined) => {
    if (!el) {
      return '';
    }
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    const parts: string[] = [];
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const value = (node.textContent ?? '').replace(/\s+/g, ' ').trim();
      if (value) {
        parts.push(value);
      }
    }
    return parts.join(' ');
  };
  const gridRows = () => Array.from(root().querySelectorAll('[data-testid="grid-row"]'));
  const cellAt = (row: number, column: number) =>
    root().querySelector<HTMLElement>(`[data-testid="cell"][data-row="${row}"][data-column="${column}"]`)!;
  const press = (el: HTMLElement, key: string, init: KeyboardEventInit = {}) => {
    el.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init }));
    fixture.detectChanges();
  };

  it('muestra los meses en columnas, con «Cerrado» y «Mes actual» como texto', async () => {
    await setup();

    const heads = Array.from(root().querySelectorAll('[data-testid="month-head"]')).map(text);
    expect(heads[0].toLowerCase()).toBe('octubre 2026 cerrado mes actual');
    expect(heads[1].toLowerCase()).toBe('noviembre 2026');
    expect(heads.length).toBe(3);
  });

  it('separa ingresos y gastos, con los Conceptos y debajo las partidas sin Concepto', async () => {
    await setup();

    expect(Array.from(root().querySelectorAll('[data-testid="section"]')).map(text)).toEqual(['Ingresos', 'Gastos']);
    expect(gridRows().map((r) => text(r.querySelector('[data-testid="row-name"]')))).toEqual([
      'Sueldo Pesos',
      'Resumen Visa Pesos',
      'Hosting Dólares',
      'Puntuales Pesos',
    ]);
  });

  it('muestra el estimado de cada celda con la moneda de la partida', async () => {
    await setup();

    expect(text(cellAt(0, 0))).toContain('$ 1.000.000,00');
    expect(text(cellAt(2, 1))).toContain('US$ 25,50');
    expect(text(cellAt(3, 0))).toContain('$ 15.000,50');
  });

  it('una celda sin partida muestra «—» y no 0', async () => {
    await setup();

    const empty = cellAt(0, 2);
    expect(text(empty)).toBe('— Sin partida');
    expect(text(empty)).not.toContain('0');
  });

  it('marca la partida editada, la consolidada y la parcial con texto', async () => {
    await setup();

    expect(text(cellAt(1, 0).querySelector('[data-testid="mark-edited"]'))).toBe('Editada');
    expect(cellAt(1, 0).querySelector('[data-testid="mark-consolidated"]')).toBeNull();
    expect(text(cellAt(1, 1).querySelector('[data-testid="mark-consolidated"]'))).toBe('Consolidada');
    expect(text(cellAt(1, 2).querySelector('[data-testid="mark-partial"]'))).toBe('Parcial');
    expect(cellAt(0, 0).querySelector('.mark')).toBeNull();
  });

  it('una celda que suma varias partidas dice cuántas', async () => {
    await setup();

    expect(text(cellAt(3, 0).querySelector('[data-testid="count"]'))).toBe('2 partidas');
    expect(cellAt(0, 0).querySelector('[data-testid="count"]')).toBeNull();
  });

  it('muestra los totales por mes y moneda tal como vienen, sin sumar', async () => {
    await setup();

    const rows = Array.from(root().querySelectorAll('[data-testid="total-row"]'));
    expect(rows.length).toBe(6);
    expect(text(rows[0])).toBe('Ingresos estimados $ $ 1.000.000,00 $ 1.000.000,00 $ 0,00');
    expect(text(rows[1])).toBe('Gastos estimados $ $ 255.000,50 $ 180.000,00 $ 90.000,00');
    expect(text(rows[2])).toBe('Resultado $ $ 744.999,50 $ 820.000,00 -$ 90.000,00');
    // Dólares: solo noviembre tiene partidas en dólares.
    expect(text(rows[3])).toContain('Ingresos estimados US$ — Sin partidas US$ 0,00 — Sin partidas');
    expect(text(rows[5])).toContain('Resultado US$ — Sin partidas -US$ 25,50 — Sin partidas');
  });

  it('un resultado negativo lleva el signo y se marca', async () => {
    await setup();

    const results = Array.from(root().querySelectorAll('[data-testid="total-result"]'));
    expect(results[2].classList).toContain('negative');
    expect(results[0].classList).not.toContain('negative');
  });

  it('sin filas avisa que no hay partidas', async () => {
    await setup(grid({ rows: [], totals: [] }));

    expect(text(root().querySelector('[role="status"]'))).toBe('No hay partidas en estos meses.');
    expect(root().querySelector('table')).toBeNull();
  });

  it('un clic en una celda pide abrir el mes de su columna', async () => {
    await setup();

    cellAt(1, 1).click();
    cellAt(2, 2).click();

    expect(opened).toEqual(['2026-11', '2026-12']);
  });

  it('Enter y Espacio abren el mes, también en una celda vacía', async () => {
    await setup();

    press(cellAt(0, 2), 'Enter');
    press(cellAt(1, 0), ' ');

    expect(opened).toEqual(['2026-12', '2026-10']);
  });

  it('solo una celda está en el orden de tabulación', async () => {
    await setup();

    const tabbable = Array.from(root().querySelectorAll('[data-testid="cell"]')).filter(
      (c) => c.getAttribute('tabindex') === '0',
    );
    expect(tabbable.length).toBe(1);
    expect(tabbable[0]).toBe(cellAt(0, 0));
  });

  it('las flechas pasan el foco a la celda vecina y esa pasa a ser la de tabulación', async () => {
    await setup();
    document.body.appendChild(root());
    cellAt(0, 0).focus();

    press(cellAt(0, 0), 'ArrowRight');
    expect(document.activeElement).toBe(cellAt(0, 1));
    expect(cellAt(0, 1).getAttribute('tabindex')).toBe('0');
    expect(cellAt(0, 0).getAttribute('tabindex')).toBe('-1');

    press(cellAt(0, 1), 'ArrowDown');
    expect(document.activeElement).toBe(cellAt(1, 1));

    press(cellAt(1, 1), 'End');
    expect(document.activeElement).toBe(cellAt(1, 2));

    press(cellAt(1, 2), 'Home', { ctrlKey: true });
    expect(document.activeElement).toBe(cellAt(0, 0));

    press(cellAt(0, 0), 'ArrowUp');
    expect(document.activeElement).toBe(cellAt(0, 0));
    root().remove();
  });

  it('las flechas no abren ningún mes', async () => {
    await setup();

    press(cellAt(0, 0), 'ArrowRight');

    expect(opened).toEqual([]);
  });
});
