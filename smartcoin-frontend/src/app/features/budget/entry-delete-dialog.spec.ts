import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Observable, of, throwError } from 'rxjs';
import { DeletionPlan, EntryDeletionPreview, PartidasService, PeriodEntry } from '../../api';
import { provideLocale } from '../../core/locale';
import { EntryDeleteDialog, EntryDeleteDialogData, EntryDeleteDialogResult } from './entry-delete-dialog';

const entry = (overrides: Partial<PeriodEntry>): PeriodEntry => ({
  id: 512,
  budgetItemId: 31,
  origin: 'RECURRING',
  kind: 'EXPENSE',
  name: 'Luz',
  categoryId: null,
  categoryName: null,
  accountId: 12,
  accountName: 'Banco Nación',
  currency: 'ARS',
  dueDate: '2026-12-18',
  installmentNumber: null,
  installmentsTotal: null,
  budgetedAmount: 45000,
  actualAmount: 0,
  pendingAmount: 45000,
  forecastAmount: 45000,
  status: 'ESTIMATED',
  manual: false,
  overdue: false,
  ...overrides,
});

const plan = (overrides: Partial<DeletionPlan>): DeletionPlan => ({
  allowed: true,
  entryCount: 1,
  fromPeriod: '2026-12',
  toPeriod: '2026-12',
  itemOutcome: 'KEEPS_ITEM',
  newEndPeriod: null,
  blockers: [],
  ...overrides,
});

/** «Luz» recurrente: «Solo este mes» elimina 1; «Este mes y los siguientes» elimina 3 y el Concepto termina en 2026-11. */
const RECURRING: EntryDeletionPreview = {
  entryId: 512,
  recurring: true,
  origin: 'RECURRING',
  period: '2026-12',
  onlyThis: plan({}),
  thisAndFuture: plan({
    entryCount: 3,
    toPeriod: '2027-02',
    itemOutcome: 'ENDS_ITEM',
    newEndPeriod: '2026-11',
  }),
};

const ONE_OFF: EntryDeletionPreview = {
  entryId: 900,
  recurring: false,
  origin: 'ONE_OFF',
  period: '2026-11',
  removal: plan({ fromPeriod: '2026-11', toPeriod: '2026-11', itemOutcome: null }),
};

const problem = (status: number, body: object) => throwError(() => new HttpErrorResponse({ status, error: body }));

