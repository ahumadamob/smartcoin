import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import {
  AccountResponse,
  BudgetItemResponse,
  CategorasService,
  ConceptosService,
  CuentasService,
} from '../../api';
import { provideLocale } from '../../core/locale';
import { BudgetItemForm } from './budget-item-form';
import { BudgetItemNew } from './budget-item-new';

const FREE = { editable: true, reason: null };
const ACCOUNT: AccountResponse = {
  id: 12,
  name: 'Banco Nación',
  type: 'BANK',
  currency: 'ARS',
  openingDate: '2026-08-01',
  initialBalance: 0,
  currentBalance: 0,
  editability: { currency: FREE, initialBalance: FREE, openingDate: FREE },
};

const item = (generation: BudgetItemResponse['generation']): BudgetItemResponse => ({
  id: 31,
  name: 'Sueldo A',
  kind: 'INCOME',
  defaultAccountId: 12,
  currency: 'ARS',
  periodicity: 'MONTHLY',
  dueDay: 25,
  dueMonthOffset: -1,
  startPeriod: generation.firstPeriod,
  estimationRule: 'LAST_VALUE',
  currentAmount: 1200000,
  generation,
});

describe('BudgetItemNew', () => {
  let fixture: ComponentFixture<BudgetItemNew>;

  async function setup(accounts: AccountResponse[], listAccounts = of({ accounts, subtotals: [] })) {
    await TestBed.configureTestingModule({
      imports: [BudgetItemNew],
      providers: [
        provideRouter([]),
        provideLocale(),
        { provide: CuentasService, useValue: { listAccounts: vi.fn().mockReturnValue(listAccounts) } },
        {
          provide: CategorasService,
          useValue: { listCategories: vi.fn().mockReturnValue(of([{ id: 3, name: 'Impuestos' }])) },
        },
        { provide: ConceptosService, useValue: { createBudgetItem: vi.fn() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(BudgetItemNew);
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  const text = () => (root().textContent ?? '').replace(/\s+/g, ' ');
  // Las etiquetas se reemplazan por un espacio: los enlaces vecinos se leen separados.
  const status = () =>
    (root().querySelector('[role="status"]')?.innerHTML ?? '').replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
  /** Lo que hace el formulario al guardar: emite el Concepto creado. */
  const save = (created: BudgetItemResponse) => {
    fixture.componentInstance['created'].set(created);
    fixture.detectChanges();
  };

  it('muestra el título y el formulario con las cuentas y categorías del usuario', async () => {
    await setup([ACCOUNT]);

    expect(root().querySelector('h1')?.textContent).toBe('Nuevo Concepto');
    expect(root().querySelector('app-budget-item-form')).toBeTruthy();
    expect(root().querySelector('[role="status"]')).toBeNull();
  });

  it('sugiere como período de inicio el mes actual', async () => {
    vi.useFakeTimers({ now: new Date(2026, 9, 7, 12) });
    try {
      await setup([ACCOUNT]);

      expect(fixture.componentInstance['suggestedStartPeriod']).toBe('2026-10');
    } finally {
      vi.useRealTimers();
    }
  });

  it('sin cuentas no muestra el formulario y manda a cargar una', async () => {
    await setup([]);

    expect(root().querySelector('app-budget-item-form')).toBeNull();
    expect(text()).toContain('Para crear un Concepto primero necesitás una cuenta.');
    expect(root().querySelector('a')?.getAttribute('href')).toBe('/cuentas');
  });

  it('después de guardar muestra cuántas partidas se generaron, entre qué períodos y el primer vencimiento', async () => {
    await setup([ACCOUNT]);
    save(item({ entryCount: 25, firstPeriod: '2026-11', lastPeriod: '2028-11', firstDueDate: '2026-10-25' }));

    expect(status()).toBe(
      'Concepto «Sueldo A» creado. Se generaron 25 partidas, de noviembre 2026 a noviembre 2028. Primer vencimiento: 25/10/2026. Editar este Concepto Volver a la lista',
    );
    expect(root().querySelector('app-budget-item-form')).toBeTruthy();
  });

  it('trae el resumen a la vista, porque el botón de guardar queda al final del formulario', async () => {
    const scrollIntoView = vi.fn();
    HTMLElement.prototype.scrollIntoView = scrollIntoView;
    try {
      await setup([ACCOUNT]);
      save(item({ entryCount: 25, firstPeriod: '2026-11', lastPeriod: '2028-11', firstDueDate: '2026-10-25' }));
      await fixture.whenStable();

      expect(scrollIntoView).toHaveBeenCalledOnce();
      expect(scrollIntoView.mock.contexts[0]).toBe(root().querySelector('[role="status"]'));

      save(item({ entryCount: 1, firstPeriod: '2027-03', lastPeriod: '2027-03', firstDueDate: '2027-02-25' }));
      await fixture.whenStable();

      expect(scrollIntoView).toHaveBeenCalledTimes(2);
    } finally {
      delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView;
    }
  });

  it('el resumen ofrece editar el Concepto recién creado y volver a la lista', async () => {
    await setup([ACCOUNT]);
    save(item({ entryCount: 25, firstPeriod: '2026-11', lastPeriod: '2028-11', firstDueDate: '2026-10-25' }));

    const link = root().querySelector<HTMLAnchorElement>('[role="status"] a')!;
    expect(link.textContent?.trim()).toBe('Editar este Concepto');
    expect(link.getAttribute('href')).toBe('/conceptos/31/editar');
    const back = root().querySelector<HTMLAnchorElement>('[role="status"] a:last-child')!;
    expect(back.textContent?.trim()).toBe('Volver a la lista');
    expect(back.getAttribute('href')).toBe('/conceptos');
  });

  it('con una sola partida lo dice en singular', async () => {
    await setup([ACCOUNT]);
    save(item({ entryCount: 1, firstPeriod: '2027-03', lastPeriod: '2027-03', firstDueDate: '2027-02-25' }));

    expect(status()).toBe(
      'Concepto «Sueldo A» creado. Se generó 1 partida, en marzo 2027. Primer vencimiento: 25/02/2027. Editar este Concepto Volver a la lista',
    );
  });

  it('el resumen aparece cuando el formulario avisa que guardó', async () => {
    await setup([ACCOUNT]);
    const created = item({ entryCount: 3, firstPeriod: '2026-10', lastPeriod: '2026-12', firstDueDate: '2026-09-25' });
    const form = fixture.debugElement.children[0].query((e) => e.componentInstance instanceof BudgetItemForm);
    (form.componentInstance as BudgetItemForm).saved.emit(created);
    fixture.detectChanges();

    expect(status()).toContain('Se generaron 3 partidas, de octubre 2026 a diciembre 2026.');
  });

  it('si no se pueden cargar las cuentas muestra el error y no el formulario', async () => {
    await setup(
      [],
      throwError(() => new HttpErrorResponse({ status: 0 })),
    );

    expect(root().querySelector('[role="alert"]')?.textContent).toContain('No se pudo conectar con el servidor');
    expect(root().querySelector('app-budget-item-form')).toBeNull();
  });

  describe('Concepto en cuotas (HU-11)', () => {
    const installments = (
      total: number,
      first: number,
      lastInstallment: number,
      generation: Partial<BudgetItemResponse['generation']> = {},
    ): BudgetItemResponse => ({
      ...item({
        entryCount: lastInstallment - first + 1,
        firstPeriod: '2026-10',
        lastPeriod: '2027-06',
        firstDueDate: '2026-10-10',
        firstInstallment: first,
        lastInstallment,
        ...generation,
      }),
      name: 'Heladera',
      installmentsTotal: total,
      firstInstallmentNumber: first,
      endPeriod: '2027-06',
    });

    it('indica las cuotas generadas cuando el plan se generó completo', async () => {
      await setup([ACCOUNT]);
      save(installments(12, 4, 12));

      expect(status()).toBe(
        'Concepto «Heladera» creado. Se generaron 9 partidas, de octubre 2026 a junio 2027. ' +
          'Primer vencimiento: 10/10/2026. Son las cuotas 4 a 12 de 12. Editar este Concepto Volver a la lista',
      );
    });

    it('con una sola cuota lo dice en singular', async () => {
      await setup([ACCOUNT]);
      save(
        installments(12, 12, 12, { entryCount: 1, lastPeriod: '2026-10', firstInstallment: 12, lastInstallment: 12 }),
      );

      expect(status()).toContain('Se generó 1 partida, en octubre 2026.');
      expect(status()).toContain('Es la cuota 12 de 12.');
      expect(status()).not.toContain('El plan termina');
    });

    it('si el plan termina después del horizonte, avisa hasta qué cuota se generó y cuándo termina', async () => {
      await setup([ACCOUNT]);
      save({
        ...installments(60, 1, 25, { entryCount: 25, lastPeriod: '2028-10' }),
        endPeriod: '2031-09',
      });

      expect(status()).toContain('Son las cuotas 1 a 25 de 60.');
      expect(status()).toContain(
        'El plan termina en septiembre 2031; las demás cuotas se generan a medida que avance el horizonte.',
      );
    });

    it('un Concepto sin cuotas no muestra nada de cuotas', async () => {
      await setup([ACCOUNT]);
      save(item({ entryCount: 25, firstPeriod: '2026-11', lastPeriod: '2028-11', firstDueDate: '2026-10-25' }));

      expect(status()).not.toContain('cuota');
      expect(root().querySelector('.installments')).toBeNull();
    });
  });
});
