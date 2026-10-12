import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import {
  AccountResponse,
  CuentasService,
  Movement,
  MovementDates,
  MovementRegistered,
  MovementRequest,
  MovimientosService,
  PeriodEntry,
} from '../../api';
import { provideLocale } from '../../core/locale';
import { MovementDialog, MovementDialogData, MovementDialogResult } from './movement-dialog';
import { MovementDeleteDialogResult } from './movement-delete-dialog';

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

/** Lo que informa el backend con hoy 20/11 y la ventana de 10 días para una partida de noviembre. */
const DATES: MovementDates = { earliestDate: '2026-10-22', latestDate: '2026-11-20', earlyDays: 10 };

const problem = (status: number, body: object) => throwError(() => new HttpErrorResponse({ status, error: body }));

describe('MovementDialog (HU-19, HU-20, HU-21)', () => {
  afterEach(() => vi.restoreAllMocks());

  let fixture: ComponentFixture<MovementDialog>;
  let api: {
    listMovements: ReturnType<typeof vi.fn>;
    registerMovement: ReturnType<typeof vi.fn>;
    updateMovement: ReturnType<typeof vi.fn>;
    getMovementDates: ReturnType<typeof vi.fn>;
  };
  let refresh: ReturnType<typeof vi.fn<() => Observable<PeriodEntry | null>>>;
  let confirmDelete: ReturnType<typeof vi.fn>;
  let ref: { close: ReturnType<typeof vi.fn<(result?: MovementDialogResult) => void>> };

  async function open(
    data: Partial<MovementDialogData> = {},
    options: {
      movements?: Movement[];
      accounts?: AccountResponse[];
      listError?: boolean;
      dates?: MovementDates | ((accountId: number) => MovementDates);
      /** Qué responde `refresh` (la vista del mes ya recargada): por omisión, la partida de `data`. */
      refreshed?: PeriodEntry | null;
      /** Qué responde el diálogo de confirmación de eliminar; por omisión, lo cancela. */
      deleteResult?: MovementDeleteDialogResult;
    } = {},
  ) {
    const datesFor = (accountId: number) =>
      typeof options.dates === 'function' ? options.dates(accountId) : (options.dates ?? DATES);
    api = {
      getMovementDates: vi.fn((entryId: number, accountId?: number) => of(datesFor(accountId ?? 0))),
      listMovements: vi.fn(() => (options.listError ? problem(500, {}) : of(options.movements ?? []))),
      updateMovement: vi.fn((id: number, request: MovementRequest) =>
        of({
          movement: { ...movement(id, request.date, request.amount, 'Banco Nación', request.note ?? null) },
          entry: { ...ENTRY, status: 'PARTIAL', actualAmount: request.amount },
        }),
      ),
      registerMovement: vi.fn((entryId: number, request: MovementRequest) =>
        of({
          movement: { ...movement(501, request.date, request.amount, 'Banco Nación', request.note ?? null) },
          entry: { ...ENTRY, status: 'PARTIAL', actualAmount: request.amount },
        }),
      ),
    };
    ref = { close: vi.fn() };
    refresh = vi.fn(() => of(options.refreshed === undefined ? { ...ENTRY, ...data.entry } : options.refreshed));
    // El componente importa `MatDialogModule`, que trae su propio `MatDialog`: se simula el método, no el proveedor.
    confirmDelete = vi.spyOn(MatDialog.prototype, 'open') as unknown as ReturnType<typeof vi.fn>;
    confirmDelete.mockImplementation(() => ({ afterClosed: () => of(options.deleteResult) }));
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
          useValue: { entry: ENTRY, period: '2026-11', readonly: false, refresh, ...data },
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
        Array.from(row.querySelectorAll('td:not(.row-actions)')).map((cell) => text(cell)),
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

    it('con el pendiente en 0 en una Parcial avisa que está cubierta y ofrece «Consolidar» (HU-23)', async () => {
      await open({ entry: { ...ENTRY, status: 'PARTIAL', actualAmount: 120000, pendingAmount: 0 } });

      expect(text(root().querySelector('[data-testid="covered-notice"]'))).toBe(
        '«Expensas» ya está cubierta: pendiente $ 0,00. Sigue Parcial hasta que se consolide.',
      );
      expect(root().querySelector('[data-testid="consolidate-from-dialog"]')).not.toBeNull();
    });

    it('«Consolidar» cierra el diálogo pidiendo consolidar la partida', async () => {
      const covered = { ...ENTRY, status: 'PARTIAL' as const, actualAmount: 120000, pendingAmount: 0 };
      await open({ entry: covered });

      root().querySelector<HTMLButtonElement>('[data-testid="consolidate-from-dialog"]')!.click();

      expect(ref.close).toHaveBeenCalledWith({ consolidate: true, entry: covered });
    });

    it('en un período cerrado el aviso no ofrece «Consolidar»', async () => {
      await open({
        entry: { ...ENTRY, status: 'PARTIAL', actualAmount: 120000, pendingAmount: 0 },
        readonly: true,
      });

      expect(root().querySelector('[data-testid="covered-notice"]')).not.toBeNull();
      expect(root().querySelector('[data-testid="consolidate-from-dialog"]')).toBeNull();
    });

    it('una Estimada con presupuestado 0 no está «cubierta»: no hay aviso', async () => {
      await open({ entry: { ...ENTRY, budgetedAmount: 0, pendingAmount: 0, forecastAmount: 0 } });

      expect(root().querySelector('[data-testid="covered-notice"]')).toBeNull();
    });
  });

  describe('el formulario', () => {
    it('arranca con hoy según el backend, la cuenta de la partida y el pendiente como monto, con coma decimal', async () => {
      await open({ entry: { ...ENTRY, status: 'PARTIAL', actualAmount: 70000, pendingAmount: 50000.5 } });

      expect(input('Fecha').value).toBe('2026-11-20');
      expect(input('Fecha').type).toBe('date');
      expect(input('Monto').value).toBe('50000,50');
      expect(form().controls['accountId'].value).toBe(12);
    });

    it('la fecha con la que arranca es la que informa el backend, no la del navegador', async () => {
      await open({}, { dates: { earliestDate: '2026-11-21', latestDate: '2026-11-30', earlyDays: 10 } });

      expect(input('Fecha').value).toBe('2026-11-30');
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

  describe('la ventana de anticipación (HU-20)', () => {
    const notice = () => root().querySelector('[data-testid="window-notice"]');

    it('explica en una línea la ventana con los días y la fecha más temprana que informa el backend', async () => {
      await open(
        { entry: { ...ENTRY, kind: 'INCOME', name: 'Sueldo' }, period: '2026-12' },
        { dates: { earliestDate: '2026-11-21', latestDate: '2026-11-30', earlyDays: 10 } },
      );

      expect(text(notice())).toBe(
        'Se puede fechar hasta 10 días antes del inicio de diciembre 2026. Fecha más temprana con esta cuenta: 21/11/2026.',
      );
      expect(api.getMovementDates).toHaveBeenCalledWith(900, 12);
    });

    it('si la ventana es de otro valor, el texto la sigue', async () => {
      await open({}, { dates: { earliestDate: '2026-10-29', latestDate: '2026-11-20', earlyDays: 3 } });

      expect(text(notice())).toContain('hasta 3 días antes del inicio de noviembre 2026');
      expect(text(notice())).not.toContain('10 días');
    });

    it('al cambiar de cuenta se vuelve a pedir la fecha más temprana y la fecha escrita no se toca', async () => {
      await open(
        {},
        {
          dates: (accountId) =>
            accountId === 14
              ? { earliestDate: '2026-11-05', latestDate: '2026-11-20', earlyDays: 10 }
              : DATES,
        },
      );
      type('Fecha', '2026-11-01');
      expect(text(notice())).toContain('22/10/2026');

      const control = fixture.componentInstance as unknown as {
        form: { controls: { accountId: { setValue(value: number): void } } };
      };
      control.form.controls.accountId.setValue(14);
      fixture.detectChanges();

      expect(api.getMovementDates).toHaveBeenLastCalledWith(900, 14);
      expect(text(notice())).toContain('Fecha más temprana con esta cuenta: 05/11/2026.');
      expect(input('Fecha').value).toBe('2026-11-01');
    });

    it('si la ventana todavía no abrió dice desde cuándo y no deja enviar', async () => {
      await open(
        { period: '2027-03' },
        { dates: { earliestDate: '2027-02-19', latestDate: '2026-10-10', earlyDays: 10 } },
      );

      expect(text(notice())).toBe(
        'Todavía no se pueden registrar movimientos de esta partida: se admiten desde el 19/02/2027.',
      );
      expect(button('Registrar pago').disabled).toBe(true);
      submit();
      expect(api.registerMovement).not.toHaveBeenCalled();
    });

    it('el último día antes de abrir no deja enviar y el día en que abre sí', async () => {
      await open({}, { dates: { earliestDate: '2026-11-21', latestDate: '2026-11-20', earlyDays: 10 } });
      expect(button('Registrar pago').disabled).toBe(true);
      TestBed.resetTestingModule();

      await open({}, { dates: { earliestDate: '2026-11-21', latestDate: '2026-11-21', earlyDays: 10 } });
      expect(button('Registrar pago').disabled).toBe(false);
    });

    it('si no se pueden cargar las fechas se muestra el error de carga y no se deja enviar', async () => {
      await open({}, { listError: true });

      expect(notice()).toBeNull();
      expect(button('Registrar pago').disabled).toBe(true);
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
      const result = ref.close.mock.calls[0][0] as MovementRegistered;
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
  describe('corregir o eliminar un movimiento (HU-21)', () => {
    const PARTIAL: PeriodEntry = { ...ENTRY, status: 'PARTIAL', actualAmount: 120000, pendingAmount: 0 };
    const TWO = [
      movement(1, '2026-11-05', 70000, 'Banco Nación', 'Primera parte'),
      movement(2, '2026-11-12', 50000, 'Billetera', null),
    ];
    const rowOf = (id: number) => root().querySelector<HTMLElement>(`[data-movement-id="${id}"]`)!;
    const action = (id: number, testid: 'edit-movement' | 'delete-movement') =>
      rowOf(id).querySelector<HTMLButtonElement>(`[data-testid="${testid}"]`)!;
    const click = async (el: HTMLElement) => {
      el.click();
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };

    describe('las acciones de la lista', () => {
      it('cada fila de una partida pendiente ofrece Editar y Eliminar, con el movimiento en su nombre', async () => {
        await open({ entry: PARTIAL }, { movements: TWO });

        expect(root().querySelectorAll('[data-testid="edit-movement"]').length).toBe(2);
        expect(root().querySelectorAll('[data-testid="delete-movement"]').length).toBe(2);
        expect(action(1, 'edit-movement').getAttribute('aria-label')).toBe('Editar pago del 05/11/2026 por $ 70.000,00');
        expect(action(2, 'delete-movement').getAttribute('aria-label')).toBe(
          'Eliminar pago del 12/11/2026 por $ 50.000,00',
        );
      });

      it('con la partida consolidada no hay acciones', async () => {
        await open({ entry: { ...PARTIAL, status: 'CONSOLIDATED' } }, { movements: TWO });

        expect(root().querySelectorAll('[data-testid="movement-row"]').length).toBe(2);
        expect(root().querySelector('[data-testid="edit-movement"]')).toBeNull();
        expect(root().querySelector('[data-testid="delete-movement"]')).toBeNull();
        expect(text(root().querySelector('thead'))).not.toContain('Acciones');
      });

      it('con el período cerrado no hay acciones', async () => {
        await open({ entry: PARTIAL, readonly: true }, { movements: TWO });

        expect(root().querySelector('[data-testid="edit-movement"]')).toBeNull();
        expect(root().querySelector('[data-testid="delete-movement"]')).toBeNull();
      });
    });

    describe('editar', () => {
      it('«Editar» carga el formulario del alta con los valores del movimiento y lo dice', async () => {
        await open({ entry: PARTIAL }, { movements: TWO });

        await click(action(1, 'edit-movement'));

        expect(form().controls['date'].value).toBe('2026-11-05');
        expect(form().controls['amount'].value).toBe('70000,00');
        expect(form().controls['accountId'].value).toBe(12);
        expect(form().controls['note'].value).toBe('Primera parte');
        expect(text(root().querySelector('h2'))).toBe('Corregir pago · Expensas');
        expect(text(root().querySelector('[data-testid="editing-banner"]'))).toContain('05/11/2026');
        expect(text(root().querySelector('[data-testid="submit-movement"]'))).toBe('Guardar cambios');
      });

      it('mantiene la explicación de la ventana y vuelve a pedir la fecha más temprana de la cuenta del movimiento', async () => {
        await open({ entry: PARTIAL }, { movements: [movement(2, '2026-11-12', 50000, 'Billetera', null)] });
        api.getMovementDates.mockClear();

        await click(action(2, 'edit-movement'));

        expect(text(root().querySelector('[data-testid="window-notice"]'))).toContain('hasta 10 días antes del inicio de noviembre 2026');
        expect(api.getMovementDates).toHaveBeenCalledWith(900, 12);
      });

      it('guardar envía PUT al movimiento y a la partida no le cambia nada: la pantalla y la lista se vuelven a pedir', async () => {
        await open({ entry: PARTIAL }, { movements: TWO, refreshed: { ...PARTIAL, actualAmount: 80000, pendingAmount: 40000 } });
        await click(action(1, 'edit-movement'));
        api.listMovements.mockClear();

        type('Monto', '30000,00');
        submit();
        await fixture.whenStable();
        fixture.detectChanges();

        expect(api.updateMovement).toHaveBeenCalledWith(1, {
          date: '2026-11-05',
          amount: 30000,
          accountId: 12,
          note: 'Primera parte',
        });
        expect(api.registerMovement).not.toHaveBeenCalled();
        expect(refresh).toHaveBeenCalled();
        expect(api.listMovements).toHaveBeenCalledWith(900);
        // Los valores salen de lo que devolvió el backend: el diálogo no suma nada.
        expect(text(root().querySelector('[data-testid="actual"]'))).toBe('$ 80.000,00');
        expect(text(root().querySelector('[data-testid="pending"]'))).toBe('$ 40.000,00');
        expect(text(root().querySelector('[data-testid="change-notice"]'))).toBe('Pago corregido: ahora es de $ 30.000,00.');
        expect(ref.close).not.toHaveBeenCalled();
        // Vuelve el formulario del alta.
        expect(text(root().querySelector('h2'))).toBe('Registrar pago · Expensas');
      });

      it('después de guardar, el formulario del alta vuelve sin errores aunque el monto quede vacío (pendiente 0)', async () => {
        await open({ entry: PARTIAL }, { movements: TWO });
        await click(action(1, 'edit-movement'));

        type('Monto', '30000,00');
        submit();
        await fixture.whenStable();
        fixture.detectChanges();

        expect(form().controls['amount'].value).toBe('');
        expect(root().querySelector('mat-error')).toBeNull();
      });

      it('al empezar a corregir después de un error, el formulario no arrastra el «enviado» anterior', async () => {
        await open({ entry: PARTIAL }, { movements: TWO });
        submit();
        fixture.detectChanges();
        expect(root().querySelector('mat-error')).not.toBeNull();

        await click(action(1, 'edit-movement'));

        expect(root().querySelector('mat-error')).toBeNull();
      });

      it('cambiar de cuenta envía la cuenta nueva', async () => {
        await open({ entry: PARTIAL }, { movements: TWO });
        await click(action(1, 'edit-movement'));
        (fixture.componentInstance as unknown as { form: { controls: { accountId: { setValue(v: number): void } } } }).form.controls.accountId.setValue(14);

        submit();
        await fixture.whenStable();

        expect((api.updateMovement.mock.calls[0][1] as MovementRequest).accountId).toBe(14);
      });

      it('un error de fecha sale bajo el campo con el detail del backend y no pierde lo cargado', async () => {
        await open({ entry: PARTIAL }, { movements: TWO });
        api.updateMovement.mockImplementation(() =>
          problem(409, {
            code: 'DATE_OUT_OF_RANGE',
            title: 'Fecha fuera de rango',
            detail: 'La fecha no puede ser posterior a hoy (20/11/2026).',
          }),
        );
        await click(action(1, 'edit-movement'));

        type('Fecha', '2026-11-25');
        type('Monto', '31000,00');
        submit();
        await fixture.whenStable();
        fixture.detectChanges();

        expect(text(field('Fecha'))).toContain('La fecha no puede ser posterior a hoy (20/11/2026).');
        expect(form().controls['amount'].value).toBe('31000,00');
        expect(text(root().querySelector('h2'))).toBe('Corregir pago · Expensas');
        expect(ref.close).not.toHaveBeenCalled();
      });

      it('si el backend dice que el período se cerró, avisa, vuelve a pedir y al cerrar la pantalla de atrás se recarga', async () => {
        await open({ entry: PARTIAL }, { movements: TWO });
        api.updateMovement.mockImplementation(() =>
          problem(409, { code: 'PERIOD_CLOSED', title: 'Período cerrado', detail: 'El período 2026-11 está cerrado y no admite cambios.' }),
        );
        await click(action(1, 'edit-movement'));

        submit();
        await fixture.whenStable();
        fixture.detectChanges();

        expect(text(root().querySelector('[role="alert"]'))).toContain('está cerrado');
        expect(refresh).toHaveBeenCalled();
        root().querySelector<HTMLButtonElement>('mat-dialog-actions button')!.click();
        expect(ref.close).toHaveBeenCalledWith({ stale: true });
      });

      it('«Cancelar corrección» vuelve al formulario del alta sin enviar nada', async () => {
        await open({ entry: PARTIAL }, { movements: TWO });
        await click(action(1, 'edit-movement'));

        await click(root().querySelector<HTMLElement>('[data-testid="cancel-edit"]')!);

        expect(api.updateMovement).not.toHaveBeenCalled();
        expect(text(root().querySelector('h2'))).toBe('Registrar pago · Expensas');
        expect(root().querySelector('[data-testid="editing-banner"]')).toBeNull();
        expect(form().controls['date'].value).toBe('2026-11-20');
      });

      it('con la ventana sin abrir se puede corregir un movimiento existente', async () => {
        await open(
          { entry: PARTIAL },
          { movements: TWO, dates: { earliestDate: '2026-12-01', latestDate: '2026-11-20', earlyDays: 10 } },
        );
        expect(button('Registrar pago').disabled).toBe(true);

        await click(action(1, 'edit-movement'));

        expect(button('Guardar cambios').disabled).toBe(false);
      });
    });

    describe('eliminar', () => {
      it('pide la confirmación con el movimiento exacto y, sin confirmar, no elimina nada', async () => {
        await open({ entry: PARTIAL }, { movements: TWO });

        await click(action(2, 'delete-movement'));

        const config = confirmDelete.mock.calls[0][1] as { data: { movement: Movement; entryName: string; isOnly: boolean } };
        expect(config.data.movement.id).toBe(2);
        expect(config.data.movement.date).toBe('2026-11-12');
        expect(config.data.movement.amount).toBe(50000);
        expect(config.data.movement.accountName).toBe('Billetera');
        expect(config.data.entryName).toBe('Expensas');
        expect(config.data.isOnly).toBe(false);
        expect(refresh).not.toHaveBeenCalled();
      });

      it('al confirmar vuelve a pedir la lista y la partida, avisa y el foco pasa a la fila que ocupa el lugar', async () => {
        await open(
          { entry: PARTIAL },
          { movements: TWO, deleteResult: { deleted: true }, refreshed: { ...PARTIAL, actualAmount: 50000, pendingAmount: 70000 } },
        );
        api.listMovements.mockImplementation(() => of([TWO[1]]));
        document.body.appendChild(root());

        await click(action(1, 'delete-movement'));
        await fixture.whenStable();
        fixture.detectChanges();

        expect(refresh).toHaveBeenCalled();
        expect(root().querySelectorAll('[data-testid="movement-row"]').length).toBe(1);
        expect(text(root().querySelector('[data-testid="change-notice"]'))).toBe('Pago de $ 70.000,00 eliminado.');
        expect(text(root().querySelector('[data-testid="actual"]'))).toBe('$ 50.000,00');
        expect(document.activeElement).toBe(action(2, 'edit-movement'));
        root().remove();
      });

      it('al eliminar el último, la partida vuelve a Estimada con lo que informa el backend y el foco va al formulario', async () => {
        await open(
          { entry: { ...PARTIAL, actualAmount: 70000, pendingAmount: 50000 } },
          {
            movements: [TWO[0]],
            deleteResult: { deleted: true },
            refreshed: { ...ENTRY, status: 'ESTIMATED', actualAmount: 0, pendingAmount: 120000 },
          },
        );
        api.listMovements.mockImplementation(() => of([]));
        document.body.appendChild(root());

        await click(action(1, 'delete-movement'));
        await fixture.whenStable();
        fixture.detectChanges();

        expect(text(root().querySelector('[data-testid="no-movements"]'))).toBe('Todavía no tiene movimientos.');
        expect(text(root().querySelector('[data-testid="actual"]'))).toBe('$ 0,00');
        expect(text(root().querySelector('[data-testid="pending"]'))).toBe('$ 120.000,00');
        expect(form().controls['amount'].value).toBe('120000,00');
        expect(document.activeElement).toBe(root().querySelector('[formcontrolname="date"]'));
        root().remove();
      });

      it('cuando es el único movimiento, la confirmación lo dice', async () => {
        await open({ entry: { ...PARTIAL, actualAmount: 70000, pendingAmount: 50000 } }, { movements: [TWO[0]] });

        await click(action(1, 'delete-movement'));

        expect((confirmDelete.mock.calls[0][1] as { data: { isOnly: boolean } }).data.isOnly).toBe(true);
      });

      it('si la confirmación dice que la pantalla estaba desactualizada, vuelve a pedir y al cerrar avisa', async () => {
        await open({ entry: PARTIAL }, { movements: TWO, deleteResult: { stale: true } });

        await click(action(1, 'delete-movement'));
        root().querySelector<HTMLButtonElement>('mat-dialog-actions button')!.click();

        expect(refresh).toHaveBeenCalled();
        expect(ref.close).toHaveBeenCalledWith({ stale: true });
      });

      it('si la partida ya no está en el mes, cierra avisando que la pantalla está desactualizada', async () => {
        await open({ entry: PARTIAL }, { movements: TWO, deleteResult: { deleted: true }, refreshed: null });

        await click(action(1, 'delete-movement'));

        expect(ref.close).toHaveBeenCalledWith({ stale: true });
      });
    });
  });
});