describe('EntryDeleteDialog (HU-18)', () => {
  let fixture: ComponentFixture<EntryDeleteDialog>;
  let api: { getEntryDeletionPreview: ReturnType<typeof vi.fn>; deleteEntry: ReturnType<typeof vi.fn> };
  let ref: { close: ReturnType<typeof vi.fn<(result?: EntryDeleteDialogResult) => void>> };

  async function open(
    data: EntryDeleteDialogData,
    previews: Observable<EntryDeletionPreview>[],
    deleteResponse: Observable<unknown> = of(undefined),
  ) {
    let call = 0;
    api = {
      getEntryDeletionPreview: vi.fn(() => previews[Math.min(call++, previews.length - 1)]),
      deleteEntry: vi.fn(() => deleteResponse),
    };
    ref = { close: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [EntryDeleteDialog],
      providers: [
        provideLocale(),
        { provide: PartidasService, useValue: api },
        { provide: MatDialogRef, useValue: ref },
        { provide: MAT_DIALOG_DATA, useValue: data },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(EntryDeleteDialog);
    await refresh();
  }

  async function refresh() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  const text = (el: Element | null | undefined = root()) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const byId = (id: string) => root().querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const confirm = () => byId('confirm-delete') as HTMLButtonElement;
  const radio = (id: 'scope-only-this' | 'scope-this-and-future') =>
    root().querySelector<HTMLInputElement>(`[data-testid="${id}"] input`)!;
  async function choose(id: 'scope-only-this' | 'scope-this-and-future') {
    radio(id).click();
    await refresh();
  }
  const blockers = (container: Element) =>
    Array.from(container.querySelectorAll('[data-testid="blocker"]')).map((li) => text(li));

  describe('partida sin Concepto', () => {
    const data = (overrides: Partial<PeriodEntry> = {}): EntryDeleteDialogData => ({
      entry: entry({ id: 900, budgetItemId: null, origin: 'ONE_OFF', name: 'Service del auto', budgetedAmount: 85000, ...overrides }),
      period: '2026-11',
    });

    it('pide la vista previa de la partida y muestra una confirmación simple con su nombre y su monto', async () => {
      await open(data(), [of(ONE_OFF)]);

      expect(api.getEntryDeletionPreview).toHaveBeenCalledWith(900);
      expect(text(root().querySelector('h2'))).toBe('Eliminar partida');
      expect(text(byId('delete-summary'))).toBe(
        'Vas a eliminar «Service del auto» de noviembre 2026 por $ 85.000,00. No se puede deshacer.',
      );
      expect(root().querySelector('mat-radio-group')).toBeNull();
      expect(confirm().textContent?.trim()).toBe('Eliminar partida');
      expect(confirm().disabled).toBe(false);
    });

    it('al confirmar elimina sin alcance y se cierra con el aviso', async () => {
      await open(data(), [of(ONE_OFF)]);

      confirm().click();
      await refresh();

      expect(api.deleteEntry).toHaveBeenCalledWith(900, undefined);
      expect(ref.close).toHaveBeenCalledWith({ deleted: true, message: 'Partida «Service del auto» eliminada.' });
    });

    it('«Cancelar» cierra sin eliminar', async () => {
      await open(data(), [of(ONE_OFF)]);

      byId('cancel-delete')!.click();

      expect(api.deleteEntry).not.toHaveBeenCalled();
      expect(ref.close).toHaveBeenCalledWith(undefined);
    });

    it('un saldo postergado explica qué significa eliminarlo', async () => {
      await open(data({ origin: 'CARRIED_OVER', name: 'Saldo pendiente: Luz' }), [of({ ...ONE_OFF, origin: 'CARRIED_OVER' })]);

      expect(text(byId('origin-note'))).toContain('saldo que quedó pendiente');
    });

    it('con movimientos no se puede: lista el motivo y el botón queda deshabilitado', async () => {
      const blocked: EntryDeletionPreview = {
        ...ONE_OFF,
        removal: plan({
          allowed: false,
          itemOutcome: null,
          blockers: [{ entryId: 900, period: '2026-11', reason: 'HAS_MOVEMENTS' }],
        }),
      };
      await open(data({ status: 'PARTIAL' }), [of(blocked)]);

      expect(blockers(root())).toEqual(['noviembre 2026: tiene movimientos']);
      expect(confirm().disabled).toBe(true);
    });
  });

  describe('partida de un Concepto', () => {
    const data: EntryDeleteDialogData = { entry: entry({}), period: '2026-12' };

    it('el título dice la partida y el mes, y ninguna opción viene elegida', async () => {
      await open(data, [of(RECURRING)]);

      expect(text(root().querySelector('h2'))).toBe('Eliminar «Luz» de diciembre 2026');
      expect(radio('scope-only-this').checked).toBe(false);
      expect(radio('scope-this-and-future').checked).toBe(false);
      expect(confirm().disabled).toBe(true);
      expect(text(byId('choose-hint'))).toBe('Elegí una opción para poder eliminar.');
    });

    it('explica en palabras qué hace cada opción, con las cifras del backend', async () => {
      await open(data, [of(RECURRING)]);

      expect(text(byId('only-this-text'))).toBe(
        'Se elimina solo la partida de diciembre 2026. El Concepto «Luz» sigue igual y esta partida no vuelve a aparecer.',
      );
      expect(text(byId('this-and-future-text'))).toBe(
        'Se eliminan las 3 partidas de «Luz», de diciembre 2026 a febrero 2027. El Concepto «Luz» termina en noviembre 2026 y no genera más partidas; las anteriores no cambian.',
      );
    });

    it('si desde ese mes no queda ninguna partida, avisa que el Concepto desaparece', async () => {
      const removesItem: EntryDeletionPreview = {
        ...RECURRING,
        thisAndFuture: plan({ entryCount: 5, fromPeriod: '2026-10', toPeriod: '2027-02', itemOutcome: 'REMOVES_ITEM' }),
      };
      await open(data, [of(removesItem)]);

      expect(text(byId('this-and-future-text'))).toContain('también se elimina');
    });

    it('«Solo este mes»: el botón lo dice y elimina con ese alcance', async () => {
      await open(data, [of(RECURRING)]);

      await choose('scope-only-this');
      expect(confirm().textContent?.trim()).toBe('Eliminar solo este mes');
      expect(confirm().disabled).toBe(false);
      confirm().click();
      await refresh();

      expect(api.deleteEntry).toHaveBeenCalledWith(512, 'ONLY_THIS');
      expect(ref.close).toHaveBeenCalledWith({ deleted: true, message: 'Se eliminó «Luz» de diciembre 2026.' });
    });

    it('«Este mes y los siguientes»: el botón lo dice y elimina con ese alcance', async () => {
      await open(data, [of(RECURRING)]);

      await choose('scope-this-and-future');
      expect(confirm().textContent?.trim()).toBe('Eliminar este mes y los siguientes');
      confirm().click();
      await refresh();

      expect(api.deleteEntry).toHaveBeenCalledWith(512, 'THIS_AND_FUTURE');
      expect(ref.close).toHaveBeenCalledWith({
        deleted: true,
        message: 'Se eliminaron 3 partidas de «Luz». El Concepto termina en noviembre 2026.',
      });
    });

    it('sin elegir no se puede confirmar', async () => {
      await open(data, [of(RECURRING)]);

      confirm().click();
      await refresh();

      expect(api.deleteEntry).not.toHaveBeenCalled();
    });

    describe('partidas que lo impiden', () => {
      const blocked: EntryDeletionPreview = {
        ...RECURRING,
        thisAndFuture: plan({
          allowed: false,
          entryCount: 3,
          toPeriod: '2027-02',
          itemOutcome: 'ENDS_ITEM',
          newEndPeriod: '2026-11',
          blockers: [
            { entryId: 513, period: '2027-01', reason: 'CONSOLIDATED' },
            { entryId: 514, period: '2027-02', reason: 'HAS_MOVEMENTS' },
          ],
        }),
      };

      it('las lista por mes y motivo bajo su opción, y esa opción no se puede confirmar', async () => {
        await open(data, [of(blocked)]);

        const options = root().querySelectorAll('.option');
        expect(blockers(options[0])).toEqual([]);
        expect(blockers(options[1])).toEqual(['enero 2027: está consolidada', 'febrero 2027: tiene movimientos']);

        await choose('scope-this-and-future');
        expect(confirm().disabled).toBe(true);

        await choose('scope-only-this');
        expect(confirm().disabled).toBe(false);
      });

      it('si el backend rechaza igual, muestra el motivo, vuelve a pedir la vista previa y conserva lo elegido', async () => {
        await open(
          data,
          [of(RECURRING), of(blocked)],
          problem(409, {
            code: 'ENTRY_NOT_PENDING',
            detail: 'No se eliminó nada: 1 partida está consolidada y 1 tiene movimientos.',
            entries: [513, 514],
          }),
        );
        await choose('scope-this-and-future');

        confirm().click();
        await refresh();

        expect(api.deleteEntry).toHaveBeenCalledWith(512, 'THIS_AND_FUTURE');
        expect(api.getEntryDeletionPreview).toHaveBeenCalledTimes(2);
        expect(text(byId('delete-error'))).toBe('No se eliminó nada: 1 partida está consolidada y 1 tiene movimientos.');
        expect(blockers(root().querySelectorAll('.option')[1])).toEqual([
          'enero 2027: está consolidada',
          'febrero 2027: tiene movimientos',
        ]);
        // No se perdió lo elegido ni se cerró el diálogo.
        expect(radio('scope-this-and-future').checked).toBe(true);
        expect(confirm().disabled).toBe(true);
        expect(ref.close).not.toHaveBeenCalled();
      });
    });

    it('un período cerrado avisa, no deja confirmar y «Cancelar» pide recargar la pantalla', async () => {
      await open(data, [of(RECURRING)], problem(409, { code: 'PERIOD_CLOSED', detail: 'El período 2026-12 está cerrado.' }));
      await choose('scope-only-this');

      confirm().click();
      await refresh();

      expect(text(byId('delete-error'))).toBe('Ese mes ya está cerrado y no admite cambios.');
      expect(confirm().disabled).toBe(true);
      byId('cancel-delete')!.click();
      expect(ref.close).toHaveBeenCalledWith({ stale: true });
    });

    it('si no se puede cargar la vista previa, lo dice y no deja confirmar', async () => {
      await open(data, [problem(500, { code: 'INTERNAL_ERROR', detail: 'Ocurrió un error inesperado.' })]);

      expect(text(root().querySelector('[role="alert"]'))).toBe('Ocurrió un error inesperado.');
      expect(confirm().disabled).toBe(true);
      expect(root().querySelector('mat-radio-group')).toBeNull();
    });
  });
});
