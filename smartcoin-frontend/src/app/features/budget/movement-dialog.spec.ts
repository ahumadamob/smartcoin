import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AccountResponse, CuentasService, Movement, MovementRequest, MovimientosService, PeriodEntry } from '../../api';
import { provideLocale } from '../../core/locale';
import { MovementDialog, MovementDialogData, MovementDialogResult } from './movement-dialog';

const FREE = { editable: true, reason: null };
const account = (id: number, name: string, currency: 'ARS' | 'USD'): AccountResponse => ({
  id,
  name,
  type: 'BANK',
  currency,
  openingDate: '2026-08-01',
  initialBalance: 0,
  currentBalance: 0,
  editability: { currency: FREE, initialBalance: FREE, openingDate: FREE },
});
const ACCOUNTS = [
  account(12, 'Banco Nación', 'ARS'),
  account(14, 'Billetera', 'ARS'),
  account(13, 'Caja en dólares', 'USD'),
];

const ENTRY: PeriodEntry = {
  id: 900,
  budgetItemId: null,
  origin: 'ONE_OFF',
  kind: 'EXPENSE',
  name: 'Expensas',
  categoryId: null,
  categoryName: null,
  accountId: 12,
  accountName: 'Banco Nación',
  currency: 'ARS',
  dueDate: '2026-11-10',
  installmentNumber: null,
  installmentsTotal: null,
  budgetedAmount: 120000,
  actualAmount: 0,
  pendingAmount: 120000,
  forecastAmount: 120000,
  status: 'ESTIMATED',
  manual: false,
  overdue: false,
};

const movement = (id: number, date: string, amount: number, accountName: string, note: string | null): Movement => ({
  id,
  entryId: 900,
  date,
  amount,
  note,
  accountId: 12,
  accountName,
  currency: 'ARS',
});

const problem = (status: number, body: object) => throwError(() => new HttpErrorResponse({ status, error: body }));

