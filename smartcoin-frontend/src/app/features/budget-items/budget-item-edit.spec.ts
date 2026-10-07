import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError } from 'rxjs';
import {
  AccountResponse,
  BudgetItemDetail,
  CategorasService,
  ConceptosService,
  CuentasService,
} from '../../api';
import { provideLocale } from '../../core/locale';
import { BudgetItemEdit } from './budget-item-edit';

const FREE = { editable: true, reason: null };
const locked = { editable: false, reason: 'No se puede cambiar.' };
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

const ITEM: BudgetItemDetail = {
  id: 31,
  name: 'Monotributo',
  kind: 'EXPENSE',
  defaultAccountId: 12,
  currency: 'ARS',
  periodicity: 'MONTHLY',
  dueDay: 20,
  dueMonthOffset: 0,
  startPeriod: '2026-10',
  estimationRule: 'LAST_VALUE',
  currentAmount: 85000,
  editability: { kind: locked, periodicity: locked, startPeriod: locked, endPeriod: locked, installments: locked },
  entryCounts: { pendingNotManual: 23, pendingManual: 2 },
};

describe('BudgetItemEdit', () => {
  let harness: RouterTestingHarness;
  let items: { getBudgetItem: ReturnType<typeof vi.fn>; updateBudgetItem: ReturnType<typeof vi.fn> };
  let snackBar: { open: ReturnType<typeof vi.fn> };

  async function open(url: string, getBudgetItem = of(ITEM)) {
    items = { getBudgetItem: vi.fn().mockReturnValue(getBudgetItem), updateBudgetItem: vi.fn() };
    snackBar = { open: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'conceptos/:id/editar', component: BudgetItemEdit }]),
        provideLocale(),
        { provide: ConceptosService, useValue: items },
        {
          provide: CuentasService,
          useValue: { listAccounts: vi.fn().mockReturnValue(of({ accounts: [ACCOUNT], subtotals: [] })) },
        },
        {
          provide: CategorasService,
          useValue: { listCategories: vi.fn().mockReturnValue(of([{ id: 3, name: 'Impuestos' }])) },
        },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    harness = await RouterTestingHarness.create(url);
  }

  const root = () => harness.routeNativeElement as HTMLElement;
  const text = () => (root().textContent ?? '').replace(/\s+/g, ' ');
  const alert = () => root().querySelector('[role="alert"]')?.textContent ?? '';

  it('carga el Concepto de la ruta y lo muestra en el formulario de edición', async () => {
    await open('/conceptos/31/editar');

    expect(items.getBudgetItem).toHaveBeenCalledWith(31);
    expect(root().querySelector('h1')?.textContent).toBe('Editar Concepto');
    expect(text()).toContain('«Monotributo»');
    expect(root().querySelector('app-budget-item-form')).toBeTruthy();
    expect(root().querySelector<HTMLInputElement>('input[formcontrolname="name"]')!.value).toBe('Monotributo');
    expect(root().querySelector('button[type="submit"]')?.textContent?.trim()).toBe('Guardar cambios');
  });

  it('explica cómo dar de baja el Concepto: eliminar su partida desde el mes elegido', async () => {
    await open('/conceptos/31/editar');

    const removal = root().querySelector('.removal')!;
    expect(removal.querySelector('h2')?.textContent).toBe('Dar de baja este Concepto');
    expect(removal.textContent).toContain('eliminá su partida desde el mes elegido');
    expect(removal.textContent).toContain('Este mes y los siguientes');
    expect(removal.textContent).toContain('todavía no está disponible');
  });

  it('aclara que las consolidadas y las de períodos cerrados nunca cambian', async () => {
    await open('/conceptos/31/editar');

    expect(text()).toContain('las partidas consolidadas y las de períodos cerrados nunca cambian');
  });

  it('tiene un enlace para volver a Conceptos', async () => {
    await open('/conceptos/31/editar');

    expect(root().querySelector('a')?.getAttribute('href')).toBe('/conceptos');
  });

  it('un Concepto que no existe o es de otro usuario muestra el error y no el formulario', async () => {
    await open(
      '/conceptos/99/editar',
      throwError(
        () =>
          new HttpErrorResponse({ status: 404, error: { code: 'NOT_FOUND', detail: 'El Concepto no existe.' } }),
      ),
    );

    expect(alert()).toBe('El Concepto no existe.');
    expect(root().querySelector('app-budget-item-form')).toBeNull();
    expect(root().querySelector('.removal')).toBeNull();
    expect(root().querySelector('a')?.getAttribute('href')).toBe('/conceptos');
  });

  it.each(['abc', '0', '-3', '1.5'])('un id inválido («%s») ni llama a la API', async (id) => {
    await open(`/conceptos/${id}/editar`);

    expect(items.getBudgetItem).not.toHaveBeenCalled();
    expect(alert()).toBe('El Concepto no existe.');
    expect(root().querySelector('app-budget-item-form')).toBeNull();
  });

  it('al guardar avisa y pasa al formulario los datos y conteos nuevos', async () => {
    await open('/conceptos/31/editar');
    const edit = harness.routeDebugElement!.componentInstance as BudgetItemEdit;
    const saved: BudgetItemDetail = {
      ...ITEM,
      name: 'Monotributo B',
      currentAmount: 90000,
      entryCounts: { pendingNotManual: 24, pendingManual: 2 },
    };

    edit['onUpdated'](saved);
    harness.detectChanges();

    expect(snackBar.open).toHaveBeenCalledWith('Concepto «Monotributo B» guardado.', undefined, { duration: 4000 });
    expect(text()).toContain('«Monotributo B»');
    expect(root().querySelector<HTMLInputElement>('input[formcontrolname="name"]')!.value).toBe('Monotributo B');
    expect(root().querySelector<HTMLInputElement>('input[formcontrolname="currentAmount"]')!.value).toBe('90000,00');
  });
});
