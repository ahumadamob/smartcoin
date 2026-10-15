import { HttpErrorResponse } from '@angular/common/http';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';
import { CuentasService, Transfer, TransferenciasService } from '../../api';
import { Transfers } from './transfers';

function transfer(overrides: Partial<Transfer> = {}): Transfer {
  return {
    id: 31,
    date: '2026-10-15',
    sourceAccountId: 1,
    sourceAccountName: 'Banco',
    sourceCurrency: 'ARS',
    sourceAmount: 50000,
    targetAccountId: 2,
    targetAccountName: 'Efectivo',
    targetCurrency: 'ARS',
    targetAmount: 50000,
    note: 'Extracción',
    ...overrides,
  };
}

describe('Transfers', () => {
  let fixture: ComponentFixture<Transfers>;
  let transfersApi: { listTransfers: ReturnType<typeof vi.fn>; createTransfer: ReturnType<typeof vi.fn> };

  async function setup(list: Transfer[]) {
    registerLocaleData(localeEsAr, 'es-AR');
    transfersApi = { listTransfers: vi.fn().mockReturnValue(of(list)), createTransfer: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [Transfers],
      providers: [
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: TransferenciasService, useValue: transfersApi },
        { provide: CuentasService, useValue: { listAccounts: () => of({ accounts: [], subtotals: [] }) } },
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Transfers);
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  const text = () => root().textContent ?? '';

  it('lista fecha, origen, destino, monto y nota', async () => {
    await setup([transfer()]);

    const row = root().querySelector('[data-testid="transfer"]')!.textContent!;
    expect(row).toContain('15/10/2026');
    expect(row).toContain('Banco → Efectivo');
    expect(row).toContain('$ 50.000,00');
    expect(row).toContain('Extracción');
  });

  it('en una de distinta moneda muestra los dos montos', async () => {
    await setup([
      transfer({ targetCurrency: 'USD', targetAmount: 40, targetAccountName: 'Dólares', note: undefined }),
    ]);

    const row = root().querySelector('[data-testid="transfer"]')!.textContent!;
    expect(row).toContain('$ 50.000,00');
    expect(row).toContain('US$ 40,00');
  });

  it('en una de distinta moneda muestra el tipo de cambio que devuelve la API', async () => {
    await setup([
      transfer({
        sourceAmount: 1250000,
        targetCurrency: 'USD',
        targetAmount: 1000,
        targetAccountName: 'Dólares',
        exchangeRate: 1250,
      }),
      transfer({
        id: 32,
        sourceCurrency: 'USD',
        sourceAmount: 500,
        targetAmount: 610000,
        sourceAccountName: 'Dólares',
        targetAccountName: 'Banco',
        exchangeRate: 1220,
      }),
    ]);

    const rows = Array.from(root().querySelectorAll('[data-testid="exchange"]')).map((e) =>
      e.textContent!.replace(/\s+/g, ' ').trim(),
    );
    expect(rows).toEqual([
      'Compra de dólares · Tipo de cambio $ 1.250,00',
      'Venta de dólares · Tipo de cambio $ 1.220,00',
    ]);
  });

  it('en una de la misma moneda no muestra tipo de cambio', async () => {
    await setup([transfer({ exchangeRate: null })]);

    expect(root().querySelector('[data-testid="exchange"]')).toBeNull();
    expect(text()).not.toContain('Tipo de cambio');
  });

  it('sin transferencias avisa que no hay en el rango', async () => {
    await setup([]);

    expect(text()).toContain('No hay transferencias en ese rango.');
  });

  it('pide el rango indicado y, en blanco, deja que el backend elija el mes actual', async () => {
    await setup([]);
    expect(transfersApi.listTransfers).toHaveBeenCalledWith();

    const range = fixture.componentInstance['range'];
    range.setValue({ from: '2026-09-01', to: '2026-09-30' });
    fixture.componentInstance['search']();
    expect(transfersApi.listTransfers).toHaveBeenLastCalledWith('2026-09-01', '2026-09-30');

    range.setValue({ from: '', to: '' });
    fixture.componentInstance['search']();
    expect(transfersApi.listTransfers).toHaveBeenLastCalledWith(undefined, undefined);
  });

  it('muestra el error del backend ante un rango invertido', async () => {
    await setup([]);
    transfersApi.listTransfers.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { code: 'VALIDATION_ERROR', errors: [{ field: 'from', message: 'La fecha desde es posterior.' }] },
          }),
      ),
    );
    fixture.componentInstance['search']();
    fixture.detectChanges();

    expect(text()).toContain('La fecha desde es posterior.');
  });
});
