import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import {
  AccountResponse,
  CategorasService,
  CategoryResponse,
  CuentasService,
  EntryUpdateRequest,
  OneOffEntryRequest,
  PartidasService,
  PeriodEntry,
} from '../../api';
import { provideLocale } from '../../core/locale';
import { EntryFormDialog, EntryFormDialogData, EntryFormDialogResult } from './entry-form-dialog';

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
const ACCOUNTS = [account(12, 'Banco Nación', 'ARS'), account(13, 'Caja en dólares', 'USD')];
const CATEGORIES: CategoryResponse[] = [
  { id: 3, name: 'Hogar' },
  { id: 4, name: 'Servicios' },
];

const ENTRY: PeriodEntry = {
  id: 900,
  budgetItemId: null,
  origin: 'ONE_OFF',
  kind: 'EXPENSE',
  name: 'Service del auto',
  categoryId: 3,
  categoryName: 'Hogar',
  accountId: 12,
  accountName: 'Banco Nación',
  currency: 'ARS',
  dueDate: '2026-11-18',
  installmentNumber: null,
  installmentsTotal: null,
  budgetedAmount: 45000,
  actualAmount: 0,
  pendingAmount: 45000,
  forecastAmount: 45000,
  status: 'ESTIMATED',
  manual: false,
  overdue: false,
};

const problem = (status: number, body: object) => throwError(() => new HttpErrorResponse({ status, error: body }));

