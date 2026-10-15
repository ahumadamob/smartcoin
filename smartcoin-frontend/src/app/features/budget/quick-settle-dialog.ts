import { formatDate } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  Injector,
  LOCALE_ID,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import { catchError, EMPTY, forkJoin, switchMap } from 'rxjs';
import {
  AccountResponse,
  CuentasService,
  MovementDates,
  MovimientosService,
  PeriodEntry,
  QuickSettlePreview,
} from '../../api';
import { detailFor, messageFor } from '../../core/error-messages';
import { DATE_FORMAT } from '../../core/locale';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { PeriodPipe } from '../../shared/pipes/period.pipe';
import { ConsolidationOutcome } from './consolidation-outcome';
import { ManualPolicy, quickSettledMessage } from './consolidation-text';
import { isoDate } from './iso-date';
import {
  movementWord,
  notOpenYetNotice,
  quickSettleLabel,
  suggestedDate,
  windowNotice,
  windowNotOpenYet,
} from './movement-text';

/** Qué se paga o cobra: la partida (la fila de la vista del mes) y el mes que se está viendo. */
export interface QuickSettleDialogData {
  entry: PeriodEntry;
  /** `YYYY-MM` del mes que se está viendo. */
  period: string;
}

/**
 * Cómo se cierra: `settled` con el aviso para el usuario y la partida ya consolidada, `stale` si el backend dijo que
 * lo que se ve ya no es lo que hay (período cerrado, partida inexistente, consolidada o sin pendiente), o sin valor
 * si el usuario canceló.
 */
export type QuickSettleDialogResult = { settled: true; message: string; entry: PeriodEntry } | { stale: true };

/** Estos códigos significan que lo que muestra la pantalla ya no es lo que hay en el backend. */
const STALE_CODES = ['PERIOD_CLOSED', 'ENTRY_NOT_PENDING', 'NOTHING_PENDING', 'NOT_FOUND'];

type FieldName = 'date' | 'accountId';

/**
 * Diálogo de «Pagar todo» y «Cobrar todo» (HU-22, RN-24). Muestra el movimiento que se va a registrar, por el
 * pendiente que informa el backend; la fecha (la que sugiere `GET /api/entries/{id}/movement-dates`, editable, con la
 * misma explicación de la ventana que `MovementDialog`); la cuenta (la de la partida, editable entre las de su
 * moneda); y a continuación la vista previa de la consolidación que seguiría, que sale del mismo plan que el pago
 * rápido (`GET /api/entries/{id}/quick-settle-preview`). El frontend no calcula nada.
 *
 * Con partidas editadas en el destino pide la elección «Respetar» o «Pisar» (`ManualPolicyChoice` dentro de
 * `ConsolidationOutcome`) y no deja confirmar sin elegir. Ante `MANUAL_POLICY_REQUIRED` porque algo cambió desde la
 * vista previa, la vuelve a pedir y conserva lo elegido, como `ConsolidationDialog`. Si el pedido falla, no queda
 * nada registrado: el backend lo hace todo o nada.
 */
@Component({
  selector: 'app-quick-settle-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    RouterLink,
    ConsolidationOutcome,
    MoneyPipe,
    PeriodPipe,
  ],
  templateUrl: './quick-settle-dialog.html',
  styleUrl: './quick-settle-dialog.scss',
})
export class QuickSettleDialog implements OnInit {
  private readonly api = inject(MovimientosService);
  private readonly accountsApi = inject(CuentasService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);
  private readonly locale = inject(LOCALE_ID);
  private readonly ref = inject<MatDialogRef<QuickSettleDialog, QuickSettleDialogResult>>(MatDialogRef);
  private readonly money = new MoneyPipe();
  private readonly periodPipe = new PeriodPipe();
  protected readonly data = inject<QuickSettleDialogData>(MAT_DIALOG_DATA);

  protected readonly entry = this.data.entry;
  protected readonly word = movementWord(this.data.entry.kind);
  protected readonly label = quickSettleLabel(this.data.entry.kind);
  protected readonly preview = signal<QuickSettlePreview | null>(null);
  /** Solo las cuentas de la moneda de la partida (S-02). */
  protected readonly accounts = signal<AccountResponse[]>([]);
  /** Rango de fechas que admite la partida con la cuenta elegida, según el backend. */
  protected readonly dates = signal<MovementDates | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  /** Error del backend que no es de un campo, o `null`. Los de un campo salen debajo de él. */
  protected readonly error = signal<string | null>(null);
  protected readonly submitting = signal(false);
  /** La pantalla estaba desactualizada: ya no se puede pagar o cobrar desde acá. */
  protected readonly gone = signal(false);
  /** Qué hacer con las editadas: sin elegir mientras el usuario no decida (HU-25). */
  protected readonly policy = signal<ManualPolicy | null>(null);
  private stale = false;

  protected readonly form = new FormGroup({
    date: new FormControl('', { nonNullable: true, validators: [Validators.required, isoDate] }),
    accountId: new FormControl<number | null>(this.data.entry.accountId, Validators.required),
  });

  /** Una línea con la ventana de anticipación y la fecha más temprana, o `null` si no hay que mostrarla. */
  protected readonly windowText = computed(() => {
    const dates = this.dates();
    if (dates === null) {
      return null;
    }
    const earliest = formatDate(dates.earliestDate, DATE_FORMAT, this.locale);
    return windowNotOpenYet(dates)
      ? notOpenYetNotice(earliest)
      : windowNotice(dates.earlyDays, this.periodPipe.transform(this.data.period), earliest);
  });

