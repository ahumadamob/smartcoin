import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { BudgetItemListItem, CategorasService, ConceptosService } from '../../api';
import { provideLocale } from '../../core/locale';
import { BudgetItems } from './budget-items';
import { dueText, statusText } from './budget-item-labels';

const base: BudgetItemListItem = {
  id: 1,
  name: 'Monotributo',
  kind: 'EXPENSE',
  defaultAccountId: 12,
  defaultAccountName: 'Banco Nación',
  currency: 'ARS',
  categoryId: 3,
  categoryName: 'Impuestos',
  periodicity: 'MONTHLY',
  dueDay: 20,
  dueMonthOffset: 0,
  estimationRule: 'LAST_VALUE',
  currentAmount: 85000,
  startPeriod: '2026-01',
  status: 'ACTIVE',
};

const salary: BudgetItemListItem = {
  ...base,
  id: 2,
  name: 'Sueldo',
  kind: 'INCOME',
  categoryId: null,
  categoryName: null,
  dueDay: 25,
  dueMonthOffset: -1,
  currentAmount: 1200000.5,
  periodicity: 'MONTHLY',
};

const fridge: BudgetItemListItem = {
  ...base,
  id: 3,
  name: 'Heladera',
  categoryId: 4,
  categoryName: 'Hogar',
  currency: 'USD',
  currentAmount: 150,
  startPeriod: '2026-10',
  endPeriod: '2027-06',
  installmentsTotal: 12,
  firstInstallmentNumber: 4,
  currentInstallment: 4,
  installmentsRemaining: 8,
};

const old: BudgetItemListItem = {
  ...base,
  id: 4,
  name: 'Gimnasio',
  startPeriod: '2025-01',
  endPeriod: '2026-06',
  status: 'FINISHED',
};

