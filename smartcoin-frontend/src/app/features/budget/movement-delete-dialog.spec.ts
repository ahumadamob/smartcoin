import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DynamicDialogConfig, DynamicDialogRef } from 'primeng/dynamicdialog';
import { of, throwError } from 'rxjs';
import { Movement, MovimientosService } from '../../api';
import { provideLocale } from '../../core/locale';
import { MovementDeleteDialog, MovementDeleteDialogData, MovementDeleteDialogResult } from './movement-delete-dialog';

const MOVEMENT: Movement = {
  id: 1204,
  entryId: 900,
  date: '2026-11-05',
  amount: 70000,
  note: 'Primera parte',
  accountId: 12,
  accountName: 'Banco Nación',
  currency: 'ARS',
};

describe('MovementDeleteDialog (HU-21)', () => {
  let fixture: ComponentFixture<MovementDeleteDialog>;
  let api: { deleteMovement: ReturnType<typeof vi.fn> };
  let ref: { close: ReturnType<typeof vi.fn<(result?: MovementDeleteDialogResult) => void>> };

  async function open(data: Partial<MovementDeleteDialogData> = {}, failure?: { status: number; body: object }) {
    api = {
      deleteMovement: vi.fn(() =>
        failure ? throwError(() => new HttpErrorResponse({ status: failure.status, error: failure.body })) : of(null),
      ),
    };
    ref = { close: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [MovementDeleteDialog],
      providers: [
        provideLocale(),
        { provide: MovimientosService, useValue: api },
        { provide: DynamicDialogRef, useValue: ref },
        {
          provide: DynamicDialogConfig,
          useValue: { data: { movement: MOVEMENT, entryName: 'Expensas', kind: 'EXPENSE', isOnly: false, ...data } },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(MovementDeleteDialog);
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const confirm = () => root().querySelector<HTMLButtonElement>('[data-testid="confirm-delete-movement"]')!;
  const cancel = () => root().querySelector<HTMLButtonElement>('[data-testid="cancel-delete-movement"]')!;

  it('muestra fecha, monto y cuenta del movimiento y el nombre de la partida', async () => {
    await open();

    expect(text(root().querySelector('h2'))).toBe('Eliminar pago');
    expect(text(root().querySelector('[data-testid="delete-date"]'))).toBe('05/11/2026');
    expect(text(root().querySelector('[data-testid="delete-amount"]'))).toBe('$ 70.000,00');
    expect(text(root().querySelector('[data-testid="delete-account"]'))).toBe('Banco Nación');
    expect(text(root())).toContain('«Expensas»');
    expect(root().querySelector('[data-testid="delete-only-note"]')).toBeNull();
  });

  it('un ingreso dice «cobro»', async () => {
    await open({ kind: 'INCOME' });

    expect(text(root().querySelector('h2'))).toBe('Eliminar cobro');
  });

  it('si es el único movimiento, avisa que la partida vuelve a Estimada', async () => {
    await open({ isOnly: true });

    expect(text(root().querySelector('[data-testid="delete-only-note"]'))).toContain('vuelve a verse Estimada');
  });

  it('sin confirmar no pide nada y «Cancelar» cierra sin resultado', async () => {
    await open();

    cancel().click();

    expect(api.deleteMovement).not.toHaveBeenCalled();
    expect(ref.close).toHaveBeenCalledWith(undefined);
  });

  it('confirmar elimina el movimiento y cierra con deleted', async () => {
    await open();

    confirm().click();

    expect(api.deleteMovement).toHaveBeenCalledWith(1204);
    expect(ref.close).toHaveBeenCalledWith({ deleted: true });
  });

  it('un rechazo del backend se muestra y no cierra', async () => {
    await open(
      {},
      {
        status: 409,
        body: { code: 'ENTRY_NOT_PENDING', title: 'La partida no está pendiente', detail: 'La partida está consolidada.' },
      },
    );

    confirm().click();
    fixture.detectChanges();

    expect(text(root().querySelector('[data-testid="delete-movement-error"]'))).not.toBe('');
    expect(ref.close).not.toHaveBeenCalled();
    cancel().click();
    expect(ref.close).toHaveBeenCalledWith({ stale: true });
  });

  it('un 404 (otra pestaña ya lo eliminó) marca la pantalla como desactualizada al cancelar', async () => {
    await open({}, { status: 404, body: { code: 'NOT_FOUND', title: 'No encontrado', detail: 'El movimiento no existe.' } });

    confirm().click();
    cancel().click();

    expect(ref.close).toHaveBeenCalledWith({ stale: true });
  });
});
