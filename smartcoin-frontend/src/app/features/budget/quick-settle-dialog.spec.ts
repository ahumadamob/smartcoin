import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import {
  AccountResponse,
  ConsolidationPreview,
  ConsolidationTarget,
  CuentasService,
  MovementDates,
  MovimientosService,
  PeriodEntry,
  QuickSettle,
  QuickSettlePreview,
} from '../../api';
import { provideLocale } from '../../core/locale';
import { QuickSettleDialog, QuickSettleDialogData, QuickSettleDialogResult } from './quick-settle-dialog';

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
  id: 40,
  budgetItemId: 31,
  origin: 'RECURRING',
  kind: 'EXPENSE',
  name: 'Luz',
  categoryId: null,
  categoryName: null,
  accountId: 12,
  accountName: 'Banco Nación',
  currency: 'ARS',
  dueDate: '2026-11-18',
  installmentNumber: null,
  installmentsTotal: null,
  budgetedAmount: 45000,
  actualAmount: 41000,
  pendingAmount: 4000,
  forecastAmount: 45000,
  status: 'PARTIAL',
  manual: false,
  overdue: false,
};

const DATES: MovementDates = { earliestDate: '2026-10-22', latestDate: '2026-11-20', earlyDays: 10 };

const target = (overrides: Partial<ConsolidationTarget>): ConsolidationTarget => ({
  entryId: 600,
  period: '2026-12',
  currentBudgetedAmount: 40000,
  newBudgetedAmount: 45000,
  manual: false,
  changes: true,
  ...overrides,
});

const consolidation = (overrides: Partial<ConsolidationPreview> = {}): ConsolidationPreview => ({
  entryId: 40,
  period: '2026-11',
  recurring: true,
  allowed: true,
  blockedBy: null,
  blockedDetail: null,
  actualAmount: 45000,
  estimationRule: 'LAST_VALUE',
  estimate: 45000,
  currentAmount: 40000,
  estimateBasis: [{ period: '2026-11', amount: 45000 }],
  targets: [target({ entryId: 600, period: '2026-12' }), target({ entryId: 601, period: '2027-01' })],
  manualPolicyRequired: false,
  ...overrides,
});

const preview = (overrides: Partial<QuickSettlePreview> = {}): QuickSettlePreview => ({
  entryId: 40,
  allowed: true,
  blockedBy: null,
  blockedDetail: null,
  amount: 4000,
  consolidation: consolidation(),
  ...overrides,
});

const settled = (): QuickSettle => ({
  movement: {
    id: 9001,
    entryId: 40,
    date: '2026-11-20',
    amount: 4000,
    note: null,
    accountId: 12,
    accountName: 'Banco Nación',
    currency: 'ARS',
  },
  entry: { ...ENTRY, status: 'CONSOLIDATED', actualAmount: 45000, pendingAmount: 0 },
  estimate: 45000,
  updatedEntries: 2,
});

const problem = (status: number, body: object) => throwError(() => new HttpErrorResponse({ status, error: body }));

