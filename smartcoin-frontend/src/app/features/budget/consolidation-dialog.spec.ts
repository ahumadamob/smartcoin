import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Observable, of, throwError } from 'rxjs';
import {
  ConsolidacinService,
  Consolidation,
  ConsolidationPreview,
  ConsolidationTarget,
  PeriodEntry,
} from '../../api';
import { provideLocale } from '../../core/locale';
import {
  ConsolidationDialog,
  ConsolidationDialogData,
  ConsolidationDialogResult,
} from './consolidation-dialog';

const ENTRY: PeriodEntry = {
  id: 512,
  budgetItemId: 31,
  origin: 'RECURRING',
  kind: 'INCOME',
  name: 'Sueldo A',
  categoryId: null,
  categoryName: null,
  accountId: 12,
  accountName: 'Banco Nación',
  currency: 'ARS',
  dueDate: '2026-11-25',
  installmentNumber: null,
  installmentsTotal: null,
  budgetedAmount: 1800000,
  actualAmount: 1850000,
  pendingAmount: 0,
  forecastAmount: 1850000,
  status: 'PARTIAL',
  manual: false,
  overdue: false,
};

const target = (overrides: Partial<ConsolidationTarget>): ConsolidationTarget => ({
  entryId: 600,
  period: '2027-01',
  currentBudgetedAmount: 1800000,
  newBudgetedAmount: 1850000,
  manual: false,
  changes: true,
  ...overrides,
});

const preview = (overrides: Partial<ConsolidationPreview> = {}): ConsolidationPreview => ({
  entryId: 512,
  period: '2026-12',
  recurring: true,
  allowed: true,
  blockedBy: null,
  blockedDetail: null,
  actualAmount: 1850000,
  estimationRule: 'LAST_VALUE',
  estimate: 1850000,
  currentAmount: 1800000,
  estimateBasis: [{ period: '2026-12', amount: 1850000 }],
  targets: [
    target({ entryId: 600, period: '2027-01' }),
    target({ entryId: 601, period: '2027-02' }),
  ],
  manualPolicyRequired: false,
  ...overrides,
});

const problem = (status: number, body: object) => throwError(() => new HttpErrorResponse({ status, error: body }));

