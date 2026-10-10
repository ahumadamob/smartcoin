import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { PeriodCurrencyTotals, PeriodEntry, PeriodSideTotals } from '../../api';
import { DATE_FORMAT } from '../../core/locale';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { canDeleteEntry, canEditAmount, canEditEntry, canRegisterMovement } from './entry-actions';
import { registerLabel } from './movement-text';

/** Texto en pantalla del estado de una partida (docs/glosario.md). */
export const ENTRY_STATUS_LABELS: Record<PeriodEntry.StatusEnum, string> = {
  ESTIMATED: 'Estimada',
  PARTIAL: 'Parcial',
  CONSOLIDATED: 'Consolidada',
};

interface FooterRow {
  currency: PeriodCurrencyTotals.CurrencyEnum;
  totals: PeriodSideTotals;
}

/**
 * Una sección de la vista del mes (HU-15): las partidas de ingreso o de gasto, en el orden que manda el backend, y
 * al pie un total por cada moneda que tiene partidas en la sección. No calcula ni ordena nada.
 *
 * Las acciones sobre una partida van en una última columna «Acciones», que se agrega solo si `readonly` es falso (un
 * período cerrado no tiene acciones) y alguna partida de la sección tiene una: no se dibuja una columna vacía. Hoy son
 * «Registrar cobro» o «Registrar pago» (HU-19), «Editar» (partidas sin Concepto, HU-16), «Editar monto» (recurrentes,
 * HU-17) y «Eliminar» (HU-18); consolidar llega con su historia. La tabla no cambia nada: emite el pedido y quien la
 * usa abre el diálogo.
 */
@Component({
  selector: 'app-entry-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MatButtonModule, MoneyPipe],
  templateUrl: './entry-table.html',
  styleUrl: './entry-table.scss',
})
export class EntryTable {
  /** «Ingresos» o «Gastos»: título de la sección y nombre accesible de la tabla. */
  readonly heading = input.required<string>();
  readonly entries = input.required<PeriodEntry[]>();
  /** Totales del período por moneda; la sección muestra el lado que indica `side`. */
  readonly totals = input.required<PeriodCurrencyTotals[]>();
  readonly side = input.required<'income' | 'expense'>();
  readonly emptyText = input.required<string>();
  /** Período cerrado: sin acciones. */
  readonly readonly = input(false);

  /** El usuario pidió registrar un cobro o pago de esta partida (HU-19). */
  readonly registerMovement = output<PeriodEntry>();
  /** El usuario pidió editar esta partida. */
  readonly edit = output<PeriodEntry>();
  /** El usuario pidió editar solo el monto de esta partida recurrente (HU-17). */
  readonly editAmount = output<PeriodEntry>();
  /** El usuario pidió eliminar esta partida (HU-18). */
  readonly remove = output<PeriodEntry>();

  protected readonly dateFormat = DATE_FORMAT;
  protected readonly statusLabels = ENTRY_STATUS_LABELS;
  protected readonly canRegister = canRegisterMovement;
  protected readonly registerLabel = registerLabel;
  protected readonly canEdit = canEditEntry;
  protected readonly canEditAmount = canEditAmount;
  protected readonly canDelete = canDeleteEntry;

  /** La columna «Acciones» solo existe si alguna partida de la sección tiene una acción (y el período no está cerrado). */
  protected readonly showActions = computed(
    () =>
      !this.readonly() &&
      this.entries().some(
        (entry) => canRegisterMovement(entry) || canEditEntry(entry) || canEditAmount(entry) || canDeleteEntry(entry),
      ),
  );

  /** Solo las monedas con partidas en esta sección (D-29). */
  protected readonly footer = computed<FooterRow[]>(() =>
    this.totals()
      .map((total) => ({ currency: total.currency, totals: total[this.side()] }))
      .filter((row) => row.totals.entryCount > 0),
  );
}
