import { HttpErrorResponse } from '@angular/common/http';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  OnInit,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatRadioModule } from '@angular/material/radio';
import { DeletionPlan, EntryDeletionPreview, PartidasService, PeriodEntry } from '../../api';
import { messageFor } from '../../core/error-messages';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { PeriodPipe } from '../../shared/pipes/period.pipe';
import { EntryDeletionBlockers } from './entry-deletion-blockers';
import {
  confirmLabel,
  onlyThisText,
  originNote,
  Scope,
  successMessage,
  thisAndFutureText,
} from './entry-deletion-text';

/** Qué se elimina: la partida elegida, del mes que se está viendo. */
export interface EntryDeleteDialogData {
  entry: PeriodEntry;
  /** `YYYY-MM` del mes que se está viendo. */
  period: string;
}

/**
 * Cómo se cierra: `deleted` con el aviso para el usuario, `stale` si el backend dijo que el período se cerró o la
 * partida ya no existe (la pantalla estaba desactualizada), o sin valor si el usuario canceló.
 */
export type EntryDeleteDialogResult = { deleted: true; message: string } | { stale: true };

const BLOCKED_CODES = ['ENTRY_NOT_PENDING', 'ENTRY_HAS_MOVEMENTS'];
const STALE_CODES = ['PERIOD_CLOSED', 'NOT_FOUND'];

/**
 * Diálogo para eliminar una partida (HU-18, RN-30 a RN-32). Al abrirse pide al backend la vista previa de la
 * eliminación y muestra exactamente qué va a pasar; el frontend no calcula nada.
 *
 * - **Sin Concepto**: una confirmación simple con el nombre y el monto de la partida.
 * - **De un Concepto**: «Solo este mes» y «Este mes y los siguientes», sin ninguna elegida de entrada, cada una
 *   explicada con las cifras de la vista previa. El botón de confirmar dice lo que va a hacer.
 *
 * Las partidas que impiden la eliminación se listan por mes y motivo, con la opción elegida intacta. Si el backend
 * rechaza igual (otra pestaña cambió algo), vuelve a pedir la vista previa y lista las que impiden.
 */
@Component({
  selector: 'app-entry-delete-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatRadioModule,
    EntryDeletionBlockers,
    MoneyPipe,
    PeriodPipe,
  ],
  templateUrl: './entry-delete-dialog.html',
  styleUrl: './entry-delete-dialog.scss',
})
export class EntryDeleteDialog implements OnInit {
  private readonly api = inject(PartidasService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly ref = inject<MatDialogRef<EntryDeleteDialog, EntryDeleteDialogResult>>(MatDialogRef);
  private readonly periodPipe = new PeriodPipe();
  protected readonly data = inject<EntryDeleteDialogData>(MAT_DIALOG_DATA);

  protected readonly entry = this.data.entry;
  protected readonly preview = signal<EntryDeletionPreview | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  /** Error del backend al eliminar, o `null`. */
  protected readonly error = signal<string | null>(null);
  protected readonly submitting = signal(false);
  /** La pantalla estaba desactualizada (período cerrado, partida inexistente): ya no se puede eliminar desde acá. */
  protected readonly gone = signal(false);

  /** El alcance elegido. Empieza sin elegir, a propósito: eliminar el futuro es una decisión que se toma. */
  protected readonly scope = new FormControl<Scope | null>(null);
  private readonly selectedScope = toSignal(this.scope.valueChanges, { initialValue: null });

  protected readonly recurring = computed(() => this.preview()?.recurring ?? this.entry.budgetItemId !== null);
  protected readonly title = computed(() =>
    this.recurring() ? `Eliminar «${this.entry.name}» de ${this.month(this.data.period)}` : 'Eliminar partida',
  );
  /** El plan que se aplicaría con lo elegido; sin Concepto, el único. */
  protected readonly plan = computed<DeletionPlan | null>(() => {
    const preview = this.preview();
    if (preview === null) {
      return null;
    }
    if (!preview.recurring) {
      return preview.removal ?? null;
    }
    const scope = this.selectedScope();
    return scope === 'ONLY_THIS' ? (preview.onlyThis ?? null) : scope === 'THIS_AND_FUTURE' ? (preview.thisAndFuture ?? null) : null;
  });
  protected readonly canConfirm = computed(
    () => !this.submitting() && !this.gone() && this.plan() !== null && this.plan()!.allowed,
  );
  protected readonly confirmText = computed(() => confirmLabel(this.selectedScope(), this.recurring()));

  protected readonly originWarning = computed(() => {
    const preview = this.preview();
    return preview !== null && !preview.recurring ? originNote(preview.origin) : null;
  });
  protected readonly onlyThisDescription = computed(() => {
    const plan = this.preview()?.onlyThis;
    return plan ? onlyThisText(plan, this.entry.name, this.month) : '';
  });
  protected readonly thisAndFutureDescription = computed(() => {
    const plan = this.preview()?.thisAndFuture;
    return plan ? thisAndFutureText(plan, this.entry.name, this.month) : '';
  });

  private stale = false;

  ngOnInit(): void {
    this.api.getEntryDeletionPreview(this.entry.id).subscribe({
      next: (preview) => {
        this.preview.set(preview);
        this.loading.set(false);
        // De un Concepto, el foco va a la primera opción: es lo que hay que decidir.
        afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>('input[type="radio"]')?.focus(), {
          injector: this.injector,
        });
      },
      error: (e: unknown) => {
        this.loadError.set(messageFor(e));
        this.loading.set(false);
        this.markStaleIfNeeded(e);
      },
    });
  }

  protected submit(): void {
    const plan = this.plan();
    if (!this.canConfirm() || plan === null) {
      return;
    }
    const scope = this.recurring() ? this.selectedScope() : null;
    this.error.set(null);
    this.submitting.set(true);
    this.api.deleteEntry(this.entry.id, scope ?? undefined).subscribe({
      next: () =>
        this.ref.close({ deleted: true, message: successMessage(plan, scope, this.entry.name, this.month) }),
      error: (e: unknown) => this.fail(e),
    });
  }

  protected cancel(): void {
    this.ref.close(this.stale ? { stale: true } : undefined);
  }

  /** Con el período cerrado o la partida inexistente, la pantalla estaba desactualizada: se avisa y se recarga. */
  private markStaleIfNeeded(e: unknown): void {
    const code = e instanceof HttpErrorResponse && typeof e.error === 'object' ? (e.error?.code as string) : '';
    if (STALE_CODES.includes(code)) {
      this.stale = true;
      this.gone.set(true);
    }
  }

  private fail(e: unknown): void {
    this.submitting.set(false);
    const body = e instanceof HttpErrorResponse && typeof e.error === 'object' ? e.error : null;
    if (typeof body?.code === 'string' && BLOCKED_CODES.includes(body.code)) {
      // Lo que impide es otro dato que el que se vio: se vuelve a pedir y se listan, sin perder lo elegido.
      this.error.set(typeof body.detail === 'string' ? body.detail : messageFor(e));
      this.api.getEntryDeletionPreview(this.entry.id).subscribe({
        next: (preview) => this.preview.set(preview),
        error: () => undefined,
      });
      return;
    }
    this.markStaleIfNeeded(e);
    this.error.set(messageFor(e));
  }

  private readonly month = (period: string): string => this.periodPipe.transform(period);
}
