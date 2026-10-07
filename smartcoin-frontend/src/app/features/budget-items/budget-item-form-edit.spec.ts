import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import {
  AccountResponse,
  BudgetItemDetail,
  BudgetItemRequest,
  CategoryResponse,
  ConceptosService,
} from '../../api';
import { provideLocale } from '../../core/locale';
import { BudgetItemForm } from './budget-item-form';

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
const CATEGORIES: CategoryResponse[] = [{ id: 3, name: 'Impuestos' }];

const REASONS = {
  kind: 'El tipo no se puede cambiar: para cambiarlo, dá de baja el Concepto y creá otro.',
  periodicity: 'La periodicidad no se puede cambiar: para cambiarla, dá de baja el Concepto y creá otro.',
  startPeriod: 'El período de inicio no se puede cambiar: para cambiarlo, dá de baja el Concepto y creá otro.',
  endPeriod: 'El período de fin no se puede cambiar: para cambiarlo, dá de baja el Concepto y creá otro.',
  installments: 'Las cuotas no se pueden cambiar: para cambiarlas, dá de baja el Concepto y creá otro.',
};
const locked = (reason: string) => ({ editable: false, reason });

/** Un Concepto mensual con fin, 23 partidas por reemplazar y 2 editadas. */
const ITEM: BudgetItemDetail = {
  id: 31,
  name: 'Monotributo',
  kind: 'EXPENSE',
  defaultAccountId: 12,
  currency: 'ARS',
  categoryId: 3,
  periodicity: 'MONTHLY',
  dueDay: 20,
  dueMonthOffset: 0,
  startPeriod: '2026-10',
  endPeriod: '2028-12',
  estimationRule: 'AVERAGE_LAST_3',
  currentAmount: 85000.5,
  editability: {
    kind: locked(REASONS.kind),
    periodicity: locked(REASONS.periodicity),
    startPeriod: locked(REASONS.startPeriod),
    endPeriod: locked(REASONS.endPeriod),
    installments: locked(REASONS.installments),
  },
  entryCounts: { pendingNotManual: 23, pendingManual: 2 },
};

/** La Heladera: 12 cuotas, primera 4, de 2026-10 a 2027-06. */
const INSTALLMENTS: BudgetItemDetail = {
  ...ITEM,
  id: 32,
  name: 'Heladera',
  categoryId: null,
  endPeriod: '2027-06',
  installmentsTotal: 12,
  firstInstallmentNumber: 4,
  entryCounts: { pendingNotManual: 9, pendingManual: 0 },
};

const problem = (status: number, body: object) =>
  throwError(() => new HttpErrorResponse({ status, error: body }));

