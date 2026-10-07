import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import {
  AccountResponse,
  BudgetItemRequest,
  BudgetItemResponse,
  CategoryResponse,
  ConceptosService,
} from '../../api';
import { BudgetItemForm } from './budget-item-form';

const SUGGESTED = '2026-10';
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
  { id: 3, name: 'Impuestos' },
  { id: 4, name: 'Servicios' },
];

const created = (request: BudgetItemRequest): BudgetItemResponse => ({
  id: 31,
  name: request.name,
  kind: request.kind,
  defaultAccountId: request.defaultAccountId,
  currency: 'ARS',
  categoryId: request.categoryId,
  periodicity: request.periodicity,
  dueDay: request.dueDay,
  dueMonthOffset: request.dueMonthOffset,
  startPeriod: request.startPeriod,
  endPeriod: request.endPeriod,
  estimationRule: request.estimationRule,
  currentAmount: request.currentAmount,
  generation: { entryCount: 25, firstPeriod: '2026-10', lastPeriod: '2028-10', firstDueDate: '2026-10-20' },
});

const problem = (status: number, body: object) =>
  throwError(() => new HttpErrorResponse({ status, error: body }));

describe('BudgetItemForm', () => {
  let fixture: ComponentFixture<BudgetItemForm>;
  let api: { createBudgetItem: ReturnType<typeof vi.fn> };
  let saved: ReturnType<typeof vi.fn<(item: BudgetItemResponse) => void>>;

  beforeEach(async () => {
    api = { createBudgetItem: vi.fn((request: BudgetItemRequest) => of(created(request))) };
    await TestBed.configureTestingModule({
      imports: [BudgetItemForm],
      providers: [{ provide: ConceptosService, useValue: api }],
    }).compileComponents();
    fixture = TestBed.createComponent(BudgetItemForm);
    fixture.componentRef.setInput('accounts', ACCOUNTS);
    fixture.componentRef.setInput('categories', CATEGORIES);
    fixture.componentRef.setInput('suggestedStartPeriod', SUGGESTED);
    saved = vi.fn<(item: BudgetItemResponse) => void>();
    fixture.componentInstance.saved.subscribe((item) => saved(item));
    fixture.detectChanges();
  });

  const root = () => fixture.nativeElement as HTMLElement;
  const text = () => root().textContent ?? '';
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
  const request = () => api.createBudgetItem.mock.calls[0][0] as BudgetItemRequest;
  /** Completa lo obligatorio que no tiene valor por defecto. */
  const fillRequired = (amount = '85000') => {
    type('Nombre', 'Monotributo');
    form().patchValue({ kind: 'EXPENSE', defaultAccountId: 12 });
    type('Día de vencimiento', '20');
    type('Monto vigente', amount);
  };

  it('tiene todos los campos con etiqueta', () => {
    for (const label of [
      'Nombre',
      'Tipo',
      'Periodicidad',
      'Cuenta por defecto',
      'Categoría',
      'Día de vencimiento',
      'Período de inicio',
      'Período de fin',
      'Monto vigente',
    ]) {
      expect(field(label), label).toBeTruthy();
    }
    expect(root().querySelector('mat-checkbox label')?.textContent).toContain('Vence el mes anterior');
    const group = root().querySelector('mat-radio-group')!;
    expect(document.getElementById(group.getAttribute('aria-labelledby')!)?.textContent).toBe(
      'Regla de estimación',
    );
  });

  it('arranca mensual, con último valor, sin desfase y con el período de inicio sugerido', () => {
    expect(form().getRawValue()).toMatchObject({
      periodicity: 'MONTHLY',
      estimationRule: 'LAST_VALUE',
      dueInPreviousMonth: false,
      startPeriod: SUGGESTED,
      endPeriod: '',
      categoryId: null,
    });
    expect(input('Período de inicio').type).toBe('month');
    expect(input('Período de fin').type).toBe('month');
  });

  it('el desfase se presenta con el texto de la historia', () => {
    expect(text()).toContain(
      'Vence el mes anterior al período, por ejemplo un sueldo que se cobra a fin del mes anterior',
    );
  });

  it('cada regla de estimación tiene una línea que la explica', () => {
    const options = Array.from(root().querySelectorAll('mat-radio-button')).map((o) => ({
      name: o.querySelector('.rule-name')?.textContent,
      description: o.querySelector('.rule-description')?.textContent,
    }));

    expect(options.map((o) => o.name)).toEqual(['Último valor', 'Promedio de los últimos 3']);
    expect(options[0].description).toContain('las siguientes toman su monto real');
    expect(options[1].description).toContain('el promedio de las últimas 3 consolidadas');
  });

  it('avisa qué datos no se pueden cambiar después (S-12)', () => {
    expect(text()).toContain(
      'El tipo, la periodicidad, los períodos de inicio y de fin y las cuotas no se pueden cambiar después de guardar.',
    );
  });

  it('con lo obligatorio vacío marca cada campo y no llama a la API', () => {
    form().patchValue({ startPeriod: '' });
    submit();

    expect(text()).toContain('Ingresá el nombre del Concepto.');
    expect(text()).toContain('Elegí si es un ingreso o un gasto.');
    expect(text()).toContain('Elegí la cuenta por defecto.');
    expect(text()).toContain('Ingresá el día de vencimiento.');
    expect(text()).toContain('Ingresá el período de inicio.');
    expect(text()).toContain('Ingresá el monto vigente.');
    expect(api.createBudgetItem).not.toHaveBeenCalled();
  });

  it('un nombre de solo espacios no vale', () => {
    fillRequired();
    type('Nombre', '   ');
    submit();

    expect(text()).toContain('Ingresá el nombre del Concepto.');
    expect(api.createBudgetItem).not.toHaveBeenCalled();
  });

  it.each(['0', '32', '-1', '1.5'])('el día de vencimiento %s no vale', (day) => {
    fillRequired();
    type('Día de vencimiento', day);
    submit();

    expect(text()).toContain('Tiene que ser un día del 1 al 31.');
    expect(api.createBudgetItem).not.toHaveBeenCalled();
  });

  it.each(['1', '31'])('el día de vencimiento %s vale', (day) => {
    fillRequired();
    type('Día de vencimiento', day);
    submit();

    expect(request().dueDay).toBe(Number(day));
  });

  it.each(['mil', '10.5', '10,505', '-100', '-0,01'])('el monto «%s» no tiene el formato', (amount) => {
    fillRequired(amount);
    submit();

    expect(text()).toContain('Escribilo sin signo, con coma decimal y hasta 2 decimales');
    expect(api.createBudgetItem).not.toHaveBeenCalled();
  });

  it.each([
    ['85000', 85000],
    ['1.200.000,50', 1200000.5],
    ['1500,5', 1500.5],
    ['0', 0],
  ])('el monto «%s» se envía como el número %d', (amount, expected) => {
    fillRequired(amount);
    submit();

    expect(request().currentAmount).toBe(expected);
  });

  it('un período que no es año y mes no vale', () => {
    fillRequired();
    form().patchValue({ startPeriod: '2026-13', endPeriod: 'diciembre' });
    form().markAllAsTouched();
    submit();

    expect(text()).toContain('Escribilo como año y mes, por ejemplo 2026-10.');
    expect(text()).toContain('Escribilo como año y mes, por ejemplo 2027-12.');
    expect(api.createBudgetItem).not.toHaveBeenCalled();
  });

  const options = (label: string) => {
    field(label).querySelector<HTMLElement>('mat-select')!.click();
    fixture.detectChanges();
    return Array.from(document.querySelectorAll('mat-option')).map((o) => o.textContent?.trim());
  };

  it('cada cuenta se muestra con su moneda', () => {
    expect(options('Cuenta por defecto')).toEqual(['Banco Nación · $ (ARS)', 'Caja en dólares · US$ (USD)']);
  });

  it('la categoría es opcional: ofrece «Sin categoría» y las del usuario', () => {
    expect(options('Categoría')).toEqual(['Sin categoría', 'Impuestos', 'Servicios']);
  });

  it('envía un Concepto mensual sin categoría, sin fin y sin desfase', () => {
    fillRequired('85000,00');
    type('Nombre', '  Monotributo ');
    submit();

    expect(request()).toEqual({
      name: 'Monotributo',
      kind: 'EXPENSE',
      defaultAccountId: 12,
      categoryId: null,
      periodicity: 'MONTHLY',
      dueDay: 20,
      dueMonthOffset: 0,
      startPeriod: '2026-10',
      endPeriod: null,
      estimationRule: 'LAST_VALUE',
      currentAmount: 85000,
    });
  });

  it('envía el desfase −1, la categoría, el fin, la periodicidad y la regla elegidos', () => {
    fillRequired('1.200.000,50');
    form().patchValue({
      kind: 'INCOME',
      categoryId: 4,
      periodicity: 'SEMIANNUAL',
      dueInPreviousMonth: true,
      startPeriod: '2026-12',
      endPeriod: '2030-06',
      estimationRule: 'AVERAGE_LAST_3',
    });
    submit();

    expect(request()).toMatchObject({
      kind: 'INCOME',
      categoryId: 4,
      periodicity: 'SEMIANNUAL',
      dueMonthOffset: -1,
      startPeriod: '2026-12',
      endPeriod: '2030-06',
      estimationRule: 'AVERAGE_LAST_3',
      currentAmount: 1200000.5,
    });
  });

  it('al marcar la casilla del desfase cambia lo que se envía', () => {
    fillRequired();
    root().querySelector<HTMLInputElement>('mat-checkbox input')!.click();
    fixture.detectChanges();
    submit();

    expect(request().dueMonthOffset).toBe(-1);
  });

  it('al guardar emite el Concepto creado y deja el formulario listo para otro, sin errores', () => {
    fillRequired();
    form().patchValue({ dueInPreviousMonth: true, endPeriod: '2027-01', estimationRule: 'AVERAGE_LAST_3' });
    submit();

    expect(saved).toHaveBeenCalledOnce();
    expect(saved.mock.calls[0][0].generation.entryCount).toBe(25);
    expect(form().getRawValue()).toEqual({
      name: '',
      kind: null,
      defaultAccountId: null,
      categoryId: null,
      periodicity: 'MONTHLY',
      dueDay: null,
      dueInPreviousMonth: false,
      startPeriod: SUGGESTED,
      endPeriod: '',
      inInstallments: false,
      installmentsTotal: null,
      firstInstallmentNumber: 1,
      estimationRule: 'LAST_VALUE',
      currentAmount: '',
    });
    expect(root().querySelector('mat-error')).toBeNull();
    expect(alert()).toBe('');
  });

  it('con el período de inicio fuera de rango muestra el error del backend y conserva lo cargado', () => {
    api.createBudgetItem.mockReturnValue(
      problem(409, { code: 'PERIOD_NOT_AVAILABLE', detail: 'El período de inicio debe estar entre…' }),
    );
    fillRequired();
    form().patchValue({ startPeriod: '2020-01' });
    submit();

    expect(alert()).toBe(
      'Ese período no está disponible: tiene que estar entre tu primer período abierto y el horizonte.',
    );
    expect(saved).not.toHaveBeenCalled();
    expect(input('Nombre').value).toBe('Monotributo');
    expect(form().controls.startPeriod.value).toBe('2020-01');
  });

  it('con el fin anterior al inicio muestra el motivo que da el backend', () => {
    api.createBudgetItem.mockReturnValue(
      problem(400, {
        code: 'VALIDATION_ERROR',
        detail: 'El período de fin no puede ser anterior al período de inicio.',
        errors: [{ field: 'endPeriod', message: 'El período de fin no puede ser anterior al período de inicio.' }],
      }),
    );
    fillRequired();
    form().patchValue({ startPeriod: '2026-12', endPeriod: '2026-11' });
    submit();

    expect(alert()).toBe('El período de fin no puede ser anterior al período de inicio.');
    expect(saved).not.toHaveBeenCalled();
  });

  it('mientras guarda deshabilita el botón y después de un error lo habilita', () => {
    api.createBudgetItem.mockReturnValue(problem(0, {}));
    fillRequired();
    submit();

    expect(alert()).toContain('No se pudo conectar con el servidor');
    expect(root().querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(false);
  });

  describe('en cuotas (HU-11)', () => {
    const toggle = () => {
      const box = Array.from(root().querySelectorAll('mat-checkbox')).find((c) =>
        c.textContent?.includes('Es en cuotas'),
      )!;
      box.querySelector<HTMLInputElement>('input')!.click();
      fixture.detectChanges();
    };
    const hasField = (label: string) =>
      Array.from(root().querySelectorAll('mat-form-field')).some((f) =>
        f.querySelector('label')?.textContent?.includes(label),
      );

    it('arranca sin cuotas: no muestra el total ni la primera cuota y sí el fin', () => {
      expect(root().querySelector('mat-checkbox')).toBeTruthy();
      expect(text()).toContain('Es en cuotas');
      expect(hasField('Total de cuotas')).toBe(false);
      expect(hasField('Primera cuota')).toBe(false);
      expect(hasField('Período de fin')).toBe(true);
      expect(root().querySelector('.installments-help')).toBeNull();
    });

    it('al marcar «Es en cuotas» muestra el total y la primera cuota (1 por defecto), oculta el fin y explica por qué', () => {
      toggle();

      expect(hasField('Total de cuotas')).toBe(true);
      expect(hasField('Primera cuota')).toBe(true);
      expect(input('Primera cuota').value).toBe('1');
      expect(hasField('Período de fin')).toBe(false);
      expect(root().querySelector('.installments-help')?.textContent).toContain('se calcula');
      expect(root().querySelector('.installments-help')?.textContent).toContain(
        'La primera cuota sirve para cargar un plan que ya empezó',
      );
    });

    it('al desmarcarla vuelve el fin y se van las cuotas', () => {
      toggle();
      toggle();

      expect(hasField('Total de cuotas')).toBe(false);
      expect(hasField('Período de fin')).toBe(true);
      expect(form().controls.installmentsTotal.disabled).toBe(true);
      expect(form().controls.endPeriod.enabled).toBe(true);
    });

    it('un fin ya escrito se descarta al pasar a cuotas y no se envía', () => {
      fillRequired();
      form().patchValue({ endPeriod: '2027-12' });
      toggle();
      type('Total de cuotas', '12');
      submit();

      expect(form().controls.endPeriod.value).toBe('');
      expect(request().endPeriod).toBeNull();
    });

    it('envía el total y la primera cuota elegidos', () => {
      fillRequired();
      toggle();
      type('Total de cuotas', '12');
      type('Primera cuota', '4');
      submit();

      expect(request()).toMatchObject({ installmentsTotal: 12, firstInstallmentNumber: 4, endPeriod: null });
    });

    it('con la primera cuota por defecto envía 1', () => {
      fillRequired();
      toggle();
      type('Total de cuotas', '12');
      submit();

      expect(request()).toMatchObject({ installmentsTotal: 12, firstInstallmentNumber: 1 });
    });

    it('con la primera cuota vacía no la envía: el backend la completa con 1', () => {
      fillRequired();
      toggle();
      type('Total de cuotas', '12');
      type('Primera cuota', '');
      submit();

      expect(request().installmentsTotal).toBe(12);
      expect(request().firstInstallmentNumber).toBeNull();
    });

    it('sin cuotas el pedido no lleva datos de cuotas', () => {
      fillRequired();
      submit();

      expect(request()).not.toHaveProperty('installmentsTotal');
      expect(request()).not.toHaveProperty('firstInstallmentNumber');
    });

    it('un plan marcado de ida y vuelta se envía como un Concepto común', () => {
      fillRequired();
      toggle();
      type('Total de cuotas', '12');
      toggle();
      form().patchValue({ endPeriod: '2027-03' });
      submit();

      expect(request()).not.toHaveProperty('installmentsTotal');
      expect(request().endPeriod).toBe('2027-03');
    });

    it('con cuotas, el total es obligatorio', () => {
      fillRequired();
      toggle();
      submit();

      expect(text()).toContain('Ingresá el total de cuotas.');
      expect(api.createBudgetItem).not.toHaveBeenCalled();
    });

    it.each(['0', '-3', '1.5'])('el total «%s» no vale', (total) => {
      fillRequired();
      toggle();
      type('Total de cuotas', total);
      submit();

      expect(text()).toContain('Tiene que ser un número entero, de 1 en adelante.');
      expect(api.createBudgetItem).not.toHaveBeenCalled();
    });

    it.each(['0', '-1', '2.5'])('la primera cuota «%s» no vale', (first) => {
      fillRequired();
      toggle();
      type('Total de cuotas', '12');
      type('Primera cuota', first);
      submit();

      expect(text()).toContain('Tiene que ser un número entero, de 1 en adelante.');
      expect(api.createBudgetItem).not.toHaveBeenCalled();
    });

    it('la primera cuota mayor que el total no se valida acá: la decide el backend y se muestra su error', () => {
      api.createBudgetItem.mockReturnValue(
        problem(400, {
          code: 'VALIDATION_ERROR',
          detail: 'La primera cuota no puede ser mayor que el total de cuotas.',
          errors: [
            { field: 'firstInstallmentNumber', message: 'La primera cuota no puede ser mayor que el total de cuotas.' },
          ],
        }),
      );
      fillRequired();
      toggle();
      type('Total de cuotas', '12');
      type('Primera cuota', '13');
      submit();

      expect(api.createBudgetItem).toHaveBeenCalledOnce();
      expect(alert()).toBe('La primera cuota no puede ser mayor que el total de cuotas.');
      expect(saved).not.toHaveBeenCalled();
      // Conserva lo cargado, incluido que es en cuotas.
      expect(form().controls.inInstallments.value).toBe(true);
      expect(input('Primera cuota').value).toBe('13');
    });

    it('al guardar deja el formulario listo para otro Concepto, sin cuotas', () => {
      fillRequired();
      toggle();
      type('Total de cuotas', '12');
      type('Primera cuota', '4');
      submit();

      expect(saved).toHaveBeenCalledOnce();
      expect(form().getRawValue()).toMatchObject({
        inInstallments: false,
        installmentsTotal: null,
        firstInstallmentNumber: 1,
        endPeriod: '',
      });
      expect(hasField('Total de cuotas')).toBe(false);
      expect(hasField('Período de fin')).toBe(true);
      expect(form().controls.endPeriod.enabled).toBe(true);
      expect(root().querySelector('mat-error')).toBeNull();
    });
  });
});