describe('MovementDialog (HU-19)', () => {
  let fixture: ComponentFixture<MovementDialog>;
  let api: { listMovements: ReturnType<typeof vi.fn>; registerMovement: ReturnType<typeof vi.fn> };
  let ref: { close: ReturnType<typeof vi.fn<(result?: MovementDialogResult) => void>> };

  async function open(
    data: Partial<MovementDialogData> = {},
    options: { movements?: Movement[]; accounts?: AccountResponse[]; listError?: boolean } = {},
  ) {
    api = {
      listMovements: vi.fn(() => (options.listError ? problem(500, {}) : of(options.movements ?? []))),
      registerMovement: vi.fn((entryId: number, request: MovementRequest) =>
        of({
          movement: { ...movement(501, request.date, request.amount, 'Banco Nación', request.note ?? null) },
          entry: { ...ENTRY, status: 'PARTIAL', actualAmount: request.amount },
        }),
      ),
    };
    ref = { close: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [MovementDialog],
      providers: [
        provideLocale(),
        provideRouter([]),
        { provide: MovimientosService, useValue: api },
        { provide: CuentasService, useValue: { listAccounts: () => of({ accounts: options.accounts ?? ACCOUNTS, subtotals: [] }) } },
        { provide: MatDialogRef, useValue: ref },
        {
          provide: MAT_DIALOG_DATA,
          useValue: { entry: ENTRY, period: '2026-11', today: new Date(2026, 10, 20), ...data },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(MovementDialog);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  const text = (el: Element | null | undefined = root()) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const field = (label: string) =>
    Array.from(root().querySelectorAll('mat-form-field')).find((f) =>
      f.querySelector('label')?.textContent?.includes(label),
    )!;
  const input = (label: string) => field(label).querySelector('input')!;
  const type = (label: string, value: string) => {
    const el = input(label);
    el.value = value;
    el.dispatchEvent(new Event('input'));
    el.dispatchEvent(new Event('blur'));
    fixture.detectChanges();
  };
  const submit = () => {
    root().querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
  };
  const button = (label: string) =>
    Array.from(root().querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.includes(label))!;
  const sent = () => api.registerMovement.mock.calls[0][1] as MovementRequest;
  const form = () =>
    (fixture.componentInstance as unknown as { form: { controls: Record<string, { value: unknown; errors: unknown }> } })
      .form;

  describe('lo que muestra', () => {
    it('el título dice la acción de un gasto y la partida; el de un ingreso, «Registrar cobro»', async () => {
      await open();
      expect(text(root().querySelector('h2'))).toBe('Registrar pago · Expensas');
      TestBed.resetTestingModule();

      await open({ entry: { ...ENTRY, kind: 'INCOME', name: 'Sueldo' } });
      expect(text(root().querySelector('h2'))).toBe('Registrar cobro · Sueldo');
      expect(text(button('Registrar cobro'))).toBe('Registrar cobro');
    });

    it('presupuestado, real y pendiente son los que informa el backend', async () => {
      await open({ entry: { ...ENTRY, status: 'PARTIAL', actualAmount: 70000, pendingAmount: 50000 } });

      expect(text(root().querySelector('[data-testid="budgeted"]'))).toBe('$ 120.000,00');
      expect(text(root().querySelector('[data-testid="actual"]'))).toBe('$ 70.000,00');
      expect(text(root().querySelector('[data-testid="pending"]'))).toBe('$ 50.000,00');
      expect(root().querySelector('[data-testid="covered-notice"]')).toBeNull();
    });

    it('lista los movimientos ya registrados, con fecha, cuenta, monto y nota', async () => {
      await open(
        { entry: { ...ENTRY, status: 'PARTIAL', actualAmount: 120000, pendingAmount: 0 } },
        {
          movements: [
            movement(1, '2026-11-05', 70000, 'Banco Nación', 'Primera parte'),
            movement(2, '2026-11-12', 50000, 'Billetera', null),
          ],
        },
      );

      const rows = Array.from(root().querySelectorAll('[data-testid="movement-row"]')).map((row) =>
        Array.from(row.querySelectorAll('td')).map((cell) => text(cell)),
      );
      expect(api.listMovements).toHaveBeenCalledWith(900);
      expect(rows).toEqual([
        ['05/11/2026', 'Banco Nación', '$ 70.000,00', 'Primera parte'],
        ['12/11/2026', 'Billetera', '$ 50.000,00', '—'],
      ]);
    });

    it('sin movimientos lo dice', async () => {
      await open();

      expect(text(root().querySelector('[data-testid="no-movements"]'))).toBe('Todavía no tiene movimientos.');
      expect(root().querySelector('[data-testid="movements-list"]')).toBeNull();
    });

    it('con el pendiente en 0 en una Parcial avisa que está cubierta, sin botón de consolidar', async () => {
      await open({ entry: { ...ENTRY, status: 'PARTIAL', actualAmount: 120000, pendingAmount: 0 } });

      expect(text(root().querySelector('[data-testid="covered-notice"]'))).toBe(
        '«Expensas» ya está cubierta: pendiente $ 0,00. Sigue Parcial hasta que se consolide.',
      );
      expect(button('Consolidar')).toBeUndefined();
    });

    it('una Estimada con presupuestado 0 no está «cubierta»: no hay aviso', async () => {
      await open({ entry: { ...ENTRY, budgetedAmount: 0, pendingAmount: 0, forecastAmount: 0 } });

      expect(root().querySelector('[data-testid="covered-notice"]')).toBeNull();
    });
  });

  describe('el formulario', () => {
    it('arranca con hoy, la cuenta de la partida y el pendiente como monto, con coma decimal', async () => {
      await open({ entry: { ...ENTRY, status: 'PARTIAL', actualAmount: 70000, pendingAmount: 50000.5 } });

      expect(input('Fecha').value).toBe('2026-11-20');
      expect(input('Fecha').type).toBe('date');
      expect(input('Monto').value).toBe('50000,50');
      expect(form().controls['accountId'].value).toBe(12);
    });

    it('con el pendiente en 0 el monto arranca vacío', async () => {
      await open({ entry: { ...ENTRY, status: 'PARTIAL', actualAmount: 120000, pendingAmount: 0 } });

      expect(input('Monto').value).toBe('');
    });

    it('ofrece solo las cuentas de la moneda de la partida', async () => {
      await open();

      const names = (fixture.componentInstance as unknown as { accounts: () => AccountResponse[] }).accounts().map((a) => a.name);
      expect(names).toEqual(['Banco Nación', 'Billetera']);
    });

    it('una partida en dólares ofrece solo cuentas en dólares', async () => {
      await open({ entry: { ...ENTRY, currency: 'USD', accountId: 13, accountName: 'Caja en dólares' } });

      const names = (fixture.componentInstance as unknown as { accounts: () => AccountResponse[] }).accounts().map((a) => a.name);
      expect(names).toEqual(['Caja en dólares']);
    });

    it('sin una cuenta de esa moneda lo dice y no deja enviar', async () => {
      await open({ entry: { ...ENTRY, currency: 'USD' } }, { accounts: [account(12, 'Banco Nación', 'ARS')] });

      expect(root().querySelector('[data-testid="no-accounts"]')).not.toBeNull();
      expect(button('Registrar pago').disabled).toBe(true);
    });

    it('todos los campos tienen etiqueta', async () => {
      await open();

      for (const label of ['Fecha', 'Monto', 'Cuenta', 'Nota']) {
        expect(field(label), label).toBeTruthy();
      }
    });
  });

  describe('enviar', () => {
    it('manda fecha, monto como número, cuenta y nota, y cierra con la respuesta del backend', async () => {
      await open();
      type('Fecha', '2026-11-05');
      type('Monto', '70.000,50');
      type('Nota', '  Primera parte  ');

      submit();

      expect(api.registerMovement).toHaveBeenCalledTimes(1);
      expect(api.registerMovement.mock.calls[0][0]).toBe(900);
      expect(sent()).toEqual({ date: '2026-11-05', amount: 70000.5, accountId: 12, note: 'Primera parte' });
      const result = ref.close.mock.calls[0][0] as Exclude<MovementDialogResult, { stale: true }>;
      expect(result.movement.amount).toBe(70000.5);
      expect(result.entry.status).toBe('PARTIAL');
    });

    it('sin nota no manda el campo', async () => {
      await open();
      type('Monto', '100');

      submit();

      expect('note' in sent()).toBe(false);
    });

    it('con otra cuenta de la misma moneda manda esa', async () => {
      await open();
      (fixture.componentInstance as unknown as { form: { controls: { accountId: { setValue(v: number): void } } } }).form.controls.accountId.setValue(14);
      type('Monto', '100');

      submit();

      expect(sent().accountId).toBe(14);
    });

    it.each([
      ['0', 'El monto tiene que ser mayor que 0.'],
      ['0,00', 'El monto tiene que ser mayor que 0.'],
      ['-5', 'hasta 2 decimales y sin signo'],
      ['abc', 'hasta 2 decimales y sin signo'],
      ['10,005', 'hasta 2 decimales y sin signo'],
    ])('el monto «%s» se rechaza sin llamar al backend', async (value, message) => {
      await open();
      type('Monto', value);

      submit();

      expect(api.registerMovement).not.toHaveBeenCalled();
      expect(text(field('Monto'))).toContain(message);
    });

    it('sin monto o sin fecha pide completarlos sin llamar al backend', async () => {
      await open({ entry: { ...ENTRY, pendingAmount: 0, status: 'PARTIAL', actualAmount: 120000 } });
      type('Fecha', '');

      submit();

      expect(api.registerMovement).not.toHaveBeenCalled();
      expect(text(field('Fecha'))).toContain('Ingresá la fecha del pago.');
      expect(text(field('Monto'))).toContain('Ingresá el monto.');
    });

    it('una fecha que no existe en el calendario se rechaza sin llamar al backend', async () => {
      await open();
      type('Fecha', '2026-02-30');

      submit();

      expect(api.registerMovement).not.toHaveBeenCalled();
    });

    it('no envía dos veces mientras espera la respuesta', async () => {
      await open();
      api.registerMovement.mockReturnValue(new (await import('rxjs')).Subject());
      type('Monto', '100');

      submit();
      submit();

      expect(api.registerMovement).toHaveBeenCalledTimes(1);
    });
  });

  describe('errores del backend', () => {
    it('una fecha fuera de rango se muestra bajo la fecha con el detalle del backend y conserva lo cargado', async () => {
      await open();
      api.registerMovement.mockReturnValue(
        problem(409, {
          code: 'DATE_OUT_OF_RANGE',
          detail: 'La fecha no puede ser anterior al 22/10/2026: una partida del período 2026-11 admite movimientos desde 10 días antes de su inicio.',
        }),
      );
      type('Fecha', '2026-10-01');
      type('Monto', '1500,50');
      type('Nota', 'Adelanto');

      submit();

      expect(text(field('Fecha'))).toContain('La fecha no puede ser anterior al 22/10/2026');
      expect(ref.close).not.toHaveBeenCalled();
      expect(input('Monto').value).toBe('1500,50');
      expect(input('Nota').value).toBe('Adelanto');
      expect(button('Registrar pago').disabled).toBe(false);
    });

    it('el error de la fecha lleva el foco al campo', async () => {
      await open();
      document.body.appendChild(root());
      api.registerMovement.mockReturnValue(problem(409, { code: 'DATE_OUT_OF_RANGE', detail: 'Fuera de rango.' }));
      type('Monto', '100');

      submit();

      expect(document.activeElement).toBe(input('Fecha'));
      root().remove();
    });

    it('una cuenta de otra moneda se muestra bajo la cuenta', async () => {
      await open();
      api.registerMovement.mockReturnValue(problem(409, { code: 'CURRENCY_MISMATCH', detail: 'x' }));
      type('Monto', '100');

      submit();

      expect(text(field('Cuenta'))).toContain('otra moneda');
    });

    it('un error de validación de un campo sale bajo ese campo', async () => {
      await open();
      api.registerMovement.mockReturnValue(
        problem(400, {
          code: 'VALIDATION_ERROR',
          errors: [{ field: 'accountId', message: 'La cuenta no existe.' }],
        }),
      );
      type('Monto', '100');

      submit();

      expect(text(field('Cuenta'))).toContain('La cuenta no existe.');
    });

    it.each([
      ['PERIOD_CLOSED', 'El período 2026-11 está cerrado y no admite cambios.'],
      ['ENTRY_NOT_PENDING', 'La partida está consolidada: no admite movimientos.'],
    ])('%s se muestra arriba y, al cerrar, avisa que la pantalla estaba desactualizada', async (code, detail) => {
      await open();
      api.registerMovement.mockReturnValue(problem(409, { code, detail }));
      type('Monto', '100');

      submit();
      expect(text(root().querySelector('[role="alert"]'))).toContain(detail);
      button('Cancelar').click();

      expect(ref.close).toHaveBeenCalledWith({ stale: true });
    });

    it('cancelar sin error cierra sin valor', async () => {
      await open();

      button('Cancelar').click();

      expect(ref.close).toHaveBeenCalledWith(undefined);
    });

    it('si no se pueden cargar los movimientos muestra el error y no deja enviar', async () => {
      await open({}, { listError: true });

      expect(root().querySelector('[role="alert"]')).not.toBeNull();
      expect(button('Registrar pago').disabled).toBe(true);
    });
  });
});
