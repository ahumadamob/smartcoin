import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { DeletionBlocker } from '../../api';
import { PeriodPipe } from '../../shared/pipes/period.pipe';

/** Por qué una partida impide eliminar (RN-32), en palabras. */
export const BLOCK_REASON_LABELS: Record<DeletionBlocker.ReasonEnum, string> = {
  CONSOLIDATED: 'está consolidada',
  HAS_MOVEMENTS: 'tiene movimientos',
};

/**
 * Las partidas que impiden una eliminación (HU-18), una por línea con su mes y su motivo. Es texto, no solo color:
 * se anuncia con el resto del diálogo. No decide nada: lista lo que informa el backend, en su orden.
 */
@Component({
  selector: 'app-entry-deletion-blockers',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PeriodPipe],
  template: `
    <div class="blockers" data-testid="blockers">
      <p class="intro">{{ intro() }}</p>
      <ul>
        @for (blocker of blockers(); track blocker.entryId) {
          <li data-testid="blocker">
            <strong>{{ blocker.period | period }}</strong
            >: {{ reasons[blocker.reason] }}
          </li>
        }
      </ul>
    </div>
  `,
  styles: `
    .blockers {
      margin: 8px 0 0;
      color: var(--mat-sys-error);
    }
    .intro {
      margin: 0 0 4px;
    }
    ul {
      margin: 0;
      padding-left: 20px;
    }
  `,
})
export class EntryDeletionBlockers {
  readonly blockers = input.required<DeletionBlocker[]>();

  protected readonly reasons = BLOCK_REASON_LABELS;
  protected readonly intro = computed(() =>
    this.blockers().length === 1
      ? 'No se puede eliminar porque esta partida lo impide:'
      : 'No se puede eliminar porque estas partidas lo impiden:',
  );
}