describe('BudgetItemForm · edición (HU-13)', () => {
  let fixture: ComponentFixture<BudgetItemForm>;
  let api: {
    createBudgetItem: ReturnType<typeof vi.fn>;
    updateBudgetItem: ReturnType<typeof vi.fn>;
  };
  let dialogAnswer: boolean | undefined;
  let dialogOpened: ReturnType<typeof vi.fn<(...args: unknown[]) => void>>;
  let updated: ReturnType<typeof vi.fn<(item: BudgetItemDetail) => void>>;

  async function setup(item: BudgetItemDetail = ITEM) {
    api = {
      createBudgetItem: vi.fn(),
      updateBudgetItem: vi.fn((id: number, request: BudgetItemRequest) =>
        of({ ...item, ...request, id } as unknown as BudgetItemDetail),
      ),
    };
    dialogAnswer = true;
    dialogOpened = vi.fn<(...args: unknown[]) => void>();
    await TestBed.configureTestingModule({
      imports: [BudgetItemForm],
      providers: [
        provideLocale(),
        { provide: ConceptosService, useValue: api },
        {
          provide: MatDialog,
          useValue: {
            open: (...args: unknown[]) => {
              dialogOpened(...args);
              return { afterClosed: () => of(dialogAnswer) };
            },
          },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(BudgetItemForm);
    fixture.componentRef.setInput('accounts', ACCOUNTS);
    fixture.componentRef.setInput('categories', CATEGORIES);
    fixture.componentRef.setInput('item', item);
    updated = vi.fn<(item: BudgetItemDetail) => void>();
    fixture.componentInstance.updated.subscribe((value) => updated(value));
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  const text = () => (root().textContent ?? '').replace(/\s+/g, ' ');
  const form = () => fixture.componentInstance['form'];
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
  const alert = () => root().querySelector('[role="alert"]')?.textContent ?? '';
  const request = () => api.updateBudgetItem.mock.calls[0][1] as BudgetItemRequest;

  describe('campos no editables', () => {
    beforeEach(() => setup());

    it('carga los datos del Concepto, con el monto con coma decimal', () => {
      expect(form().getRawValue()).toEqual({
        name: 'Monotributo',
        kind: 'EXPENSE',
        defaultAccountId: 12,
        categoryId: 3,
        periodicity: 'MONTHLY',
        dueDay: 20,
        dueInPreviousMonth: false,
        startPeriod: '2026-10',
        endPeriod: '2028-12',
        inInstallments: false,
        installmentsTotal: null,
        firstInstallmentNumber: 1,
        estimationRule: 'AVERAGE_LAST_3',
        currentAmount: '85000,50',
      });
    });

    it.each(['kind', 'periodicity', 'startPeriod', 'endPeriod', 'inInstallments'] as const)(
      '%s está deshabilitado',
      (name) => {
        expect(form().controls[name].disabled).toBe(true);
      },
    );

    it.each(['name', 'defaultAccountId', 'categoryId', 'dueDay', 'dueInPreviousMonth', 'estimationRule', 'currentAmount'] as const)(
      '%s se puede editar',
      (name) => {
        expect(form().controls[name].enabled).toBe(true);
      },
    );

    it('los campos deshabilitados se ven deshabilitados en la pantalla, no solo en el formulario', () => {
      expect(input('Período de inicio').disabled).toBe(true);
      expect(input('Período de fin').disabled).toBe(true);
      expect(field('Tipo').querySelector('mat-select')?.getAttribute('aria-disabled')).toBe('true');
      expect(field('Periodicidad').querySelector('mat-select')?.getAttribute('aria-disabled')).toBe('true');
      expect(root().querySelector<HTMLInputElement>('mat-checkbox input')!.disabled).toBe(false);
      const installmentsBox = Array.from(root().querySelectorAll('mat-checkbox')).find((c) =>
        c.textContent?.includes('Es en cuotas'),
      )!;
      expect(installmentsBox.querySelector<HTMLInputElement>('input')!.disabled).toBe(true);
      expect(input('Nombre').disabled).toBe(false);
      expect(input('Monto vigente').disabled).toBe(false);
    });

    it('muestra el motivo de cada dato bloqueado, el que informa el backend', () => {
      expect(field('Tipo').textContent).toContain(REASONS.kind);
      expect(field('Periodicidad').textContent).toContain(REASONS.periodicity);
      expect(field('Período de inicio').textContent).toContain(REASONS.startPeriod);
      expect(field('Período de fin').textContent).toContain(REASONS.endPeriod);
      expect(text()).toContain(REASONS.installments);
    });

    it('no repite el aviso del alta ni ofrece crear', () => {
      expect(text()).not.toContain('no se pueden cambiar después de guardar');
      expect(root().querySelector('button[type="submit"]')?.textContent?.trim()).toBe('Guardar cambios');
    });

    it('explica qué hace cada cambio con las partidas', () => {
      expect(field('Cuenta por defecto').textContent).toContain('Solo podés cambiarla por otra cuenta en $ (ARS)');
      expect(field('Día de vencimiento').textContent).toContain('Recalcula el vencimiento');
      expect(field('Monto vigente').textContent).toContain('Reemplaza el presupuestado');
      expect(text()).toContain('Un cambio de regla se aplica desde la próxima consolidación.');
    });

    it('un Concepto sin categoría la carga vacía', async () => {
      TestBed.resetTestingModule();
      await setup({ ...ITEM, categoryId: null });

      expect(form().controls.categoryId.value).toBeNull();
    });
  });

  describe('en cuotas', () => {
    beforeEach(() => setup(INSTALLMENTS));

    it('muestra el total, la primera cuota y el fin calculado, todo deshabilitado', () => {
      expect(input('Total de cuotas').value).toBe('12');
      expect(input('Primera cuota').value).toBe('4');
      expect(input('Período de fin').value).toBe('2027-06');
      for (const label of ['Total de cuotas', 'Primera cuota', 'Período de fin']) {
        expect(input(label).disabled, label).toBe(true);
      }
      expect(text()).not.toContain('El período de fin no se ingresa');
    });

    it('envía las cuotas tal cual y el fin vacío: es calculado', () => {
      type('Nombre', 'Heladera Samsung');
      submit();

      expect(api.updateBudgetItem).toHaveBeenCalledOnce();
      expect(api.updateBudgetItem.mock.calls[0][0]).toBe(32);
      expect(request()).toMatchObject({
        name: 'Heladera Samsung',
        installmentsTotal: 12,
        firstInstallmentNumber: 4,
        endPeriod: null,
        startPeriod: '2026-10',
        categoryId: null,
      });
    });
  });

  describe('guardar', () => {
    beforeEach(() => setup());

    it('manda a la API todos los datos, con los no editables como están, y emite el Concepto guardado', () => {
      type('Nombre', '  Monotributo B  ');
      submit();

      expect(api.createBudgetItem).not.toHaveBeenCalled();
      expect(api.updateBudgetItem).toHaveBeenCalledOnce();
      expect(api.updateBudgetItem.mock.calls[0][0]).toBe(31);
      expect(request()).toEqual({
        name: 'Monotributo B',
        kind: 'EXPENSE',
        defaultAccountId: 12,
        categoryId: 3,
        periodicity: 'MONTHLY',
        dueDay: 20,
        dueMonthOffset: 0,
        startPeriod: '2026-10',
        endPeriod: '2028-12',
        estimationRule: 'AVERAGE_LAST_3',
        currentAmount: 85000.5,
      });
      expect(updated).toHaveBeenCalledOnce();
      expect(updated.mock.calls[0][0].name).toBe('Monotributo B');
    });

    it('sin cambiar el monto no pide confirmación', () => {
      type('Día de vencimiento', '31');
      submit();

      expect(dialogOpened).not.toHaveBeenCalled();
      expect(request().dueDay).toBe(31);
    });

    it('el mismo monto escrito con otro formato no es un cambio', () => {
      type('Monto vigente', '85.000,5');
      submit();

      expect(dialogOpened).not.toHaveBeenCalled();
      expect(api.updateBudgetItem).toHaveBeenCalledOnce();
    });

    it('envía el desfase y la cuenta elegidos', () => {
      form().patchValue({ defaultAccountId: 13, dueInPreviousMonth: true });
      submit();

      expect(request()).toMatchObject({ defaultAccountId: 13, dueMonthOffset: -1 });
    });

    it('con datos inválidos marca los campos y no llama a la API', () => {
      type('Nombre', '   ');
      type('Día de vencimiento', '40');
      submit();

      expect(text()).toContain('Ingresá el nombre del Concepto.');
      expect(text()).toContain('Tiene que ser un día del 1 al 31.');
      expect(api.updateBudgetItem).not.toHaveBeenCalled();
    });

    it('con un monto sin formato no pide confirmación ni llama a la API', () => {
      type('Monto vigente', 'mucho');
      submit();

      expect(text()).toContain('Escribilo sin signo, con coma decimal');
      expect(dialogOpened).not.toHaveBeenCalled();
      expect(api.updateBudgetItem).not.toHaveBeenCalled();
    });

    it('con una cuenta de otra moneda muestra el error del backend y conserva lo cargado', () => {
      api.updateBudgetItem.mockReturnValue(
        problem(409, { code: 'CURRENCY_MISMATCH', detail: 'La cuenta por defecto tiene que ser de la misma moneda.' }),
      );
      type('Nombre', 'Otro nombre');
      form().patchValue({ defaultAccountId: 13 });
      submit();

      expect(alert()).toBe('La cuenta elegida es de otra moneda. Elegí una cuenta en la misma moneda.');
      expect(updated).not.toHaveBeenCalled();
      expect(input('Nombre').value).toBe('Otro nombre');
      expect(form().controls.defaultAccountId.value).toBe(13);
      expect(root().querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(false);
    });

    it('un dato no editable que el backend rechaza se muestra con su mensaje', () => {
      api.updateBudgetItem.mockReturnValue(problem(409, { code: 'FIELD_NOT_EDITABLE', detail: 'x' }));
      submit();

      expect(alert()).toBe('Ese dato ya no se puede editar. Actualizá la pantalla y probá de nuevo.');
    });
  });

  describe('aviso del monto vigente (criterio 3)', () => {
    beforeEach(() => setup());

    const dialogMessage = () => (dialogOpened.mock.calls[0][1] as { data: { message: string } }).data.message;

    it('antes de guardar avisa cuántas partidas reemplaza y que las editadas no cambian', () => {
      type('Monto vigente', '90.000,50');
      submit();

      expect(dialogOpened).toHaveBeenCalledOnce();
      const data = (dialogOpened.mock.calls[0][1] as { data: { title: string; confirmLabel: string } }).data;
      expect(data.title).toBe('Cambiar el monto vigente');
      expect(data.confirmLabel).toBe('Cambiar monto');
      expect(dialogMessage()).toBe(
        'Monto vigente: de $ 85.000,50 a $ 90.000,50. ' +
          'Se reemplazará el monto presupuestado de 23 partidas pendientes sin editar de los períodos abiertos. ' +
          'Las 2 partidas editadas a mano no cambian. ' +
          'Las consolidadas y las de períodos cerrados tampoco cambian.',
      );
    });

    it('no guarda hasta que el usuario confirma', () => {
      dialogAnswer = undefined;
      type('Monto vigente', '90000');
      submit();

      expect(api.updateBudgetItem).not.toHaveBeenCalled();
      expect(updated).not.toHaveBeenCalled();
    });

    it('si cancela no guarda y deja lo escrito', () => {
      dialogAnswer = false;
      type('Monto vigente', '90000');
      submit();

      expect(api.updateBudgetItem).not.toHaveBeenCalled();
      expect(input('Monto vigente').value).toBe('90000');
    });

    it('al confirmar guarda con el monto nuevo', () => {
      dialogAnswer = true;
      type('Monto vigente', '90.000,50');
      submit();

      expect(api.updateBudgetItem).toHaveBeenCalledOnce();
      expect(request().currentAmount).toBe(90000.5);
      expect(updated).toHaveBeenCalledOnce();
    });

    it('usa los conteos del Concepto: sin partidas por reemplazar lo dice', async () => {
      TestBed.resetTestingModule();
      await setup({ ...ITEM, entryCounts: { pendingNotManual: 0, pendingManual: 0 } });
      type('Monto vigente', '1');
      submit();

      expect(dialogMessage()).toContain('Hoy no hay partidas pendientes sin editar');
      expect(dialogMessage()).toContain('Las partidas editadas a mano no cambian (hoy no hay ninguna).');
    });

    it('con un Concepto en dólares muestra los montos con su moneda', async () => {
      TestBed.resetTestingModule();
      await setup({ ...ITEM, currency: 'USD', currentAmount: 100 });
      type('Monto vigente', '120');
      submit();

      expect(dialogMessage()).toContain('de US$ 100,00 a US$ 120,00');
    });

    it('un cambio de otro dato junto con el monto también pasa por el aviso', () => {
      type('Nombre', 'Otro');
      type('Monto vigente', '90000');
      submit();

      expect(dialogOpened).toHaveBeenCalledOnce();
    });
  });
});