describe('QuickSettleDialog (HU-22)', () => {
  let fixture: ComponentFixture<QuickSettleDialog>;
  let api: {
    getQuickSettlePreview: ReturnType<typeof vi.fn>;
    getMovementDates: ReturnType<typeof vi.fn>;
    quickSettleEntry: ReturnType<typeof vi.fn>;
  };
  let ref: { close: ReturnType<typeof vi.fn<(result?: QuickSettleDialogResult) => void>> };

  async function open(
    previews: Observable<QuickSettlePreview>[] = [of(preview())],
    response: Observable<QuickSettle> = of(settled()),
    data: Partial<QuickSettleDialogData> = {},
    options: { dates?: MovementDates; accounts?: AccountResponse[] } = {},
  ) {
    let call = 0;
    api = {
      getQuickSettlePreview: vi.fn(() => previews[Math.min(call++, previews.length - 1)]),
      getMovementDates: vi.fn(() => of(options.dates ?? DATES)),
      quickSettleEntry: vi.fn(() => response),
    };
    ref = { close: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [QuickSettleDialog],
      providers: [
        provideLocale(),
        provideRouter([]),
        { provide: MovimientosService, useValue: api },
        {
          provide: CuentasService,
          useValue: { listAccounts: () => of({ accounts: options.accounts ?? ACCOUNTS, subtotals: [] }) },
        },
        { provide: MatDialogRef, useValue: ref },
        { provide: MAT_DIALOG_DATA, useValue: { entry: ENTRY, period: '2026-11', ...data } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(QuickSettleDialog);
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
  const confirm = () => byId('confirm-quick-settle') as HTMLButtonElement;
  const dateInput = () => root().querySelector<HTMLInputElement>('[formcontrolname="date"]') as HTMLInputElement;
  const radio = (id: string) => byId(id)?.querySelector<HTMLInputElement>('input') as HTMLInputElement;
  const choose = async (id: string) => {
    radio(id).click();
    await refresh();
  };
  const targetRows = () =>
    Array.from(root().querySelectorAll('[data-testid="target"]')).map((row) =>
      Array.from(row.querySelectorAll('th, td')).map((cell) => text(cell)),
    );

  it('un gasto se llama «Pagar todo» y muestra el movimiento por el pendiente que informa el backend', async () => {
    await open();

    expect(api.getQuickSettlePreview).toHaveBeenCalledWith(40);
    expect(text(root().querySelector('h2'))).toBe('Pagar todo · Luz');
    expect(text(byId('settle-amount'))).toContain('Se va a registrar un pago de $ 4.000,00');
    expect(text(confirm())).toBe('Pagar todo');
  });

  it('un ingreso se llama «Cobrar todo» y habla de un cobro', async () => {
    await open([of(preview())], of(settled()), { entry: { ...ENTRY, kind: 'INCOME' } });

    expect(text(root().querySelector('h2'))).toBe('Cobrar todo · Luz');
    expect(text(byId('settle-amount'))).toContain('Se va a registrar un cobro de $ 4.000,00');
  });

  it('avisa que se puede desconsolidar mientras el mes esté abierto, sin decir que no se puede deshacer', async () => {
    await open();

    expect(text(byId('irreversible'))).toContain('desconsolidarla mientras el mes esté abierto');
    expect(text(root())).not.toContain('no se puede deshacer');
  });

  it('muestra la nueva estimación y las partidas futuras que cambian, del mismo plan del backend', async () => {
    await open();

    expect(text(byId('actual'))).toBe('$ 45.000,00');
    expect(text(byId('current-amount'))).toBe('$ 40.000,00');
    expect(text(byId('estimate'))).toBe('$ 45.000,00');
    expect(targetRows()).toEqual([
      ['diciembre 2026', '$ 40.000,00', '$ 45.000,00'],
      ['enero 2027', '$ 40.000,00', '$ 45.000,00'],
    ]);
  });

  describe('fecha y cuenta', () => {
    it('la fecha arranca con la que sugiere el backend y la cuenta con la de la partida', async () => {
      await open();

      expect(api.getMovementDates).toHaveBeenCalledWith(40, 12);
      expect(dateInput().value).toBe('2026-11-20');
      expect(text(root().querySelector('mat-select'))).toContain('Banco Nación');
    });

    it('explica la ventana de anticipación con los días y la fecha más temprana del backend', async () => {
      await open();

      expect(text(byId('window-notice'))).toBe(
        'Se puede fechar hasta 10 días antes del inicio de noviembre 2026. Fecha más temprana con esta cuenta: 22/10/2026.',
      );
    });

    it('solo ofrece cuentas de la moneda de la partida', async () => {
      await open();
      root().querySelector<HTMLElement>('mat-select .mat-mdc-select-trigger')?.click();
      await refresh();

      const options = Array.from(document.querySelectorAll('mat-option')).map((o) => text(o));
      expect(options).toEqual(['Banco Nación', 'Billetera']);
    });

    it('con la ventana sin abrir lo avisa y no deja confirmar', async () => {
      await open([of(preview())], of(settled()), {}, {
        dates: { earliestDate: '2026-11-21', latestDate: '2026-11-20', earlyDays: 10 },
      });

      expect(text(byId('window-notice'))).toBe(
        'Todavía no se pueden registrar movimientos de esta partida: se admiten desde el 21/11/2026.',
      );
      expect(confirm().disabled).toBe(true);
    });

    it('sin cuentas en la moneda de la partida lo dice y no deja confirmar', async () => {
      await open([of(preview())], of(settled()), {}, { accounts: [account(13, 'Caja en dólares', 'USD')] });

      expect(text(byId('no-accounts'))).toContain('No tenés una cuenta en pesos');
      expect(confirm().disabled).toBe(true);
    });
  });

  describe('al confirmar', () => {
    it('envía la fecha y la cuenta, sin política, y cierra con el aviso y la partida consolidada', async () => {
      await open();

      confirm().click();
      await refresh();

      expect(api.quickSettleEntry).toHaveBeenCalledWith(40, { date: '2026-11-20', accountId: 12 });
      const result = ref.close.mock.calls[0][0] as { settled: true; message: string; entry: PeriodEntry };
      expect(result.settled).toBe(true);
      expect(result.entry.status).toBe('CONSOLIDATED');
      expect(result.message).toBe(
        'Pago de $ 4.000,00 registrado. «Luz» consolidada por $ 45.000,00. Se actualizaron 2 partidas de los meses siguientes.',
      );
    });

    it('envía la fecha editada', async () => {
      await open();

      dateInput().value = '2026-11-05';
      dateInput().dispatchEvent(new Event('input'));
      await refresh();
      confirm().click();
      await refresh();

      expect(api.quickSettleEntry).toHaveBeenCalledWith(40, { date: '2026-11-05', accountId: 12 });
    });

    it('con una fecha mal escrita no hace el pedido', async () => {
      await open();

      dateInput().value = '';
      dateInput().dispatchEvent(new Event('input'));
      await refresh();
      confirm().click();
      await refresh();

      expect(api.quickSettleEntry).not.toHaveBeenCalled();
      expect(text(root())).toContain('Ingresá la fecha del pago.');
    });
  });

  describe('con partidas editadas en el destino', () => {
    const withEdited = preview({
      consolidation: consolidation({
        manualPolicyRequired: true,
        targets: [
          target({ entryId: 600, period: '2026-12', currentBudgetedAmount: 50000, manual: true }),
          target({ entryId: 601, period: '2027-01' }),
        ],
      }),
    });

    it('pide elegir Respetar o Pisar, sin ninguna elegida, y no deja confirmar', async () => {
      await open([of(withEdited)]);

      expect(text(byId('policy-required'))).toContain('Hay 1 partida editada a mano');
      expect(radio('policy-keep').checked).toBe(false);
      expect(radio('policy-overwrite').checked).toBe(false);
      expect(confirm().disabled).toBe(true);
      confirm().click();
      await refresh();
      expect(api.quickSettleEntry).not.toHaveBeenCalled();
    });

    it('con Respetar confirma y envía KEEP', async () => {
      await open([of(withEdited)]);

      await choose('policy-keep');
      confirm().click();
      await refresh();

      expect(api.quickSettleEntry).toHaveBeenCalledWith(40, {
        date: '2026-11-20',
        accountId: 12,
        manualEntriesPolicy: 'KEEP',
      });
    });

    it('con Pisar confirma y envía OVERWRITE', async () => {
      await open([of(withEdited)]);

      await choose('policy-overwrite');
      confirm().click();
      await refresh();

      expect(api.quickSettleEntry).toHaveBeenCalledWith(40, {
        date: '2026-11-20',
        accountId: 12,
        manualEntriesPolicy: 'OVERWRITE',
      });
    });
  });

  describe('errores', () => {
    it('ante MANUAL_POLICY_REQUIRED porque algo cambió, vuelve a pedir la vista previa y muestra la elección', async () => {
      const withEdited = preview({
        consolidation: consolidation({
          manualPolicyRequired: true,
          targets: [target({ entryId: 600, manual: true, currentBudgetedAmount: 50000 })],
        }),
      });
      await open(
        [of(preview()), of(withEdited)],
        problem(409, { code: 'MANUAL_POLICY_REQUIRED', detail: 'Hay 1 partida editada en los meses siguientes.' }),
      );
      expect(byId('policy-choice')).toBeNull();

      confirm().click();
      await refresh();

      expect(api.getQuickSettlePreview).toHaveBeenCalledTimes(2);
      expect(byId('policy-choice')).not.toBeNull();
      expect(text(byId('settle-error'))).toContain('Hay 1 partida editada');
      expect(confirm().disabled).toBe(true);
      expect(ref.close).not.toHaveBeenCalled();
    });

    it('una fecha fuera de rango se muestra bajo el campo con el detalle del backend, sin perder lo cargado', async () => {
      await open(
        [of(preview())],
        problem(409, { code: 'DATE_OUT_OF_RANGE', detail: 'La fecha no puede ser posterior a hoy (20/11/2026).' }),
      );
      dateInput().value = '2026-11-25';
      dateInput().dispatchEvent(new Event('input'));
      await refresh();

      confirm().click();
      await refresh();

      expect(text(root())).toContain('La fecha no puede ser posterior a hoy (20/11/2026).');
      expect(dateInput().value).toBe('2026-11-25');
      expect(ref.close).not.toHaveBeenCalled();
      expect(confirm().disabled).toBe(false);
    });

    it('una cuenta de otra moneda se muestra bajo el campo de la cuenta', async () => {
      await open([of(preview())], problem(409, { code: 'CURRENCY_MISMATCH', detail: 'x' }));

      confirm().click();
      await refresh();

      expect(text(root())).toContain('La cuenta elegida es de otra moneda.');
    });

    it('si la partida ya no tiene pendiente, avisa y deja de ofrecer la acción; cancelar avisa que está desactualizada', async () => {
      await open(
        [of(preview())],
        problem(409, { code: 'NOTHING_PENDING', detail: 'No hay nada pendiente en esta partida.' }),
      );

      confirm().click();
      await refresh();

      expect(text(byId('settle-error'))).toContain('No hay nada pendiente');
      expect(confirm().disabled).toBe(true);
      byId('cancel-quick-settle')?.click();
      expect(ref.close).toHaveBeenCalledWith({ stale: true });
    });

    it('una partida ya consolidada o un período cerrado también dejan la pantalla desactualizada', async () => {
      await open([of(preview())], problem(409, { code: 'ENTRY_NOT_PENDING', detail: 'La partida ya está consolidada.' }));

      confirm().click();
      await refresh();
      byId('cancel-quick-settle')?.click();

      expect(ref.close).toHaveBeenCalledWith({ stale: true });
    });

    it('una vista previa bloqueada explica el motivo y no deja confirmar', async () => {
      await open([
        of(
          preview({
            allowed: false,
            blockedBy: 'NOTHING_PENDING',
            blockedDetail: 'No hay nada pendiente en esta partida. Si ya tiene movimientos, consolidala.',
            amount: null,
            consolidation: undefined,
          }),
        ),
      ]);

      expect(text(byId('blocked'))).toContain('No hay nada pendiente');
      expect(confirm().disabled).toBe(true);
      expect(byId('settle-amount')).toBeNull();
    });

    it('si falla al cargar muestra el error y no deja confirmar', async () => {
      await open([problem(404, { code: 'NOT_FOUND', detail: 'La partida no existe.' })]);

      expect(text(root().querySelector('[role="alert"]'))).toContain('La partida no existe.');
      expect(confirm().disabled).toBe(true);
    });
  });

  it('cancelar sin haber hecho nada cierra sin resultado', async () => {
    await open();

    byId('cancel-quick-settle')?.click();

    expect(ref.close).toHaveBeenCalledWith(undefined);
    expect(api.quickSettleEntry).not.toHaveBeenCalled();
  });
});
