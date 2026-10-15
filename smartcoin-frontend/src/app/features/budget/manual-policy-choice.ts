import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';
import { MatRadioChange, MatRadioModule } from '@angular/material/radio';
import { ConsolidationTarget } from '../../api';
import { CurrencyCode, MoneyPipe } from '../../shared/pipes/money.pipe';
import { PeriodPipe } from '../../shared/pipes/period.pipe';
import { keepText, ManualPolicy, manualPolicyText, overwriteText } from './consolidation-text';

/**
 * Elección de qué hacer con las partidas editadas a mano de los meses siguientes al consolidar (HU-25, RN-26,
 * D-12): «Respetar» o «Pisar», una sola para todas y sin ninguna elegida de entrada. Lista las editadas con su mes,
 * su monto actual y el que tomarían si se pisan. No calcula montos: los recuentos salen de los indicadores `manual`
 * y `changes` que informa la vista previa.
 *
 * Es un componente de presentación para que lo reutilicen el diálogo de consolidación y, con HU-22, el del pago
 * rápido: quien lo usa le pasa los destinos de su vista previa y guarda la elección en `policy`.
 */
@Component({
  selector: 'app-manual-policy-choice',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatRadioModule, MoneyPipe, PeriodPipe],
  templateUrl: './manual-policy-choice.html',
  styleUrl: './manual-policy-choice.scss',
})
export class ManualPolicyChoice {
  /** Todas las partidas de destino de la vista previa; el componente toma las editadas. */
  readonly targets = input.required<ConsolidationTarget[]>();
  readonly currency = input.required<CurrencyCode>();
  /** La elección: `null` mientras el usuario no eligió. */
  readonly policy = model<ManualPolicy | null>(null);

  protected readonly edited = computed(() => this.targets().filter((target) => target.manual));
  private readonly othersChanging = computed(
    () => this.targets().filter((target) => !target.manual && target.changes).length,
  );

  protected readonly intro = computed(() => manualPolicyText(this.edited().length));
  protected readonly keepDescription = computed(() => keepText(this.edited().length, this.othersChanging()));
  protected readonly overwriteDescription = computed(() =>
    overwriteText(this.edited().length, this.edited().filter((target) => target.changes).length, this.othersChanging()),
  );

  protected choose(event: MatRadioChange): void {
    this.policy.set(event.value as ManualPolicy);
  }
}
