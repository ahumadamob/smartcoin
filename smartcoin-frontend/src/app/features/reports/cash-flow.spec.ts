import { HttpErrorResponse } from '@angular/common/http';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, FormGroup } from '@angular/forms';
import { Observable, of, Subject, throwError } from 'rxjs';
import { CashFlow, ReportesService } from '../../api';
import { CashFlowPage } from './cash-flow';

function flow(overrides: Partial<CashFlow> = {}): CashFlow {
  return {
    currency: 'ARS',
    from: '2026-10-01',
    to: '2026-10-31',
    openingBalance: 80000,
    openingBalanceDate: '2026-09-30',
    openingBalanceProjected: false,
    rows: [
      {
        date: '2026-10-05',
        status: 'REAL',
        type: 'MOVEMENT',
        description: 'Cobro · Banco',
        amount: 30000,
        balance: 110000,
        overdue: false,
      },
    ],
    closingBalance: 110000,
    ...overrides,
  };
}

describe('CashFlowPage', () => {
  let fixture: ComponentFixture<CashFlowPage>;
  let getCashFlow: ReturnType<typeof vi.fn>;

  async function setup(result: Observable<CashFlow> = of(flow())) {
    registerLocaleData(localeEsAr, 'es-AR');
    getCashFlow = vi.fn().mockReturnValue(result);
    await TestBed.configureTestingModule({
      imports: [CashFlowPage],
      providers: [
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ReportesService, useValue: { getCashFlow } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(CashFlowPage);
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  const input = (label: string) =>
    Array.from(root().querySelectorAll<HTMLInputElement>('input')).find((i) =>
      i.closest('mat-form-field')?.textContent?.includes(label),
    )!;
  const currencyControl = () =>
    (fixture.componentInstance as unknown as { filters: FormGroup<{ currency: FormControl<string> }> }).filters.controls
      .currency;

  function type(label: string, value: string) {
    const el = input(label);
    el.value = value;
    el.dispatchEvent(new Event('input'));
  }

  function submit() {
    root().querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
  }

  it('al abrir pide el flujo en pesos sin fechas y muestra el rango que resolvió el backend', async () => {
    await setup();

    expect(getCashFlow).toHaveBeenCalledWith('ARS', undefined, undefined);
    expect(input('Desde').value).toBe('2026-10-01');
    expect(input('Hasta').value).toBe('2026-10-31');
    expect(root().querySelector('[data-testid="summary"]')!.textContent).toContain('Del 01/10/2026 al 31/10/2026');
    expect(root().querySelector('[data-testid="summary"]')!.textContent).toContain('1 fila.');
    expect(root().querySelector('app-cash-flow-table')).not.toBeNull();
  });

  it('«Ver» pide el flujo con las fechas y la moneda de los filtros', async () => {
    await setup();
    getCashFlow.mockClear();

    type('Desde', '2026-11-01');
    type('Hasta', '2026-11-30');
    submit();

    expect(getCashFlow).toHaveBeenCalledTimes(1);
    expect(getCashFlow).toHaveBeenCalledWith('ARS', '2026-11-01', '2026-11-30');
  });

  it('con una fecha vacía no la manda y deja que el backend complete el extremo', async () => {
    await setup();
    getCashFlow.mockClear();

    type('Desde', '2026-11-10');
    type('Hasta', '');
    submit();

    expect(getCashFlow).toHaveBeenCalledWith('ARS', '2026-11-10', undefined);
  });

  it('cambiar la moneda vuelve a pedir el flujo con la moneda nueva y las mismas fechas', async () => {
    await setup();
    getCashFlow.mockClear();
    getCashFlow.mockReturnValue(of(flow({ currency: 'USD', openingBalance: 500 })));

    currencyControl().setValue('USD');
    fixture.detectChanges();

    expect(getCashFlow).toHaveBeenCalledTimes(1);
    expect(getCashFlow).toHaveBeenCalledWith('USD', '2026-10-01', '2026-10-31');
    expect(root().textContent).toContain('US$ 500,00');
    expect(root().querySelector('[data-testid="summary"]')!.textContent).toContain('US$ (USD)');
  });

  it('muestra el error del backend y no deja el flujo anterior a la vista', async () => {
    await setup();
    getCashFlow.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { code: 'VALIDATION_ERROR', detail: 'El rango no puede tener más de 366 días.' },
          }),
      ),
    );

    submit();

    expect(root().querySelector('[role="alert"]')!.textContent).toContain('El rango no puede tener más de 366 días.');
    expect(root().querySelector('app-cash-flow-table')).toBeNull();
  });

  it('mientras calcula avisa y no muestra la tabla vieja', async () => {
    const pending = new Subject<CashFlow>();
    await setup(pending);

    expect(root().textContent).toContain('Calculando el flujo de caja');
    expect(root().querySelector('app-cash-flow-table')).toBeNull();

    pending.next(flow());
    fixture.detectChanges();

    expect(root().textContent).not.toContain('Calculando el flujo de caja');
    expect(root().querySelector('app-cash-flow-table')).not.toBeNull();
  });

  it('un pedido nuevo cancela el anterior', async () => {
    const first = new Subject<CashFlow>();
    await setup(first);
    getCashFlow.mockReturnValue(of(flow({ openingBalance: 1 })));

    submit();
    first.next(flow({ openingBalance: 999 }));
    fixture.detectChanges();

    expect(root().querySelector('[data-testid="opening-balance"]')!.textContent).toContain('$ 1,00');
  });

  it('no pisa una fecha que el usuario escribió mientras el pedido volaba', async () => {
    const pending = new Subject<CashFlow>();
    await setup(pending);

    type('Desde', '2026-11-05');
    pending.next(flow());
    fixture.detectChanges();

    expect(input('Desde').value).toBe('2026-11-05');
    expect(input('Hasta').value).toBe('2026-10-31');
  });

  it('con las dos fechas pedidas no cambia lo que muestran los campos', async () => {
    await setup();
    type('Desde', '2026-11-01');
    type('Hasta', '2026-11-30');
    getCashFlow.mockReturnValue(of(flow({ from: '2026-11-01', to: '2026-11-30' })));

    submit();

    expect(input('Desde').value).toBe('2026-11-01');
    expect(input('Hasta').value).toBe('2026-11-30');
  });

  it('los campos tienen etiqueta y el botón es de envío', async () => {
    await setup();

    const labels = Array.from(root().querySelectorAll('mat-label')).map((l) => l.textContent!.trim());
    expect(labels).toEqual(['Desde', 'Hasta', 'Moneda']);
    expect(root().querySelector('button[type="submit"]')!.textContent).toContain('Ver');
    expect(root().querySelector('form')!.getAttribute('aria-label')).toBeTruthy();
  });
});
