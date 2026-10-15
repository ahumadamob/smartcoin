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
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ConsolidacinService, ConsolidationPreview, ConsolidationTarget, PeriodEntry } from '../../api';
import { detailFor, messageFor } from '../../core/error-messages';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { PeriodPipe } from '../../shared/pipes/period.pipe';
import { averageBasisText, consolidatedMessage, ManualPolicy, noChangesText, RULE_LABELS } from './consolidation-text';
import { ManualPolicyChoice } from './manual-policy-choice';

/** Qué se consolida: la partida (la fila de la vista del mes) y el mes que se está viendo. */
export interface ConsolidationDialogData {
  entry: PeriodEntry;
  /** `YYYY-MM` del mes que se está viendo. */
  period: string;
}

/**
 * Cómo se cierra: `consolidated` con el aviso para el usuario y la partida ya consolidada, `stale` si el backend dijo
 * que lo que se ve ya no es lo que hay (período cerrado, partida inexistente o ya no pendiente), o sin valor si el
 * usuario canceló.
 */
export type ConsolidationDialogResult = { consolidated: true; message: string; entry: PeriodEntry } | { stale: true };

const STALE_CODES = ['PERIOD_CLOSED', 'ENTRY_NOT_PENDING', 'CONSOLIDATION_REQUIRES_MOVEMENT', 'NOT_FOUND'];

/**
 * Diálogo para consolidar una partida (HU-23, RN-25 a RN-28). Al abrirse pide al backend la vista previa y muestra
 * exactamente qué va a pasar: el monto real, el monto vigente actual y la nueva estimación, y las partidas de los
 * meses siguientes que cambian. El frontend no calcula nada.
 *
 * Si hay partidas editadas en los meses siguientes, la vista previa lo indica y el diálogo pide elegir «Respetar» o
 * «Pisar» (HU-25, `ManualPolicyChoice`): sin elección no se confirma, y la elegida se envía como
 * `manualEntriesPolicy`. Consolidar no se puede deshacer hasta HU-26, y el diálogo lo avisa.
 */
@Component({
  selector: 'app-consolidation-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule, ManualPolicyChoice, MoneyPipe, PeriodPipe],
  templateUrl: './consolidation-dialog.html',
  styleUrl: './consolidation-dialog.scss',
})
export class ConsolidationDialog implements OnInit {
  private readonly api = inject(ConsolidacinService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly periodPipe = new PeriodPipe();
  private readonly ref = inject<MatDialogRef<ConsolidationDialog, ConsolidationDialogResult>>(MatDialogRef);
  private readonly money = new MoneyPipe();
  protected readonly data = inject<ConsolidationDialogData>(MAT_DIALOG_DATA);

  protected readonly entry = this.data.entry;
  protected readonly preview = signal<ConsolidationPreview | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  /** Error del backend al confirmar, o `null`. */
  protected readonly error = signal<string | null>(null);
  protected readonly submitting = signal(false);
  /** La pantalla estaba desactualizada: ya no se puede consolidar desde acá. */
  protected readonly gone = signal(false);
  private stale = false;

  /** Las partidas de los meses siguientes cuyo presupuestado cambia. */
  protected readonly changing = computed<ConsolidationTarget[]>(() =>
    (this.preview()?.targets ?? []).filter((target) => target.changes && !target.manual),
  );
  /** Qué hacer con las editadas: sin elegir mientras el usuario no decida (HU-25). */
  protected readonly policy = signal<ManualPolicy | null>(null);
  protected readonly ruleLabel = computed(() => {
    const rule = this.preview()?.estimationRule;
    return rule ? RULE_LABELS[rule] : null;
  });
  /** Cómo se obtuvo el promedio, con las consolidaciones que informa el backend; `null` con otra regla. */
  protected readonly averageText = computed(() => {
    const preview = this.preview();
    if (preview?.estimationRule !== 'AVERAGE_LAST_3') {
      return null;
    }
    return averageBasisText(preview.estimateBasis, (period) => this.periodPipe.transform(period)) || null;
  });
  protected readonly blockedByPolicy = computed(() => this.preview()?.manualPolicyRequired === true);
  protected readonly noChanges = computed(() => noChangesText(this.preview()?.targets.length ?? 0));
  protected readonly canConfirm = computed(() => {
    const preview = this.preview();
    return (
      preview !== null &&
      preview.allowed &&
      (!preview.manualPolicyRequired || this.policy() !== null) &&
      !this.submitting() &&
      !this.gone()
    );
  });

  ngOnInit(): void {
    this.api.getConsolidationPreview(this.entry.id).subscribe({
      next: (preview) => {
        this.preview.set(preview);
        this.loading.set(false);
        afterNextRender(
          () =>
            this.host.nativeElement
              .querySelector<HTMLElement>('[data-testid="confirm-consolidate"], [data-testid="cancel-consolidate"]')
              ?.focus(),
          { injector: this.injector },
        );
      },
      error: (e: unknown) => {
        this.loadError.set(messageFor(e));
        this.loading.set(false);
        this.markStaleIfNeeded(e);
      },
    });
  }

  protected submit(): void {
    if (!this.canConfirm()) {
      return;
    }
    this.error.set(null);
    this.submitting.set(true);
    // La política solo viaja si hace falta: sin editadas en el destino no se pregunta ni se envía nada.
    const policy = this.preview()?.manualPolicyRequired ? this.policy() : null;
    const request = policy === null ? undefined : { manualEntriesPolicy: policy };
    this.api.consolidateEntry(this.entry.id, request).subscribe({
      next: (result) =>
        this.ref.close({
          consolidated: true,
          entry: result.entry,
          message: consolidatedMessage(result, (amount, currency) => this.money.transform(amount, currency)),
        }),
      error: (e: unknown) => this.fail(e),
    });
  }

  protected cancel(): void {
    this.ref.close(this.stale ? { stale: true } : undefined);
  }

  private fail(e: unknown): void {
    this.submitting.set(false);
    const body = e instanceof HttpErrorResponse && typeof e.error === 'object' ? e.error : null;
    this.markStaleIfNeeded(e);
    if (body?.code === 'MANUAL_POLICY_REQUIRED') {
      // Cambió algo desde la vista previa: se vuelve a pedir y el diálogo muestra la elección. Lo elegido se conserva.
      this.api.getConsolidationPreview(this.entry.id).subscribe({
        next: (preview) => this.preview.set(preview),
        error: () => undefined,
      });
    }
    this.error.set(detailFor(e));
  }

  private markStaleIfNeeded(e: unknown): void {
    const code = e instanceof HttpErrorResponse && typeof e.error === 'object' ? (e.error?.code as string) : '';
    if (STALE_CODES.includes(code)) {
      this.stale = true;
      this.gone.set(true);
    }
  }
}
