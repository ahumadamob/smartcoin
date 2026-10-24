import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
  OutputEmitterRef,
} from '@angular/core';
import { ButtonDirective } from 'primeng/button';
import { TableModule } from 'primeng/table';
import { Tag } from 'primeng/tag';
import { Tooltip } from 'primeng/tooltip';
import { PeriodCurrencyTotals, PeriodEntry, PeriodSideTotals } from '../../api';
import { DATE_FORMAT } from '../../core/locale';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import {
  canConsolidate,
  canDeleteEntry,
  canEditAmount,
  canEditEntry,
  canQuickSettle,
  canUnconsolidate,
  canRegisterMovement,
} from './entry-actions';
import { quickSettleLabel, registerLabel } from './movement-text';

/** Texto en pantalla del estado de una partida (docs/glosario.md). */
export const ENTRY_STATUS_LABELS: Record<PeriodEntry.StatusEnum, string> = {
  ESTIMATED: 'Estimada',
  PARTIAL: 'Parcial',
  CONSOLIDATED: 'Consolidada',
};

/** Color de la etiqueta de cada estado: Estimada celeste, Parcial verde y Consolidada el primario (sin severidad). */
export const ENTRY_STATUS_SEVERITIES: Record<PeriodEntry.StatusEnum, 'info' | 'success' | undefined> = {
  ESTIMATED: 'info',
  PARTIAL: 'success',
  CONSOLIDATED: undefined,
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
 * HU-17), «Consolidar» (HU-23) y «Eliminar» (HU-18). La tabla no cambia nada: emite el pedido y quien la
 * usa abre el diálogo. Son botones redondos con ícono y borde, cada uno en su propio lugar de la fila, con la etiqueta como
 * `aria-label` y como ayuda al pasar el mouse.
 */
@Component({
  selector: 'app-entry-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ButtonDirective, DatePipe, MoneyPipe, TableModule, Tag, Tooltip],
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
  /** El usuario pidió consolidar esta partida (HU-23). */
  readonly consolidate = output<PeriodEntry>();
  /** El usuario pidió desconsolidar esta partida (HU-26). */
  readonly unconsolidate = output<PeriodEntry>();
  /** El usuario pidió pagar o cobrar todo lo pendiente de esta partida (HU-22). */
  readonly quickSettle = output<PeriodEntry>();
  /** El usuario pidió editar esta partida. */
  readonly edit = output<PeriodEntry>();
  /** El usuario pidió editar solo el monto de esta partida recurrente (HU-17). */
  readonly editAmount = output<PeriodEntry>();
  /** El usuario pidió eliminar esta partida (HU-18). */
  readonly remove = output<PeriodEntry>();

  protected readonly dateFormat = DATE_FORMAT;
  protected readonly statusLabels = ENTRY_STATUS_LABELS;
  protected readonly statusSeverity = ENTRY_STATUS_SEVERITIES;
  protected readonly canRegister = canRegisterMovement;
  protected readonly registerLabel = registerLabel;
  protected readonly canEdit = canEditEntry;
  protected readonly canEditAmount = canEditAmount;
  protected readonly canDelete = canDeleteEntry;
  protected readonly canConsolidate = canConsolidate;
  protected readonly quickSettleLabel = quickSettleLabel;
  /**
   * Las filas se identifican por la partida: al volver a pedir el mes, la tabla reutiliza las que siguen y no pierde
   * el foco (`p-table` compara por objeto si no se le dice, y cada respuesta trae objetos nuevos).
   */
  protected readonly trackById = (_index: number, entry: PeriodEntry): number => entry.id;

  /** La fila que entrega `p-table` viene sin tipo: son las partidas que recibió en `entries`. */
  protected asEntry(row: unknown): PeriodEntry {
    return row as PeriodEntry;
  }

  /** «Desconsolidar»: las consolidadas de un período abierto (HU-26). */
  protected canUndo(entry: PeriodEntry): boolean {
    return canUnconsolidate(entry, this.readonly());
  }

  /** «Pagar todo» o «Cobrar todo»: partidas con pendiente en un período abierto (HU-22). */
  protected canSettle(entry: PeriodEntry): boolean {
    return canQuickSettle(entry, this.readonly());
  }

  /** La columna «Acciones» solo existe si alguna partida de la sección tiene una acción (y el período no está cerrado). */
  protected readonly showActions = computed(
    () =>
      !this.readonly() &&
      this.entries().some(
        (entry) =>
          canRegisterMovement(entry) ||
          canConsolidate(entry) ||
          canUnconsolidate(entry, false) ||
          canEditEntry(entry) ||
          canEditAmount(entry) ||
          canDeleteEntry(entry),
      ),
  );

  /** Solo las monedas con partidas en esta sección (D-29). */
  protected readonly footerRows = computed<FooterRow[]>(() =>
    this.totals()
      .map((total) => ({ currency: total.currency, totals: total[this.side()] }))
      .filter((row) => row.totals.entryCount > 0),
  );
}