describe('EntryFormDialog (HU-16)', () => {
  let fixture: ComponentFixture<EntryFormDialog>;
  let api: { createOneOffEntry: ReturnType<typeof vi.fn>; updateEntry: ReturnType<typeof vi.fn> };
  let ref: { close: ReturnType<typeof vi.fn<(result?: EntryFormDialogResult) => void>> };

  async function open(
    data: EntryFormDialogData = { period: '2026-11', suggestedDueDate: '2026-11-01' },
    options: { accounts?: AccountResponse[]; listError?: boolean } = {},
  ) {
    api = {
      createOneOffEntry: vi.fn((period: string, request: OneOffEntryRequest) =>
        of({ ...ENTRY, ...request, id: 901, budgetedAmount: request.budgetedAmount } as unknown as PeriodEntry),
      ),
      updateEntry: vi.fn((id: number, request: EntryUpdateRequest) => of({ ...ENTRY, id, ...request } as unknown as PeriodEntry)),
    };
    ref = { close: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [EntryFormDialog],
      providers: [
        provideLocale(),
        provideRouter([]),
        { provide: PartidasService, useValue: api },
        {
          provide: CuentasService,
          useValue: {
            listAccounts: () =>
              options.listError
                ? problem(0, {})
                : of({ accounts: options.accounts ?? ACCOUNTS, subtotals: [] }),
          },
        },
        { provide: CategorasService, useValue: { listCategories: () => of(CATEGORIES) } },
        { provide: MatDialogRef, useValue: ref },
        { provide: MAT_DIALOG_DATA, useValue: data },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(EntryFormDialog);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  const text = () => (root().textContent ?? '').replace(/\s+/g, ' ');
  const form = () => (fixture.componentInstance as unknown as { form: EntryFormDialog['form' & keyof EntryFormDialog] })['form' as never] as {
    getRawValue(): Record<string, unknown>;
    patchValue(value: Record<string, unknown>): void;
    controls: Record<string, { disabled: boolean; value: unknown; errors: unknown }>;
  };
  const field = (label: string) =>
    Array.from(root().querySelectorAll('mat-form-field')).find((f) =>
      f.querySelector('label')?.textContent?.includes(label),
    )!;
  const type = (label: string, value: string) => {
    const el = field(label).querySelector('input')!;
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
  const created = () => api.createOneOffEntry.mock.calls[0][1] as OneOffEntryRequest;
  const updated = () => api.updateEntry.mock.calls[0][1] as EntryUpdateRequest;

  describe('alta', () => {
    beforeEach(() => open());

    it('tiene todos los campos con etiqueta y el título dice el mes', () => {
      for (const label of ['Nombre', 'Tipo', 'Cuenta', 'Categoría', 'Vencimiento', 'Presupuestado']) {
        expect(field(label), label).toBeTruthy();
      }
      expect(root().querySelector('h2')?.textContent).toContain('Agregar partida de noviembre 2026');
      expect(field('Vencimiento').querySelector('input')!.type).toBe('date');
    });

    it('sugiere el vencimiento que le dan y deja lo demás sin completar', () => {
      expect(form().getRawValue()).toMatchObject({
        name: '',
        kind: null,
        accountId: null,
        categoryId: null,
        dueDate: '2026-11-01',
        budgetedAmount: '',
      });
    });

    it('ofrece las cuentas con su moneda y la categoría opcional', () => {
      const component = fixture.componentInstance as unknown as {
        accounts(): AccountResponse[];
        categories(): CategoryResponse[];
        currencyLabels: Record<string, string>;
      };

      expect(component.accounts().map((a) => `${a.name} · ${component.currencyLabels[a.currency]}`)).toEqual([
        'Banco Nación · $ (ARS)',
        'Caja en dólares · US$ (USD)',
      ]);
      expect(component.categories()).toHaveLength(2);
      expect(text()).toContain('Opcional.');
    });

    it('valida el formato antes de llamar al backend', () => {
      submit();

      expect(api.createOneOffEntry).not.toHaveBeenCalled();
      expect(text()).toContain('Ingresá el nombre de la partida.');
      expect(text()).toContain('Elegí si es un ingreso o un gasto.');
      expect(text()).toContain('Elegí la cuenta.');
      expect(text()).toContain('Ingresá el monto presupuestado.');
      expect(ref.close).not.toHaveBeenCalled();
    });

    it.each(['1.5', '-5', '1,234', 'abc', '1500,'])('el monto «%s» no tiene el formato', (amount) => {
      type('Nombre', 'Regalo');
      form().patchValue({ kind: 'EXPENSE', accountId: 12 });
      type('Presupuestado', amount);

      submit();

      expect(api.createOneOffEntry).not.toHaveBeenCalled();
      expect(text()).toContain('Escribilo con coma decimal, hasta 2 decimales y sin signo');
    });

    it('un vencimiento vacío o que no existe en el calendario no se envía', () => {
      type('Nombre', 'Regalo');
      form().patchValue({ kind: 'EXPENSE', accountId: 12, dueDate: '2026-02-30' });
      type('Presupuestado', '100');

      submit();

      expect(api.createOneOffEntry).not.toHaveBeenCalled();
      expect(text()).toContain('Ingresá una fecha válida.');
    });

    it('envía el pedido con el monto con coma decimal convertido a número y el nombre recortado', () => {
      type('Nombre', '  Service del auto ');
      form().patchValue({ kind: 'EXPENSE', accountId: 12, categoryId: 3 });
      type('Presupuestado', '1.234,50');

      submit();

      expect(api.createOneOffEntry).toHaveBeenCalledWith('2026-11', {
        name: 'Service del auto',
        kind: 'EXPENSE',
        accountId: 12,
        categoryId: 3,
        dueDate: '2026-11-01',
        budgetedAmount: 1234.5,
      });
      expect(ref.close).toHaveBeenCalledTimes(1);
      const result = ref.close.mock.calls[0][0] as { entry: PeriodEntry; created: boolean };
      expect(result.created).toBe(true);
      expect(result.entry.id).toBe(901);
    });

    it('sin categoría envía null', () => {
      type('Nombre', 'Regalo');
      form().patchValue({ kind: 'EXPENSE', accountId: 13 });
      type('Presupuestado', '0');

      submit();

      expect(created().categoryId).toBeNull();
      expect(created().budgetedAmount).toBe(0);
    });

    it('el error de un campo sale debajo de él y no se pierde lo cargado', () => {
      api.createOneOffEntry.mockReturnValue(
        problem(400, {
          code: 'VALIDATION_ERROR',
          detail: 'El vencimiento debe estar entre el 01/10/2026 y el 30/11/2026.',
          errors: [{ field: 'dueDate', message: 'El vencimiento debe estar entre el 01/10/2026 y el 30/11/2026.' }],
        }),
      );
      type('Nombre', 'Regalo');
      form().patchValue({ kind: 'INCOME', accountId: 12, categoryId: 4, dueDate: '2026-12-15' });
      type('Presupuestado', '1500,50');

      submit();

      expect(ref.close).not.toHaveBeenCalled();
      expect(field('Vencimiento').textContent).toContain('El vencimiento debe estar entre el 01/10/2026 y el 30/11/2026.');
      expect(root().querySelector('[role="alert"]')).toBeNull();
      expect(form().getRawValue()).toMatchObject({
        name: 'Regalo',
        kind: 'INCOME',
        accountId: 12,
        categoryId: 4,
        dueDate: '2026-12-15',
        budgetedAmount: '1500,50',
      });
      expect(button('Guardar').disabled).toBe(false);
    });

    it('el error de una referencia sale debajo de su campo', () => {
      api.createOneOffEntry.mockReturnValue(
        problem(400, {
          code: 'VALIDATION_ERROR',
          detail: 'La cuenta no existe.',
          errors: [{ field: 'accountId', message: 'La cuenta no existe.' }],
        }),
      );
      type('Nombre', 'Regalo');
      form().patchValue({ kind: 'EXPENSE', accountId: 12 });
      type('Presupuestado', '10');

      submit();

      expect(field('Cuenta').textContent).toContain('La cuenta no existe.');
    });

    it('un error que no es de un campo se muestra arriba, con su texto, y conserva lo cargado', () => {
      api.createOneOffEntry.mockReturnValue(
        problem(409, { code: 'PERIOD_CLOSED', detail: 'El período 2026-11 está cerrado y no admite cambios.' }),
      );
      type('Nombre', 'Regalo');
      form().patchValue({ kind: 'EXPENSE', accountId: 12 });
      type('Presupuestado', '10');

      submit();

      expect(root().querySelector('[role="alert"]')?.textContent).toContain('Ese mes ya está cerrado');
      expect(form().getRawValue()).toMatchObject({ name: 'Regalo', budgetedAmount: '10' });
      expect(ref.close).not.toHaveBeenCalled();
    });

    it('si el período se cerró, cancelar avisa que la pantalla quedó desactualizada', () => {
      api.createOneOffEntry.mockReturnValue(problem(409, { code: 'PERIOD_CLOSED', detail: 'Cerrado.' }));
      type('Nombre', 'Regalo');
      form().patchValue({ kind: 'EXPENSE', accountId: 12 });
      type('Presupuestado', '10');
      submit();

      button('Cancelar').click();

      expect(ref.close).toHaveBeenCalledWith({ stale: true });
    });

    it('cancelar sin error se cierra sin resultado', () => {
      button('Cancelar').click();

      expect(ref.close).toHaveBeenCalledWith(undefined);
    });

    it('un error de red se muestra y el formulario sigue usable', () => {
      api.createOneOffEntry.mockReturnValue(problem(0, {}));
      type('Nombre', 'Regalo');
      form().patchValue({ kind: 'EXPENSE', accountId: 12 });
      type('Presupuestado', '10');

      submit();

      expect(root().querySelector('[role="alert"]')?.textContent).toContain('No se pudo conectar con el servidor');
      expect(button('Guardar').disabled).toBe(false);
    });
  });

  describe('sin cuentas o con la carga fallida', () => {
    it('sin cuentas manda a cargar una y no deja guardar', async () => {
      await open(undefined, { accounts: [] });

      expect(root().querySelector('[data-testid="no-accounts"]')).not.toBeNull();
      expect(root().querySelector('a')?.getAttribute('href')).toBe('/cuentas');
      expect(button('Guardar').disabled).toBe(true);
    });

    it('si falla la carga muestra el error y no deja guardar', async () => {
      await open(undefined, { listError: true });

      expect(root().querySelector('[role="alert"]')?.textContent).toContain('No se pudo conectar con el servidor');
      expect(button('Guardar').disabled).toBe(true);
    });
  });

  describe('edición', () => {
    beforeEach(() => open({ period: '2026-11', suggestedDueDate: ENTRY.dueDate, entry: ENTRY }));

    it('carga los datos de la partida, con el monto con coma decimal y el tipo deshabilitado', () => {
      expect(root().querySelector('h2')?.textContent).toContain('Editar partida');
      expect(form().getRawValue()).toMatchObject({
        name: 'Service del auto',
        kind: 'EXPENSE',
        accountId: 12,
        categoryId: 3,
        dueDate: '2026-11-18',
        budgetedAmount: '45000,00',
      });
      expect(form().controls['kind'].disabled).toBe(true);
      expect(text()).toContain('El tipo no se puede cambiar');
    });

    it('envía los datos editables, sin el tipo, y se cierra con la partida guardada', () => {
      type('Nombre', 'Cambio de aceite');
      type('Presupuestado', '52.000,5');
      form().patchValue({ accountId: 12, categoryId: 4, dueDate: '2026-11-20' });

      submit();

      expect(api.updateEntry).toHaveBeenCalledWith(900, {
        name: 'Cambio de aceite',
        accountId: 12,
        categoryId: 4,
        dueDate: '2026-11-20',
        budgetedAmount: 52000.5,
      });
      expect(Object.keys(updated())).not.toContain('kind');
      const result = ref.close.mock.calls[0][0] as { entry: PeriodEntry; created: boolean };
      expect(result.created).toBe(false);
    });

    it('vaciar la categoría se pide con clearCategory, no con null', () => {
      form().patchValue({ categoryId: null });

      submit();

      expect(updated().clearCategory).toBe(true);
      expect(updated().categoryId).toBeUndefined();
    });

    it('dejar sin categoría una partida que no tenía no pide vaciar nada', async () => {
      TestBed.resetTestingModule();
      await open({ period: '2026-11', suggestedDueDate: ENTRY.dueDate, entry: { ...ENTRY, categoryId: null, categoryName: null } });

      submit();

      expect(updated().clearCategory).toBe(false);
      expect(updated().categoryId).toBeUndefined();
    });

    it('avisa que cambiar a una cuenta de otra moneda cambia la moneda de la partida', () => {
      expect(text()).toContain('La moneda de la partida es la de su cuenta.');

      form().patchValue({ accountId: 13 });
      fixture.detectChanges();

      expect(field('Cuenta').textContent).toContain('Esta cuenta es en US$ (USD): la partida pasa a esa moneda.');
    });

    it('un 409 de moneda con movimientos se muestra con el texto de la pantalla', () => {
      api.updateEntry.mockReturnValue(
        problem(409, { code: 'CURRENCY_MISMATCH', detail: 'La partida ya tiene movimientos.' }),
      );
      form().patchValue({ accountId: 13 });

      submit();

      expect(root().querySelector('[role="alert"]')?.textContent).toContain('La cuenta elegida es de otra moneda');
      expect(form().getRawValue()).toMatchObject({ accountId: 13 });
    });

    it('una partida ya consolidada: el error se muestra y cancelar pide recargar', () => {
      api.updateEntry.mockReturnValue(problem(409, { code: 'ENTRY_NOT_PENDING', detail: 'Consolidada.' }));

      submit();
      expect(root().querySelector('[role="alert"]')?.textContent).toContain('ya está consolidada');

      button('Cancelar').click();
      expect(ref.close).toHaveBeenCalledWith({ stale: true });
    });
  });
});
