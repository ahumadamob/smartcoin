import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ConsolidacinService, PeriodEntry } from '../../api';
import { messageFor } from '../../core/error-messages';
import { PeriodPipe } from '../../shared/pipes/period.pipe';

/** Qué se desconsolida: la partida, del mes que se está viendo. */
export interface UnconsolidateDialogData {
  entry: PeriodEntry;
  /** `YYYY-MM` del mes que se está viendo. */
  period: string;
}

/**
 * Cómo se cierra: `unconsolidated` con el aviso para el usuario, `stale` si el backend dijo que el período se cerró,
 * la partida ya no está consolidada o ya no existe (la pantalla estaba desactualizada), o sin valor si el usuario
 * canceló.
 */
export type UnconsolidateDialogResult = { unconsolidated: true; message: string } | { stale: true };

const STALE_CODES = ['PERIOD_CLOSED', 'ENTRY_NOT_CONSOLIDATED', 'NOT_FOUND'];

/**
 * Confirmación para desconsolidar una partida (HU-26, RN-29). No calcula nada: explica lo que no se revierte y deja
 * que el backend decida. Si rechaza (mes cerrado, partida ya pendiente), muestra su mensaje.
 */
@Component({
  selector: 'app-unconsolidate-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule, PeriodPipe],
  templateUrl: './unconsolidate-dialog.html',
  styleUrl: './unconsolidate-dialog.scss',
})
export class UnconsolidateDialog {
  private readonly api = inject(ConsolidacinService);
  private readonly ref = inject<MatDialogRef<UnconsolidateDialog, UnconsolidateDialogResult>>(MatDialogRef);
  protected readonly data = inject<UnconsolidateDialogData>(MAT_DIALOG_DATA);

  protected readonly recurring = this.data.entry.budgetItemId !== null;
  protected readonly error = signal<string | null>(null);
  protected readonly submitting = signal(false);
  private stale = false;

  protected confirm(): void {
    if (this.submitting()) {
      return;
    }
    this.error.set(null);
    this.submitting.set(true);
    this.api.unconsolidateEntry(this.data.entry.id).subscribe({
      next: () =>
        this.ref.close({
          unconsolidated: true,
          message: `«${this.data.entry.name}» volvió a Parcial: ya podés corregirla y consolidarla de nuevo.`,
        }),
      error: (e: unknown) => {
        this.submitting.set(false);
        const code = e instanceof HttpErrorResponse && typeof e.error === 'object' ? (e.error?.code as string) : '';
        this.stale = STALE_CODES.includes(code);
        this.error.set(messageFor(e));
      },
    });
  }

  protected cancel(): void {
    this.ref.close(this.stale ? { stale: true } : undefined);
  }
}
