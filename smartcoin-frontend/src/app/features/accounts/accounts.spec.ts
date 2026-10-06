import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';
import { AccountListResponse, AccountResponse, AutenticacinService, CuentasService, CurrencySubtotal } from '../../api';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { Accounts } from './accounts';

const FREE = { editable: true, reason: null };

function account(
  id: number,
  name: string,
  currency: 'ARS' | 'USD',
  balance = 1000,
  currentBalance = balance,
): AccountResponse {
  return {
    id,
    name,
    type: 'BANK',
    currency,
    openingDate: '2026-08-15',
    initialBalance: balance,
    currentBalance,
    editability: { currency: FREE, initialBalance: FREE, openingDate: FREE },
  };
}

function list(accounts: AccountResponse[], subtotals: CurrencySubtotal[] = []): AccountListResponse {
  return { accounts, subtotals };
}

describe('Accounts', () => {
  let fixture: ComponentFixture<Accounts>;
  let accountsApi: {
    listAccounts: ReturnType<typeof vi.fn>;
    deleteAccount: ReturnType<typeof vi.fn>;
    createAccount: ReturnType<typeof vi.fn>;
    updateAccount: ReturnType<typeof vi.fn>;
  };
  let dialogAnswer: boolean | undefined;
  let dialogOpened: ReturnType<typeof vi.fn<(...args: unknown[]) => void>>;
  let snackBar: { open: ReturnType<typeof vi.fn> };

  async function setup(accounts: AccountResponse[], subtotals: CurrencySubtotal[] = []) {
    registerLocaleData(localeEsAr, 'es-AR');
    accountsApi = {
      listAccounts: vi.fn().mockReturnValue(of(list(accounts, subtotals))),
      deleteAccount: vi.fn().mockReturnValue(of(undefined)),
      createAccount: vi.fn(),
      updateAccount: vi.fn(),
    };
    dialogOpened = vi.fn<(...args: unknown[]) => void>();
    snackBar = { open: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [Accounts],
      providers: [
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: CuentasService, useValue: accountsApi },
        {
          provide: AutenticacinService,
          useValue: { me: () => of({ id: 1, email: 'a@b.c', mustChangePassword: false, startPeriod: '2026-08' }) },
        },
        {
          provide: MatDialog,
          useValue: {
            open: (...args: unknown[]) => {
              dialogOpened(...args);
              return { afterClosed: () => of(dialogAnswer) };
            },
          },
        },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Accounts);
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  const text = () => root().textContent ?? '';
  const button = (label: string) =>
    Array.from(root().querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === label || b.getAttribute('aria-label') === label,
    )!;
  const click = (label: string) => {
    button(label).click();
    fixture.detectChanges();
  };
  const groupNames = (heading: string) =>
    Array.from(
      Array.from(root().querySelectorAll('section')).find((s) => s.querySelector('h2')?.textContent === heading)!
        .querySelectorAll('.name'),
    ).map((n) => n.textContent);

  it('lista las cuentas agrupadas por moneda, sin mezclar pesos con dólares', async () => {
    await setup([
      account(1, 'Banco', 'ARS'),
      account(2, 'Caja USD', 'USD'),
      account(3, 'Efectivo', 'ARS'),
    ]);

    expect(Array.from(root().querySelectorAll('section h2')).map((h) => h.textContent)).toEqual([
      '$ (ARS)',
      'US$ (USD)',
    ]);
    expect(groupNames('$ (ARS)')).toEqual(['Banco', 'Efectivo']);
    expect(groupNames('US$ (USD)')).toEqual(['Caja USD']);
  });

  it('solo muestra los grupos que tienen cuentas', async () => {
    await setup([account(2, 'Caja USD', 'USD')]);

    expect(Array.from(root().querySelectorAll('section h2')).map((h) => h.textContent)).toEqual(['US$ (USD)']);
  });

  it('muestra tipo, fecha de apertura y saldo actual con formato', async () => {
    await setup([account(1, 'Banco', 'ARS', 1000, 98765.4), account(2, 'Caja USD', 'USD', 80, 80)]);

    expect(text()).toContain('Banco · desde el 15/08/2026');
    expect(text()).toContain('Saldo actual');
    expect(text()).toContain('$ 98.765,40');
    expect(text()).toContain('US$ 80,00');
  });

  it('muestra el saldo actual y no el inicial', async () => {
    await setup([account(1, 'Banco', 'ARS', 100000, 130000)]);

    expect(text()).toContain('$ 130.000,00');
    expect(text()).not.toContain('100.000,00');
  });

  it('muestra un subtotal por moneda tal como lo informa el backend, sin sumar nada', async () => {
    await setup(
      [account(1, 'Banco', 'ARS', 0, 100), account(2, 'Efectivo', 'ARS', 0, 200), account(3, 'Caja USD', 'USD', 0, 50)],
      [
        { currency: 'ARS', balance: 77777.77 },
        { currency: 'USD', balance: 1250 },
      ],
    );

    const subtotals = Array.from(root().querySelectorAll('[data-testid="subtotal"] .amount')).map(
      (e) => e.textContent,
    );
    expect(subtotals).toEqual(['$ 77.777,77', 'US$ 1.250,00']);
    expect(text()).not.toMatch(/total general/i);
  });

  it('un grupo con una sola moneda tiene un solo subtotal', async () => {
    await setup([account(2, 'Caja USD', 'USD')], [{ currency: 'USD', balance: 1000 }]);

    expect(root().querySelectorAll('[data-testid="subtotal"]')).toHaveLength(1);
  });

  it('distingue los saldos negativos: signo menos y clase de error, en la cuenta y en el subtotal', async () => {
    await setup(
      [account(1, 'Banco', 'ARS', 0, -2500.5), account(2, 'Efectivo', 'ARS', 0, 1000)],
      [{ currency: 'ARS', balance: -1500.5 }],
    );

    const rows = Array.from(root().querySelectorAll('li.row'));
    expect(rows[0].querySelector('.balance')?.classList.contains('negative')).toBe(true);
    expect(rows[0].querySelector('[data-testid="balance"]')?.textContent).toContain('-$ 2.500,50');
    expect(rows[1].querySelector('.balance')?.classList.contains('negative')).toBe(false);
    const subtotal = root().querySelector('[data-testid="subtotal"]')!;
    expect(subtotal.classList.contains('negative')).toBe(true);
    expect(subtotal.textContent).toContain('-$ 1.500,50');
  });

  it('un saldo en cero no se marca como negativo', async () => {
    await setup([account(1, 'Banco', 'ARS', 0, 0)], [{ currency: 'ARS', balance: 0 }]);

    expect(root().querySelector('.negative')).toBeNull();
  });

  it('después de eliminar una cuenta, los subtotales se actualizan con la respuesta del backend', async () => {
    await setup([account(1, 'Banco', 'ARS', 0, 100), account(2, 'Efectivo', 'ARS', 0, 50)], [
      { currency: 'ARS', balance: 150 },
    ]);
    accountsApi.listAccounts.mockReturnValue(
      of(list([account(2, 'Efectivo', 'ARS', 0, 50)], [{ currency: 'ARS', balance: 50 }])),
    );
    dialogAnswer = true;
    click('Eliminar Banco');

    expect(root().querySelector('[data-testid="subtotal"]')?.textContent).toContain('$ 50,00');
  });

  it('sin cuentas lo dice y ofrece crear la primera', async () => {
    await setup([]);

    expect(text()).toContain('Todavía no cargaste ninguna cuenta');
    expect(button('Nueva cuenta')).toBeTruthy();
  });

  it('«Nueva cuenta» abre el formulario de alta con la fecha sugerida del período inicial', async () => {
    await setup([]);
    click('Nueva cuenta');

    expect(text()).toContain('Nueva cuenta');
    expect(root().querySelector('app-account-form')).toBeTruthy();
    const date = Array.from(root().querySelectorAll('mat-form-field'))
      .find((f) => f.querySelector('label')?.textContent?.includes('Fecha de apertura'))!
      .querySelector('input')!;
    expect(date.value).toBe('2026-08-01');
  });

  it('«Editar» abre el formulario con los datos de esa cuenta', async () => {
    await setup([account(1, 'Banco', 'ARS')]);
    click('Editar Banco');

    expect(text()).toContain('Editar cuenta');
    const name = Array.from(root().querySelectorAll('mat-form-field'))
      .find((f) => f.querySelector('label')?.textContent?.includes('Nombre'))!
      .querySelector('input')!;
    expect(name.value).toBe('Banco');
  });

  it('Cancelar cierra el formulario', async () => {
    await setup([]);
    click('Nueva cuenta');
    click('Cancelar');

    expect(root().querySelector('app-account-form')).toBeNull();
  });

  describe('eliminar', () => {
    it('pide confirmación antes de eliminar y dice qué cuenta es', async () => {
      await setup([account(1, 'Banco', 'ARS')]);
      dialogAnswer = false;
      click('Eliminar Banco');

      expect(dialogOpened).toHaveBeenCalledOnce();
      const config = dialogOpened.mock.calls[0][1] as { data: { message: string } };
      expect(config.data.message).toContain('«Banco»');
      expect(accountsApi.deleteAccount).not.toHaveBeenCalled();
    });

    it('si se descarta el diálogo no elimina nada', async () => {
      await setup([account(1, 'Banco', 'ARS')]);
      dialogAnswer = undefined;
      click('Eliminar Banco');

      expect(accountsApi.deleteAccount).not.toHaveBeenCalled();
    });

    it('al confirmar elimina, recarga la lista y avisa', async () => {
      await setup([account(1, 'Banco', 'ARS')]);
      accountsApi.listAccounts.mockReturnValue(of(list([])));
      dialogAnswer = true;
      click('Eliminar Banco');

      expect(accountsApi.deleteAccount).toHaveBeenCalledWith(1);
      expect(accountsApi.listAccounts).toHaveBeenCalledTimes(2);
      expect(text()).toContain('Todavía no cargaste ninguna cuenta');
      expect(snackBar.open).toHaveBeenCalledWith('Cuenta «Banco» eliminada.', undefined, expect.anything());
    });

    it('si la cuenta está en uso muestra el error y la cuenta sigue en la lista', async () => {
      await setup([account(1, 'Banco', 'ARS')]);
      accountsApi.deleteAccount.mockReturnValue(
        throwError(
          () => new HttpErrorResponse({ status: 409, error: { code: 'ACCOUNT_IN_USE', detail: 'texto del backend' } }),
        ),
      );
      dialogAnswer = true;
      click('Eliminar Banco');

      expect(root().querySelector('[role="alert"]')?.textContent).toContain('No se puede eliminar la cuenta');
      expect(groupNames('$ (ARS)')).toEqual(['Banco']);
    });
  });

  it('si no se pueden cargar las cuentas muestra el error', async () => {
    registerLocaleData(localeEsAr, 'es-AR');
    accountsApi = {
      listAccounts: vi.fn().mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 }))),
      deleteAccount: vi.fn(),
      createAccount: vi.fn(),
      updateAccount: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [Accounts],
      providers: [
        { provide: CuentasService, useValue: accountsApi },
        {
          provide: AutenticacinService,
          useValue: { me: () => of({ id: 1, email: 'a@b.c', mustChangePassword: false, startPeriod: '2026-08' }) },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Accounts);
    fixture.detectChanges();

    expect(root().querySelector('[role="alert"]')?.textContent).toContain('No se pudo conectar con el servidor');
  });
});
