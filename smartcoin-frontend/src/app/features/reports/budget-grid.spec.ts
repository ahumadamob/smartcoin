import { HttpErrorResponse } from '@angular/common/http';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Observable, of, Subject, throwError } from 'rxjs';
import { BudgetGrid, ReportesService } from '../../api';
import { BudgetGridPage } from './budget-grid';

function grid(overrides: Partial<BudgetGrid> = {}): BudgetGrid {
  return {
    from: '2026-10',
    to: '2027-09',
    currentPeriod: '2026-10',
    startPeriod: '2026-08',
    horizon: '2028-10',
    months: [{ period: '2026-10', status: 'OPEN' }],
    rows: [
      {
        budgetItemId: 1,
        name: 'Sueldo',
        kind: 'INCOME',
        currency: 'ARS',
        cells: [
          { entryId: 1, entryCount: 1, currency: 'ARS', forecast: 1000, status: 'ESTIMATED', manual: false, consolidated: false },
        ],
      },
    ],
    totals: [{ period: '2026-10', currencies: [{ currency: 'ARS', incomeForecast: 1000, expenseForecast: 0, result: 1000 }] }],
    ...overrides,
  };
}

describe('BudgetGridPage', () => {
  let fixture: ComponentFixture<BudgetGridPage>;
  let getBudgetGrid: ReturnType<typeof vi.fn>;
  let navigate: ReturnType<typeof vi.fn>;

  async function setup(result: Observable<BudgetGrid> = of(grid())) {
    registerLocaleData(localeEsAr, 'es-AR');
    getBudgetGrid = vi.fn().mockReturnValue(result);
    navigate = vi.fn().mockResolvedValue(true);
    await TestBed.configureTestingModule({
      imports: [BudgetGridPage],
      providers: [
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ReportesService, useValue: { getBudgetGrid } },
        { provide: Router, useValue: { navigate } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(BudgetGridPage);
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  const input = (label: string) =>
    Array.from(root().querySelectorAll<HTMLInputElement>('input')).find(
      (i) => i.closest('mat-form-field')?.textContent?.includes(label),
    )!;

  function type(el: HTMLInputElement, value: string) {
    el.value = value;
    el.dispatchEvent(new Event('input'));
  }

  function submit() {
    root().querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
  }

  it('al abrir pide la grilla sin extremos y muestra el rango que resolvió el backend', async () => {
    await setup();

    expect(getBudgetGrid).toHaveBeenCalledWith(undefined, undefined);
    expect(input('Desde').value).toBe('2026-10');
    expect(input('Hasta').value).toBe('2027-09');
    expect(root().querySelector('app-budget-grid-table')).not.toBeNull();
  });

  it('limita los selectores al período inicial y al horizonte que informa el backend', async () => {
    await setup();

    expect(input('Desde').getAttribute('min')).toBe('2026-08');
    expect(input('Hasta').getAttribute('max')).toBe('2028-10');
  });

  it('«Ver» pide el rango elegido', async () => {
    await setup();
    getBudgetGrid.mockClear();

    type(input('Desde'), '2027-01');
    type(input('Hasta'), '2027-06');
    submit();

    expect(getBudgetGrid).toHaveBeenCalledWith('2027-01', '2027-06');
  });

  it('un clic en una celda va al presupuesto de ese mes', async () => {
    await setup();

    root().querySelector<HTMLElement>('[data-testid="cell"]')!.click();

    expect(navigate).toHaveBeenCalledWith(['/presupuesto', '2026-10']);
  });

  it('muestra el error del backend y no deja la grilla anterior a la vista', async () => {
    await setup();
    getBudgetGrid.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { code: 'VALIDATION_ERROR', detail: 'El rango no puede tener más de 24 meses.' },
          }),
      ),
    );

    submit();

    expect(root().querySelector('[role="alert"]')!.textContent).toContain('más de 24 meses');
    expect(root().querySelector('app-budget-grid-table')).toBeNull();
  });

  it('mientras carga avisa y no muestra la grilla vieja', async () => {
    await setup(new Subject<BudgetGrid>());

    expect(root().querySelector('[role="status"]')!.textContent).toContain('Cargando');
    expect(root().querySelector('app-budget-grid-table')).toBeNull();
  });
});
