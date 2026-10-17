import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Projection } from '../../api';
import { ProjectionTable } from './projection-table';

function projection(overrides: Partial<Projection> = {}): Projection {
  return {
    from: '2026-10',
    until: '2026-11',
    accounts: [
      { id: 1, name: 'Banco', currency: 'ARS' },
      { id: 2, name: 'Billetera', currency: 'ARS' },
      { id: 3, name: 'Caja USD', currency: 'USD' },
    ],
    months: [
      {
        month: '2026-10',
        balances: [
          { accountId: 1, balance: 340000 },
          { accountId: 2, balance: 20000 },
          { accountId: 3, balance: 500 },
        ],
        subtotals: [
          { currency: 'ARS', balance: 360000 },
          { currency: 'USD', balance: 500 },
        ],
      },
      {
        month: '2026-11',
        balances: [
          { accountId: 1, balance: -50000 },
          { accountId: 2, balance: 20000 },
          { accountId: 3, balance: 450 },
        ],
        subtotals: [
          { currency: 'ARS', balance: -30000 },
          { currency: 'USD', balance: 450 },
        ],
      },
    ],
    ...overrides,
  };
}

describe('ProjectionTable', () => {
  let fixture: ComponentFixture<ProjectionTable>;

  async function setup(value: Projection) {
    registerLocaleData(localeEsAr, 'es-AR');
    await TestBed.configureTestingModule({
      imports: [ProjectionTable],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    fixture = TestBed.createComponent(ProjectionTable);
    fixture.componentRef.setInput('projection', value);
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const rows = () => Array.from(root().querySelectorAll('[data-testid="month-row"]'));
  const cells = (row: Element, id: string) => Array.from(row.querySelectorAll(`[data-testid="${id}"]`)).map(text);

  it('muestra un mes por fila, con su nombre en español', async () => {
    await setup(projection());

    expect(rows().length).toBe(2);
    expect(text(rows()[0].querySelector('[data-testid="month"]')).toLowerCase()).toBe('octubre 2026');
    expect(text(rows()[1].querySelector('[data-testid="month"]')).toLowerCase()).toBe('noviembre 2026');
  });

  it('agrupa las cuentas por moneda, cada grupo con su subtotal', async () => {
    await setup(projection());

    const groups = Array.from(root().querySelectorAll('[data-testid="group"]'));
    expect(groups.map(text)).toEqual(['Pesos ($)', 'Dólares (US$)']);
    expect(groups.map((g) => g.getAttribute('colspan'))).toEqual(['3', '2']);
    expect(Array.from(root().querySelectorAll('[data-testid="account-head"]')).map(text)).toEqual([
      'Banco',
      'Billetera',
      'Caja USD',
    ]);
    expect(Array.from(root().querySelectorAll('[data-testid="subtotal-head"]')).map(text)).toEqual([
      'Subtotal $',
      'Subtotal US$',
    ]);
  });

  it('muestra los saldos y subtotales tal como vienen, con el pipe de moneda de cada grupo', async () => {
    await setup(projection());

    expect(cells(rows()[0], 'balance')).toEqual(['$ 340.000,00', '$ 20.000,00', 'US$ 500,00']);
    expect(cells(rows()[0], 'subtotal')).toEqual(['$ 360.000,00', 'US$ 500,00']);
  });

  it('un saldo negativo lleva el signo menos y la marca «Faltante», en la cuenta y en el subtotal', async () => {
    await setup(projection());

    const [bank] = Array.from(rows()[1].querySelectorAll('[data-testid="balance"]'));
    expect(text(bank)).toContain('-$ 50.000,00');
    expect(text(bank)).toContain('Faltante');
    expect(bank.classList).toContain('shortfall');
    const [pesos, dollars] = Array.from(rows()[1].querySelectorAll('[data-testid="subtotal"]'));
    expect(text(pesos)).toContain('Faltante');
    expect(text(dollars)).not.toContain('Faltante');
    expect(rows()[0].textContent).not.toContain('Faltante');
  });

  it('no suma nada: el subtotal que muestra es el del backend aunque no cierre con las cuentas', async () => {
    const value = projection();
    value.months[0].subtotals[0].balance = 1;
    await setup(value);

    expect(cells(rows()[0], 'subtotal')[0]).toBe('$ 1,00');
  });

  it('con una sola moneda solo hay un grupo', async () => {
    await setup(
      projection({
        accounts: [{ id: 1, name: 'Banco', currency: 'ARS' }],
        months: [
          {
            month: '2026-10',
            balances: [{ accountId: 1, balance: 100 }],
            subtotals: [{ currency: 'ARS', balance: 100 }],
          },
        ],
      }),
    );

    expect(root().querySelectorAll('[data-testid="group"]').length).toBe(1);
    expect(cells(rows()[0], 'balance')).toEqual(['$ 100,00']);
  });

  it('sin cuentas avisa en vez de dibujar una tabla vacía', async () => {
    await setup(projection({ accounts: [], months: [] }));

    expect(root().querySelector('table')).toBeNull();
    expect(text(root().querySelector('[role="status"]'))).toBe('Todavía no hay cuentas para proyectar.');
  });

  it('se recorre con el teclado: la región con las filas es enfocable y tiene nombre', async () => {
    await setup(projection());

    const region = root().querySelector('[role="region"]')!;
    expect(region.getAttribute('tabindex')).toBe('0');
    expect(region.getAttribute('aria-label')).toBe('Saldos proyectados por mes');
    expect(root().querySelector('caption')).not.toBeNull();
    expect(root().querySelectorAll('thead th').length).toBeGreaterThan(0);
    expect(root().querySelectorAll('tbody th[scope="row"]').length).toBe(2);
  });
});