  /** `false` cuando la ventana de la partida todavía no abrió: ninguna fecha sirve y no se deja confirmar. */
  protected readonly windowOpen = computed(() => {
    const dates = this.dates();
    return dates === null || !windowNotOpenYet(dates);
  });

  protected readonly canConfirm = computed(() => {
    const preview = this.preview();
    return (
      preview !== null &&
      preview.allowed &&
      this.accounts().length > 0 &&
      this.windowOpen() &&
      (!preview.consolidation?.manualPolicyRequired || this.policy() !== null) &&
      !this.submitting() &&
      !this.gone()
    );
  });

  ngOnInit(): void {
    forkJoin({
      preview: this.api.getQuickSettlePreview(this.entry.id),
      accounts: this.accountsApi.listAccounts(),
      dates: this.api.getMovementDates(this.entry.id, this.entry.accountId),
    }).subscribe({
      next: ({ preview, accounts, dates }) => {
        this.preview.set(preview);
        this.accounts.set(accounts.accounts.filter((account) => account.currency === this.entry.currency));
        this.dates.set(dates);
        this.form.controls.date.setValue(suggestedDate(dates));
        this.loading.set(false);
        if (!preview.allowed) {
          this.markStaleIfNeeded(preview.blockedBy ?? '');
        }
        afterNextRender(
          () =>
            this.host.nativeElement
              .querySelector<HTMLElement>('[data-testid="confirm-quick-settle"], [data-testid="cancel-quick-settle"]')
              ?.focus(),
          { injector: this.injector },
        );
      },
      error: (e: unknown) => {
        this.loadError.set(messageFor(e));
        this.loading.set(false);
        this.markStaleIfNeeded(this.codeOf(e));
      },
    });
    // La fecha más temprana depende de la cuenta (su apertura): al cambiarla, se vuelve a pedir al backend. La fecha
    // que ya escribió el usuario no se toca; si falla el pedido, queda el rango anterior y el backend decide al enviar.
    this.form.controls.accountId.valueChanges
      .pipe(
        switchMap((accountId) =>
          accountId === null
            ? EMPTY
            : this.api.getMovementDates(this.entry.id, accountId).pipe(catchError(() => EMPTY)),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((dates) => this.dates.set(dates));
  }

  protected submit(): void {
    if (!this.canConfirm()) {
      return;
    }
    this.error.set(null);
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      return;
    }
    const value = this.form.getRawValue();
    this.submitting.set(true);
    // La política solo viaja si hace falta: sin editadas en el destino no se pregunta ni se envía nada.
    const policy = this.preview()?.consolidation?.manualPolicyRequired ? this.policy() : null;
    const request = {
      date: value.date,
      accountId: value.accountId ?? undefined,
      ...(policy === null ? {} : { manualEntriesPolicy: policy }),
    };
    this.api.quickSettleEntry(this.entry.id, request).subscribe({
      next: (result) =>
        this.ref.close({
          settled: true,
          entry: result.entry,
          message: quickSettledMessage(result, (amount, currency) => this.money.transform(amount, currency)),
        }),
      error: (e: unknown) => this.fail(e),
    });
  }

  protected cancel(): void {
    this.ref.close(this.stale ? { stale: true } : undefined);
  }

  /**
   * El error de un campo sale debajo de él; cualquier otro, arriba del formulario. Lo cargado no se toca. Ante
   * `MANUAL_POLICY_REQUIRED` se vuelve a pedir la vista previa para que el diálogo muestre la elección.
   */
  private fail(e: unknown): void {
    this.submitting.set(false);
    const body = e instanceof HttpErrorResponse && typeof e.error === 'object' ? e.error : null;
    const code = this.codeOf(e);
    this.markStaleIfNeeded(code);
    if (code === 'MANUAL_POLICY_REQUIRED') {
      this.api.getQuickSettlePreview(this.entry.id).subscribe({
        next: (preview) => this.preview.set(preview),
        error: () => undefined,
      });
    }
    let field: FieldName | undefined;
    if (code === 'VALIDATION_ERROR') {
      const name = body?.errors?.[0]?.field as string | undefined;
      field = name === 'date' || name === 'accountId' ? name : undefined;
    } else if (code === 'DATE_OUT_OF_RANGE') {
      field = 'date';
    } else if (code === 'CURRENCY_MISMATCH') {
      field = 'accountId';
    }
    // El `detail` nombra la fecha límite o la cuenta: explica más que el texto fijo.
    const message =
      code === 'DATE_OUT_OF_RANGE' || code === 'PERIOD_CLOSED' || code === 'ENTRY_NOT_PENDING' || code === 'MANUAL_POLICY_REQUIRED'
        ? detailFor(e)
        : messageFor(e);
    const control = field === undefined ? undefined : this.form.controls[field];
    if (control) {
      control.setErrors({ server: message });
      control.markAsTouched();
      this.host.nativeElement.querySelector<HTMLElement>(`[formcontrolname="${field}"]`)?.focus();
    } else {
      this.error.set(message);
    }
  }

  private codeOf(e: unknown): string {
    return e instanceof HttpErrorResponse && typeof e.error === 'object' && typeof e.error?.code === 'string'
      ? (e.error.code as string)
      : '';
  }

  private markStaleIfNeeded(code: string): void {
    if (STALE_CODES.includes(code)) {
      this.stale = true;
      this.gone.set(true);
    }
  }
}
