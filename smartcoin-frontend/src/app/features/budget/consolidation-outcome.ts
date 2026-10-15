import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';
import { ConsolidationPreview, ConsolidationTarget } from '../../api';
import { CurrencyCode, MoneyPipe } from '../../shared/pipes/money.pipe';
import { PeriodPipe } from '../../shared/pipes/period.pipe';
import { averageBasisText, ManualPolicy, noChangesText, RULE_LABELS } from './consolidation-text';
import { ManualPolicyChoice } from './manual-policy-choice';

/**
 * Lo que muestra la vista previa de una consolidación que se puede hacer (HU-23 a HU-25, RN-26, RN-27): el monto
 * real, el monto vigente actual y la nueva estimación, cómo salió el promedio, la elección «Respetar» o «Pisar» si
 * hay partidas editadas y las partidas de los meses siguientes que cambian. Es lo que comparten el diálogo de
 * consolidar y el del pago rápido (HU-22): los dos muestran el mismo plan del backend. No calcula nada.
 *
 * La elección se guarda en `policy`; sin elegir (`null`) quien lo usa no deja confirmar.
 */
@Component({
  selector: 'app-consolidation-outcome',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ManualPolicyChoice, MoneyPipe, PeriodPipe],
  templateUrl: './consolidation-outcome.html',
  styleUrl: './consolidation-outcome.scss',
})
export class ConsolidationOutcome {
  /** La vista previa del backend, ya permitida (`allowed`). */
  readonly preview = input.required<ConsolidationPreview>();
  readonly currency = input.required<CurrencyCode>();
  /** Cómo se llama el monto que queda consolidado: «Monto real» al consolidar, otro en el pago rápido. */
  readonly actualLabel = input('Monto real');
  /** Qué hacer con las editadas: `null` mientras el usuario no decida (HU-25). */
  readonly policy = model<ManualPolicy | null>(null);

  private readonly periodPipe = new PeriodPipe();

  /** Las partidas de los meses siguientes cuyo presupuestado cambia con cualquiera de las dos opciones. */
  protected readonly changing = computed<ConsolidationTarget[]>(() =>
    this.preview().targets.filter((target) => target.changes && !target.manual),
  );
  protected readonly ruleLabel = computed(() => {
    const rule = this.preview().estimationRule;
    return rule ? RULE_LABELS[rule] : null;
  });
  /** Cómo se obtuvo el promedio, con las consolidaciones que informa el backend; `null` con otra regla. */
  protected readonly averageText = computed(() => {
    const preview = this.preview();
    if (preview.estimationRule !== 'AVERAGE_LAST_3') {
      return null;
    }
    return averageBasisText(preview.estimateBasis, (period) => this.periodPipe.transform(period)) || null;
  });
  protected readonly blockedByPolicy = computed(() => this.preview().manualPolicyRequired);
  protected readonly noChanges = computed(() => noChangesText(this.preview().targets.length));
}