describe('ConsolidationDialog (HU-23, HU-25)', () => {
  let fixture: ComponentFixture<ConsolidationDialog>;
  let api: { getConsolidationPreview: ReturnType<typeof vi.fn>; consolidateEntry: ReturnType<typeof vi.fn> };
  let ref: { close: ReturnType<typeof vi.fn<(result?: ConsolidationDialogResult) => void>> };

  async function open(
    previews: Observable<ConsolidationPreview>[],
    consolidateResponse: Observable<Consolidation> = of({ entry: ENTRY, estimate: 1850000, updatedEntries: 2 }),
    data: Partial<ConsolidationDialogData> = {},
  ) {
    let call = 0;
    api = {
      getConsolidationPreview: vi.fn(() => previews[Math.min(call++, previews.length - 1)]),
      consolidateEntry: vi.fn(() => consolidateResponse),
    };
    ref = { close: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [ConsolidationDialog],
      providers: [
        provideLocale(),
        { provide: ConsolidacinService, useValue: api },
        { provide: MatDialogRef, useValue: ref },
        { provide: MAT_DIALOG_DATA, useValue: { entry: ENTRY, period: '2026-12', ...data } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ConsolidationDialog);
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
  const confirm = () => byId('confirm-consolidate') as HTMLButtonElement;
  const targetRows = () =>
    Array.from(root().querySelectorAll('[data-testid="target"]')).map((row) =>
      Array.from(row.querySelectorAll('th, td')).map((cell) => text(cell)),
    );

  it('pide la vista previa de la partida y muestra el real, el monto vigente actual y la nueva estimación', async () => {
    await open([of(preview())]);

    expect(api.getConsolidationPreview).toHaveBeenCalledWith(512);
    expect(text(root().querySelector('h2'))).toBe('Consolidar «Sueldo A» de diciembre 2026');
    expect(text(byId('actual'))).toBe('$ 1.850.000,00');
    expect(text(byId('current-amount'))).toBe('$ 1.800.000,00');
    expect(text(byId('estimate'))).toBe('$ 1.850.000,00');
    expect(text(byId('rule'))).toBe('Regla de estimación del Concepto: Último valor.');
  });

  describe('Concepto con promedio (HU-24)', () => {
    const average = (periods: string[]) =>
      preview({
        estimationRule: 'AVERAGE_LAST_3',
        estimate: 41753.33,
        estimateBasis: periods.map((period) => ({ period, amount: 100 })),
      });

    it('explica en una línea qué consolidaciones entraron en el promedio', async () => {
      await open([of(average(['2026-12', '2026-11', '2026-10']))]);

      expect(text(byId('average-basis'))).toBe('Promedio de octubre, noviembre y diciembre.');
      expect(text(byId('rule'))).toBe('Regla de estimación del Concepto: Promedio de los últimos 3.');
    });

    it('con una o dos consolidaciones aclara que promedia las que hay', async () => {
      await open([of(average(['2026-12', '2026-11']))]);

      expect(text(byId('average-basis'))).toContain('Promedio de noviembre y diciembre: las únicas consolidadas');
    });

    it('con «Último valor» no muestra la línea del promedio', async () => {
      await open([of(preview())]);

      expect(byId('average-basis')).toBeNull();
    });
  });

  it('lista las partidas futuras que cambian con su mes, el monto actual y el nuevo', async () => {
    await open([of(preview())]);

    expect(targetRows()).toEqual([
      ['enero 2027', '$ 1.800.000,00', '$ 1.850.000,00'],
      ['febrero 2027', '$ 1.800.000,00', '$ 1.850.000,00'],
    ]);
    expect(confirm().disabled).toBe(false);
  });

  it('solo lista las que cambian: las que ya valen la estimación no figuran', async () => {
    await open([
      of(
        preview({
          targets: [
            target({ entryId: 600, period: '2027-01', currentBudgetedAmount: 1850000, changes: false }),
            target({ entryId: 601, period: '2027-02' }),
          ],
        }),
      ),
    ]);

    expect(targetRows()).toEqual([['febrero 2027', '$ 1.800.000,00', '$ 1.850.000,00']]);
  });

  it('si no cambia ninguna lo dice, y deja confirmar', async () => {
    await open([
      of(preview({ targets: [target({ currentBudgetedAmount: 1850000, changes: false })] })),
    ]);

    expect(byId('targets')).toBeNull();
    expect(text(byId('no-changes'))).toContain('ya tienen ese valor');
    expect(confirm().disabled).toBe(false);
  });

  it('sin partidas pendientes en los meses siguientes también lo dice', async () => {
    await open([of(preview({ targets: [] }))]);

    expect(text(byId('no-changes'))).toContain('No hay partidas pendientes');
  });

  it('una partida sin Concepto explica que no afecta otros meses y no muestra estimación', async () => {
    await open(
      [of(preview({ recurring: false, estimate: null, currentAmount: null, estimationRule: null, targets: [] }))],
      of({ entry: ENTRY, estimate: null, updatedEntries: 0 }),
    );

    expect(text(byId('no-propagation'))).toContain('no afecta otros meses');
    expect(byId('estimate')).toBeNull();
    expect(byId('targets')).toBeNull();
    expect(confirm().disabled).toBe(false);
  });

  it('avisa que se puede desconsolidar mientras el mes esté abierto, sin decir que no se puede deshacer', async () => {
    await open([of(preview())]);

    expect(text(byId('irreversible'))).toContain('desconsolidarla mientras el mes esté abierto');
    expect(text(root())).not.toContain('no se puede deshacer');
  });

  describe('con partidas editadas en los meses siguientes (HU-25)', () => {
    const withEdited = preview({
      manualPolicyRequired: true,
      targets: [
        target({ entryId: 600, period: '2027-01', currentBudgetedAmount: 300000, manual: true, changes: true }),
        target({ entryId: 601, period: '2027-02' }),
        target({ entryId: 602, period: '2027-03' }),
      ],
    });
    const radio = (id: string) => byId(id)?.querySelector<HTMLInputElement>('input') as HTMLInputElement;
    const choose = async (id: string) => {
      radio(id).click();
      await refresh();
    };

    it('ofrece Respetar y Pisar sin ninguna elegida, con una línea que explica cada una', async () => {
      await open([of(withEdited)]);

      expect(text(byId('policy-required'))).toContain('Hay 1 partida editada a mano');
      expect(radio('policy-keep').checked).toBe(false);
      expect(radio('policy-overwrite').checked).toBe(false);
      expect(text(byId('policy-keep-text'))).toBe(
        'La partida editada conserva su monto y sigue editada. 2 otras partidas cambian de monto.',
      );
      expect(text(byId('policy-overwrite-text'))).toBe(
        'La partida editada toma la nueva estimación y deja de estar editada. 2 otras partidas cambian de monto.',
      );
    });

    it('lista las editadas con su mes, el monto actual y el que tomarían si se pisan', async () => {
      await open([of(withEdited)]);

      expect(text(byId('edited-target'))).toBe('enero 2027$ 300.000,00 $ 1.850.000,00');
      // Las no editadas siguen en su propia lista.
      expect(targetRows().map((row) => row[0])).toEqual(['febrero 2027', 'marzo 2027']);
    });

    it('no deja confirmar sin elegir y, aunque se apriete, no hace el pedido', async () => {
      await open([of(withEdited)]);

      expect(text(byId('policy-hint'))).toContain('Elegí una opción');
      expect(confirm().disabled).toBe(true);
      confirm().click();
      await refresh();

      expect(api.consolidateEntry).not.toHaveBeenCalled();
    });

    it('con Respetar confirma y envía KEEP', async () => {
      await open([of(withEdited)]);

      await choose('policy-keep');
      expect(confirm().disabled).toBe(false);
      expect(byId('policy-hint')).toBeNull();
      confirm().click();
      await refresh();

      expect(api.consolidateEntry).toHaveBeenCalledWith(512, { manualEntriesPolicy: 'KEEP' });
      expect(ref.close).toHaveBeenCalled();
    });

    it('con Pisar confirma y envía OVERWRITE', async () => {
      await open([of(withEdited)]);

      await choose('policy-overwrite');
      confirm().click();
      await refresh();

      expect(api.consolidateEntry).toHaveBeenCalledWith(512, { manualEntriesPolicy: 'OVERWRITE' });
    });

    it('es una sola elección para todas: cambiarla reemplaza la anterior', async () => {
      await open([of(withEdited)]);

      await choose('policy-keep');
      await choose('policy-overwrite');
      confirm().click();
      await refresh();

      expect(api.consolidateEntry).toHaveBeenCalledWith(512, { manualEntriesPolicy: 'OVERWRITE' });
    });

    it('una editada que ya vale la estimación obliga a elegir y aclara que solo pierde la marca', async () => {
      await open([
        of(
          preview({
            manualPolicyRequired: true,
            targets: [target({ currentBudgetedAmount: 1850000, manual: true, changes: false })],
          }),
        ),
      ]);

      expect(text(byId('edited-target'))).toContain('(ya coincide)');
      expect(text(byId('policy-overwrite-text'))).toContain('ya tiene ese monto: solo deja de estar editada');
      expect(confirm().disabled).toBe(true);
    });

    it('sin editadas no pregunta nada y confirma sin enviar política', async () => {
      await open([of(preview())]);

      expect(byId('policy-choice')).toBeNull();
      expect(confirm().disabled).toBe(false);
      confirm().click();
      await refresh();

      expect(api.consolidateEntry).toHaveBeenCalledWith(512, undefined);
    });
  });

  describe('una partida que no se puede consolidar', () => {
    it('dice el motivo que informa el backend y no deja confirmar', async () => {
      await open([
        of(
          preview({
            allowed: false,
            blockedBy: 'CONSOLIDATION_REQUIRES_MOVEMENT',
            blockedDetail: 'La partida no tiene movimientos: registrá al menos un cobro o pago antes de consolidarla.',
            estimate: null,
            currentAmount: null,
            estimationRule: null,
            targets: [],
          }),
        ),
      ]);

      expect(text(byId('blocked'))).toContain('no tiene movimientos');
      expect(confirm().disabled).toBe(true);
    });

    it('si la vista previa falla muestra el error y no deja confirmar', async () => {
      await open([problem(404, { code: 'NOT_FOUND', detail: 'La partida no existe.' })]);

      expect(text(root().querySelector('[role="alert"]'))).toBe('La partida no existe.');
      expect(confirm().disabled).toBe(true);
    });
  });

  describe('al confirmar', () => {
    it('consolida y se cierra con el aviso de lo que hizo el backend y la partida actualizada', async () => {
      const consolidated = { ...ENTRY, status: 'CONSOLIDATED' as const, pendingAmount: 0 };
      await open([of(preview())], of({ entry: consolidated, estimate: 1850000, updatedEntries: 2 }));

      confirm().click();
      await refresh();

      expect(api.consolidateEntry).toHaveBeenCalledWith(512, undefined);
      expect(ref.close).toHaveBeenCalledWith({
        consolidated: true,
        entry: consolidated,
        message: '«Sueldo A» consolidada por $ 1.850.000,00. Se actualizaron 2 partidas de los meses siguientes.',
      });
    });

    it('un error del backend se muestra con su detalle y deja la elección intacta', async () => {
      await open(
        [of(preview())],
        problem(409, { code: 'PERIOD_CLOSED', detail: 'El período 2026-12 está cerrado y no admite cambios.' }),
      );

      confirm().click();
      await refresh();

      expect(text(byId('consolidate-error'))).toBe('El período 2026-12 está cerrado y no admite cambios.');
      expect(ref.close).not.toHaveBeenCalled();
      expect(confirm().disabled).toBe(true);

      // Al cancelar, avisa que la pantalla estaba desactualizada.
      (byId('cancel-consolidate') as HTMLButtonElement).click();
      expect(ref.close).toHaveBeenCalledWith({ stale: true });
    });

    it('si aparecen editadas desde la vista previa, la vuelve a pedir, muestra la elección y el error', async () => {
      const edited = preview({
        manualPolicyRequired: true,
        targets: [target({ manual: true, changes: true, currentBudgetedAmount: 300000 })],
      });
      await open(
        [of(preview()), of(edited)],
        problem(409, { code: 'MANUAL_POLICY_REQUIRED', detail: 'Hay 1 partida editada en los meses siguientes.' }),
      );

      confirm().click();
      await refresh();

      expect(api.consolidateEntry).toHaveBeenCalledTimes(1);
      expect(api.getConsolidationPreview).toHaveBeenCalledTimes(2);
      expect(byId('policy-choice')).not.toBeNull();
      expect(text(byId('consolidate-error'))).toBe('Hay 1 partida editada en los meses siguientes.');
      expect(confirm().disabled).toBe(true);
      expect(ref.close).not.toHaveBeenCalled();

      // Elige y confirma: ahora sí viaja la política.
      (byId('policy-keep')!.querySelector('input') as HTMLInputElement).click();
      await refresh();
      confirm().click();
      await refresh();

      expect(api.consolidateEntry).toHaveBeenLastCalledWith(512, { manualEntriesPolicy: 'KEEP' });
    });

    it('si ya había elegido y el backend lo vuelve a pedir, conserva la elección', async () => {
      const edited = preview({
        manualPolicyRequired: true,
        targets: [target({ manual: true, changes: true, currentBudgetedAmount: 300000 })],
      });
      await open([of(edited), of(edited)], problem(409, { code: 'MANUAL_POLICY_REQUIRED', detail: 'Cambió.' }));

      (byId('policy-overwrite')!.querySelector('input') as HTMLInputElement).click();
      await refresh();
      confirm().click();
      await refresh();

      expect(api.getConsolidationPreview).toHaveBeenCalledTimes(2);
      expect((byId('policy-overwrite')!.querySelector('input') as HTMLInputElement).checked).toBe(true);
      expect(confirm().disabled).toBe(false);
      expect(text(byId('consolidate-error'))).toBe('Cambió.');
    });

    it('Cancelar cierra sin resultado', async () => {
      await open([of(preview())]);

      (byId('cancel-consolidate') as HTMLButtonElement).click();

      expect(ref.close).toHaveBeenCalledWith(undefined);
    });
  });
});