describe('BudgetItems', () => {
  let fixture: ComponentFixture<BudgetItems>;
  let itemsApi: { listBudgetItems: ReturnType<typeof vi.fn> };

  async function setup(response: unknown = of([base, salary, fridge, old])) {
    itemsApi = { listBudgetItems: vi.fn().mockReturnValue(response) };
    await TestBed.configureTestingModule({
      imports: [BudgetItems],
      providers: [
        provideRouter([]),
        provideLocale(),
        { provide: ConceptosService, useValue: itemsApi },
        {
          provide: CategorasService,
          useValue: {
            listCategories: () =>
              of([
                { id: 3, name: 'Impuestos' },
                { id: 4, name: 'Hogar' },
              ]),
          },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(BudgetItems);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  // Las etiquetas se reemplazan por un espacio: los bloques (`display: block`) se leen separados.
  const text = (el: Element | null | undefined) => (el?.innerHTML ?? '').replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
  const rows = () => Array.from(root().querySelectorAll('tbody tr'));
  const cells = (row: Element) => Array.from(row.querySelectorAll('td')).map((td) => text(td));
  const component = () => fixture.componentInstance as unknown as {
    setKind(kind: string): void;
    setCategory(category: string | number): void;
    clearFilters(): void;
  };
  async function refresh() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('pide la lista sin filtros y muestra una fila por Concepto con sus columnas', async () => {
    await setup();

    expect(itemsApi.listBudgetItems).toHaveBeenCalledWith(undefined, undefined, undefined);
    expect(text(root().querySelector('thead'))).toBe(
      'Nombre Tipo Cuenta Categoría Periodicidad Vencimiento Estimación Monto vigente Estado Acciones',
    );
    expect(rows()).toHaveLength(4);
    expect(cells(rows()[0])).toEqual([
      'Monotributo',
      'Gasto',
      'Banco Nación $',
      'Impuestos',
      'Mensual',
      'día 20',
      'Último valor',
      '$ 85.000,00',
      'Activo',
      'Editar',
    ]);
  });

  it('muestra el vencimiento con desfase, el monto en dólares y "—" sin categoría', async () => {
    await setup();

    expect(cells(rows()[1])[3]).toBe('—');
    expect(cells(rows()[1])[5]).toBe('día 25, el mes anterior');
    expect(cells(rows()[1])[7]).toBe('$ 1.200.000,50');
    expect(cells(rows()[2])[7]).toBe('US$ 150,00');
  });

  it('muestra el estado: cuotas, finalizado con su fin y por comenzar con su inicio', async () => {
    await setup(of([fridge, old, { ...base, id: 5, name: 'Futuro', status: 'SCHEDULED', startPeriod: '2026-12' }]));

    const status = (i: number) => text(rows()[i].querySelector('[data-testid="status"]'));
    expect(status(0)).toBe('Cuota 4 de 12, quedan 8 termina en junio 2027');
    expect(status(1)).toBe('Finalizado terminó en junio 2026');
    expect(status(2)).toBe('Por comenzar en diciembre 2026');
  });

  it('cada fila tiene «Editar» que lleva a la pantalla de edición, con el nombre como etiqueta', async () => {
    await setup();

    const link = rows()[2].querySelector('a')!;
    expect(link.getAttribute('href')).toBe('/conceptos/3/editar');
    expect(link.getAttribute('aria-label')).toBe('Editar Heladera');
  });

  it('«Nuevo Concepto» lleva al alta', async () => {
    await setup();

    const link = Array.from(root().querySelectorAll('a')).find((a) => text(a) === 'Nuevo Concepto')!;
    expect(link.getAttribute('href')).toBe('/conceptos/nuevo');
  });

  it('el filtro de tipo y el de categoría se piden al backend, solos y combinados', async () => {
    await setup();

    component().setKind('INCOME');
    await refresh();
    expect(itemsApi.listBudgetItems).toHaveBeenLastCalledWith('INCOME', undefined, undefined);

    component().setCategory(3);
    await refresh();
    expect(itemsApi.listBudgetItems).toHaveBeenLastCalledWith('INCOME', 3, undefined);

    component().setCategory('NONE');
    await refresh();
    expect(itemsApi.listBudgetItems).toHaveBeenLastCalledWith('INCOME', undefined, true);
  });

  it('los filtros tienen etiqueta y las opciones incluyen «Sin categoría» y las categorías del usuario', async () => {
    await setup();

    const labels = Array.from(root().querySelectorAll('mat-form-field mat-label')).map((l) => text(l));
    expect(labels).toEqual(['Tipo', 'Categoría']);
    expect(root().querySelectorAll('mat-select')).toHaveLength(2);
  });

  it('sin Conceptos explica cómo empezar', async () => {
    await setup(of([]));

    expect(root().querySelector('table')).toBeNull();
    expect(text(root().querySelector('.empty'))).toBe('Todavía no cargaste ningún Concepto. Empezá con «Nuevo Concepto».');
    expect(root().textContent).not.toContain('Quitar filtros');
  });

  it('con un filtro que no devuelve nada dice otra cosa y ofrece quitar los filtros', async () => {
    await setup();
    itemsApi.listBudgetItems.mockReturnValue(of([]));

    component().setKind('INCOME');
    await refresh();

    expect(text(root().querySelector('.empty'))).toBe(
      'Ningún Concepto coincide con los filtros. Probá con otros o quitalos.',
    );
    const clear = Array.from(root().querySelectorAll('button')).find((b) => text(b) === 'Quitar filtros')!;
    itemsApi.listBudgetItems.mockReturnValue(of([base]));
    clear.click();
    await refresh();

    expect(itemsApi.listBudgetItems).toHaveBeenLastCalledWith(undefined, undefined, undefined);
    expect(rows()).toHaveLength(1);
  });

  it('muestra el error del backend, por ejemplo una categoría que ya no existe', async () => {
    await setup(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 404,
            error: { code: 'NOT_FOUND', detail: 'La categoría no existe.' },
          }),
      ),
    );

    expect(root().querySelector('[role="alert"]')?.textContent).toContain('La categoría no existe.');
    expect(root().querySelector('table')).toBeNull();
    expect(root().querySelector('.empty')).toBeNull();
  });
});

describe('textos de la lista de Conceptos', () => {
  it('dueText', () => {
    expect(dueText(20, 0)).toBe('día 20');
    expect(dueText(25, -1)).toBe('día 25, el mes anterior');
  });

  it.each([
    [{ status: 'ACTIVE' as const }, 'Activo'],
    [{ status: 'FINISHED' as const }, 'Finalizado'],
    [{ status: 'SCHEDULED' as const }, 'Por comenzar'],
    [{ status: 'ACTIVE' as const, currentInstallment: 4, installmentsRemaining: 8, installmentsTotal: 12 }, 'Cuota 4 de 12, quedan 8'],
    [{ status: 'ACTIVE' as const, currentInstallment: 11, installmentsRemaining: 1, installmentsTotal: 12 }, 'Cuota 11 de 12, queda 1'],
    [{ status: 'ACTIVE' as const, currentInstallment: 12, installmentsRemaining: 0, installmentsTotal: 12 }, 'Cuota 12 de 12, última'],
  ])('statusText %j', (source, expected) => {
    expect(statusText(source)).toBe(expected);
  });
});
