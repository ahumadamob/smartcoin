import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { AutoFocus } from 'primeng/autofocus';
import { ButtonDirective } from 'primeng/button';
import { DynamicDialogConfig, DynamicDialogRef } from 'primeng/dynamicdialog';
import { Movement, MovimientosService, PeriodEntry } from '../../api';
import { DialogTitle } from '../../core/dialogs';
import { messageFor } from '../../core/error-messages';
import { DATE_FORMAT } from '../../core/locale';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { movementWord } from './movement-text';

/** Qué se elimina: el movimiento, con el nombre y el tipo de su partida, y si es el único que tiene. */
export interface MovementDeleteDialogData {
  movement: Movement;
  entryName: string;
  kind: PeriodEntry.KindEnum;
  /** La partida no tiene otro movimiento: al eliminarlo vuelve a verse Estimada (RN-22). */
  isOnly: boolean;
}

/**
 * Cómo se cierra: `deleted` si el backend lo eliminó, `stale` si dijo que el período se cerró, la partida ya no está
 * pendiente o el movimiento ya no existe (la pantalla estaba desactualizada), o sin valor si el usuario canceló.
 */
export type MovementDeleteDialogResult = { deleted: true } | { stale: true };

const STALE_CODES = ['PERIOD_CLOSED', 'ENTRY_NOT_PENDING', 'NOT_FOUND'];

/**
 * Confirmación para eliminar un movimiento (HU-21, RN-22). Muestra exactamente cuál: fecha, monto y cuenta. No decide
 * nada: si el backend rechaza (mes cerrado, partida consolidada), muestra su mensaje.
 */
@Component({
  selector: 'app-movement-delete-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AutoFocus, ButtonDirective, DatePipe, DialogTitle, MoneyPipe],
  templateUrl: './movement-delete-dialog.html',
  styleUrl: './movement-delete-dialog.scss',
})
export class MovementDeleteDialog {
  private readonly api = inject(MovimientosService);
  private readonly ref = inject(DynamicDialogRef);
  protected readonly data = inject<DynamicDialogConfig<MovementDeleteDialogData>>(DynamicDialogConfig).data!;

  protected readonly movement = this.data.movement;
  protected readonly word = movementWord(this.data.kind);
  protected readonly dateFormat = DATE_FORMAT;
  protected readonly error = signal<string | null>(null);
  protected readonly submitting = signal(false);
  private stale = false;

  protected confirm(): void {
    if (this.submitting()) {
      return;
    }
    this.error.set(null);
    this.submitting.set(true);
    this.api.deleteMovement(this.movement.id).subscribe({
      next: () => this.ref.close({ deleted: true } satisfies MovementDeleteDialogResult),
      error: (e: unknown) => {
        this.submitting.set(false);
        const code = e instanceof HttpErrorResponse && typeof e.error === 'object' ? (e.error?.code as string) : '';
        this.stale = STALE_CODES.includes(code);
        this.error.set(messageFor(e));
      },
    });
  }

  protected cancel(): void {
    const result: MovementDeleteDialogResult | undefined = this.stale ? { stale: true } : undefined;
    this.ref.close(result);
  }
}
