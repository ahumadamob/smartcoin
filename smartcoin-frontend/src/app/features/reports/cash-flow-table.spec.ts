import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CashFlow, CashFlowRow } from '../../api';
import { CashFlowTable } from './cash-flow-table';

function row(overrides: Partial<CashFlowRow> = {}): CashFlowRow {
  return {
    date: '2026-11-25',
    status: 'REAL',
    type: 'MOVEMENT',
    description: 'Sueldo · Banco',
    amount: 400000,
    balance: 450000,
    overdue: false,
    ...overrides,
  };
}

function flow(rows: CashFlowRow[], overrides: Partial<CashFlow> = {}): CashFlow {
  return {
    currency: 'ARS',
    from: '2026-11-01',
    to: '2026-11-30',
    openingBalance: 50000,
    openingBalanceDate: '2026-10-31',
    openingBalanceProjected: false,
    rows,
    closingBalance: rows.length ? rows[rows.length - 1].balance : 50000,
    ...overrides,
  };
}

describe('CashFlowTable', () => {
  let fixture: ComponentFixture<CashFlowTable>;

  async function setup(value: CashFlow) {
    registerLocaleData(localeEsAr, 'es-AR');
    await TestBed.configureTestingModule({
      imports: [CashFlowTable],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    fixture = TestBed.createComponent(CashFlowTable);
    fixture.componentRef.setInput('flow', value);
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  const rows = () => Array.from(root().querySelectorAll<HTMLElement>('[data-testid="row"]'));
  const text = (el: Element, testId: string) => el.querySelector(`[data-testid="${testId}"]`)!.textContent!.replace(/\s+/g, ' ').trim();

  it('muestra el saldo de arranque arriba con su fecha', async () => {
    await setup(flow([row()]));

    const opening = root().querySelector('[data-testid="opening"]')!.textContent!.replace(/\s+/g, ' ');
    expect(opening).toContain('Saldo de arranque al 31/10/2026');
    expect(text(root(), 'opening-balance')).toBe('$ 50.000,00');
    expect(root().querySelector('[data-testid="projected-hint"]')).toBeNull();
  });

  it('lista las filas en el orden que manda el backend con fecha, descripción, monto y saldo acumulado', async () => {
    await setup(
      flow([
        row({ date: '2026-11-25', description: 'Sueldo diciembre A · Banco', amount: 400000, balance: 450000 }),
        row({ date: '2026-11-30', description: 'Sueldo diciembre B · Banco', amount: 250000, balance: 700000 }),
      ]),
    );

    expect(rows().map((r) => text(r, 'date'))).toEqual(['25/11/2026', '30/11/2026']);
    expect(rows().map((r) => text(r, 'description'))).toEqual(['Sueldo diciembre A · Banco', 'Sueldo diciembre B · Banco']);
    expect(rows().map((r) => text(r, 'amount'))).toEqual(['$ 400.000,00', '$ 250.000,00']);
    expect(rows().map((r) => text(r, 'balance'))).toEqual(['$ 450.000,00', '$ 700.000,00']);
    expect(text(root(), 'closing')).toBe('$ 700.000,00');
  });

  it('un pago muestra el monto con signo menos', async () => {
    await setup(flow([row({ description: 'Alquiler · Banco', amount: -150000, balance: -100000 })]));

    expect(text(rows()[0], 'amount')).toContain('-');
    expect(text(rows()[0], 'amount')).toContain('150.000,00');
  });

  it('distingue lo real de lo previsto por el texto, no solo por el color', async () => {
    await setup(
      flow([
        row({ status: 'REAL' }),
        row({ status: 'PLANNED', type: 'PENDING_ENTRY', description: 'Luz · Banco', amount: -20000, balance: 430000 }),
      ]),
    );

    const [real, planned] = rows();
    expect(text(real, 'status')).toContain('Real');
    expect(text(planned, 'status')).toContain('Prevista');
    expect(real.getAttribute('data-status')).toBe('REAL');
    expect(planned.getAttribute('data-status')).toBe('PLANNED');
    expect(real.classList.contains('planned')).toBe(false);
    expect(planned.classList.contains('planned')).toBe(true);
    expect(real.querySelector('.mark-real')).not.toBeNull();
    expect(planned.querySelector('.mark-planned')).not.toBeNull();
  });

  it('una prevista vencida lleva la marca «Vencida» y su vencimiento original', async () => {
    await setup(
      flow([
        row({
          status: 'PLANNED',
          type: 'PENDING_ENTRY',
          date: '2026-10-17',
          description: 'Luz · Banco',
          amount: -20000,
          balance: 30000,
          dueDate: '2026-10-10',
          overdue: true,
        }),
        row({ status: 'PLANNED', type: 'PENDING_ENTRY', description: 'Tarjeta · Banco', dueDate: '2026-10-20' }),
      ]),
    );

    const [overdue, onTime] = rows();
    expect(text(overdue, 'description')).toContain('Vencida');
    expect(text(overdue, 'due')).toBe('Vencimiento: 10/10/2026');
    expect(text(onTime, 'description')).not.toContain('Vencida');
  });

  it('marca como «Faltante» solo los saldos acumulados negativos', async () => {
    await setup(
      flow([
        row({ amount: -90000, balance: -40000 }),
        row({ amount: 50000, balance: 10000 }),
        row({ amount: -10000, balance: 0 }),
      ]),
    );

    const [negative, positive, zero] = rows();
    expect(text(negative, 'balance')).toContain('Faltante');
    expect(text(negative, 'balance')).toContain('-');
    expect(negative.querySelector('[data-testid="balance"]')!.classList.contains('shortfall')).toBe(true);
    expect(text(positive, 'balance')).not.toContain('Faltante');
    expect(text(zero, 'balance')).not.toContain('Faltante');
  });

  it('marca un saldo final negativo y un arranque negativo', async () => {
    await setup(flow([row({ amount: -10, balance: -5 })], { openingBalance: -20 }));

    expect(text(root(), 'closing')).toContain('Faltante');
    expect(root().querySelector('[data-testid="opening"]')!.textContent).toContain('Faltante');
  });

  it('si el arranque es proyectado lo dice y explica por qué', async () => {
    await setup(flow([row()], { openingBalanceProjected: true, openingBalance: 340000 }));

    const opening = root().querySelector('[data-testid="opening"]')!.textContent!.replace(/\s+/g, ' ');
    expect(opening).toContain('Saldo de arranque proyectado al 31/10/2026');
    expect(text(root(), 'opening-balance')).toBe('$ 340.000,00');
    expect(root().querySelector('[data-testid="projected-hint"]')).not.toBeNull();
  });

  it('en dólares usa el símbolo de la moneda del flujo', async () => {
    await setup(flow([row({ amount: 1000, balance: 1050 })], { currency: 'USD', openingBalance: 50 }));

    expect(text(root(), 'opening-balance')).toBe('US$ 50,00');
    expect(text(rows()[0], 'balance')).toBe('US$ 1.050,00');
  });

  it('sin filas avisa y muestra igual los saldos de arranque y final', async () => {
    await setup(flow([]));

    expect(root().querySelector('table')).toBeNull();
    expect(root().textContent).toContain('No hay movimientos ni pendientes en este rango.');
    expect(root().querySelector('[data-testid="opening-balance"]')).not.toBeNull();
    expect(root().querySelector('[data-testid="closing-empty"]')!.textContent).toContain('$ 50.000,00');
  });

  it('la tabla es accesible: título, encabezados de columna y de fila, y región con foco por teclado', async () => {
    await setup(flow([row()]));

    expect(root().querySelector('caption')).not.toBeNull();
    expect(Array.from(root().querySelectorAll('thead th')).map((th) => th.textContent!.trim())).toEqual([
      'Fecha',
      'Descripción',
      'Estado',
      'Monto',
      'Saldo acumulado',
    ]);
    expect(root().querySelectorAll('tbody th[scope="row"]').length).toBe(1);
    const region = root().querySelector('[role="region"]')!;
    expect(region.getAttribute('tabindex')).toBe('0');
    expect(region.getAttribute('aria-label')).toBeTruthy();
  });
});
