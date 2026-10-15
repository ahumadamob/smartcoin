import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { ConsolidacinService, PeriodEntry } from '../../api';
import { provideLocale } from '../../core/locale';
import { UnconsolidateDialog, UnconsolidateDialogData, UnconsolidateDialogResult } from './unconsolidate-dialog';

function entry(overrides: Partial<PeriodEntry> = {}): PeriodEntry {
  return {
    id: 512,
    budgetItemId: 31,
    origin: 'RECURRING',
    kind: 'EXPENSE',
    name: 'Luz',
    categoryId: null,
    categoryName: null,
    accountId: 12,
    accountName: 'Banco',
    currency: 'ARS',
    dueDate: '2026-11-10',
    installmentNumber: null,
    installmentsTotal: null,
    budgetedAmount: 45000,
    actualAmount: 50000,
    pendingAmount: 0,
    forecastAmount: 50000,
    status: 'CONSOLIDATED',
    manual: false,
    overdue: false,
    ...overrides,
  } as PeriodEntry;
}

describe('UnconsolidateDialog (HU-26)', () => {
  let fixture: ComponentFixture<UnconsolidateDialog>;
  let api: { unconsolidateEntry: ReturnType<typeof vi.fn> };
  let ref: { close: ReturnType<typeof vi.fn<(result?: UnconsolidateDialogResult) => void>> };

  async function open(data: Partial<UnconsolidateDialogData> = {}, failure?: { status: number; body: object }) {
    api = {
      unconsolidateEntry: vi.fn(() =>
        failure
          ? throwError(() => new HttpErrorResponse({ status: failure.status, error: failure.body }))
          : of(entry({ status: 'PARTIAL' })),
      ),
    };
    ref = { close: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [UnconsolidateDialog],
      providers: [
        provideLocale(),
        { provide: ConsolidacinService, useValue: api },
        { provide: MatDialogRef, useValue: ref },
        { provide: MAT_DIALOG_DATA, useValue: { entry: entry(), period: '2026-11', ...data } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(UnconsolidateDialog);
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const byId = (id: string) => root().querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const confirm = () => byId('confirm-unconsolidate') as HTMLButtonElement;
  const cancel = () => byId('cancel-unconsolidate') as HTMLButtonElement;

  it('nombra la partida y el mes, y dice que vuelve a Parcial', async () => {
    await open();

    expect(text(root().querySelector('h2'))).toBe('Desconsolidar «Luz» de noviembre 2026');
    expect(text(byId('unconsolidate-effect'))).toContain('vuelve a Parcial');
    expect(text(byId('unconsolidate-effect'))).toContain('Mientras el mes esté abierto');
  });

  it('en una recurrente explica que lo propagado no se deshace, sin mostrar cifras', async () => {
    await open();

    const kept = text(byId('unconsolidate-kept'));
    expect(kept).toContain('Lo que se propagó no se deshace');
    expect(kept).toContain('monto vigente del Concepto');
    expect(kept).toContain('próxima vez que consolides este Concepto');
    expect(kept).not.toMatch(/\$/);
    expect(byId('unconsolidate-no-propagation')).toBeNull();
  });

  it('en una partida sin Concepto dice que no afecta otros meses', async () => {
    await open({ entry: entry({ budgetItemId: null }) });

    expect(byId('unconsolidate-kept')).toBeNull();
    expect(text(byId('unconsolidate-no-propagation'))).toContain('no afecta otros meses');
  });

  it('sin confirmar no pide nada y «Cancelar» cierra sin resultado', async () => {
    await open();

    cancel().click();

    expect(api.unconsolidateEntry).not.toHaveBeenCalled();
    expect(ref.close).toHaveBeenCalledWith(undefined);
  });

  it('confirmar desconsolida y cierra con el aviso', async () => {
    await open();

    confirm().click();

    expect(api.unconsolidateEntry).toHaveBeenCalledWith(512);
    expect(ref.close).toHaveBeenCalledWith({
      unconsolidated: true,
      message: '«Luz» volvió a Parcial: ya podés corregirla y consolidarla de nuevo.',
    });
  });

  it('un período cerrado se muestra, no cierra y al cancelar marca la pantalla como desactualizada', async () => {
    await open(
      {},
      { status: 409, body: { code: 'PERIOD_CLOSED', title: 'Período cerrado', detail: 'El período está cerrado.' } },
    );

    confirm().click();
    fixture.detectChanges();

    expect(text(byId('unconsolidate-error'))).not.toBe('');
    expect(ref.close).not.toHaveBeenCalled();
    cancel().click();
    expect(ref.close).toHaveBeenCalledWith({ stale: true });
  });

  it('una partida que ya no está consolidada también deja la pantalla desactualizada', async () => {
    await open(
      {},
      {
        status: 409,
        body: { code: 'ENTRY_NOT_CONSOLIDATED', title: 'No consolidada', detail: 'La partida no está consolidada.' },
      },
    );

    confirm().click();
    fixture.detectChanges();

    expect(text(byId('unconsolidate-error'))).toContain('no está consolidada');
    cancel().click();
    expect(ref.close).toHaveBeenCalledWith({ stale: true });
  });
});
