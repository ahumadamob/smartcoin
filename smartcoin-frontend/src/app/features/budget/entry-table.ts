import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { PeriodCurrencyTotals, PeriodEntry, PeriodSideTotals } from '../../api';
import { DATE_FORMAT } from '../../core/locale';
import { MoneyPipe } from '../../shared/pipes/money.pipe';

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
 * Las acciones sobre una partida (editar, eliminar, registrar un movimiento, consolidar: HU-16 en adelante) van en
 * una última columna «Acciones», que se agrega solo si `readonly` es falso: un período cerrado no tiene acciones.
 */
@Component({
  selector: 'app-entry-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MoneyPipe],
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
  /** Período cerrado: sin acciones. Hoy ninguna partida tiene acciones; llegan desde HU-16. */
  readonly readonly = input(false);

  protected readonly dateFormat = DATE_FORMAT;
  protected readonly statusLabels = ENTRY_STATUS_LABELS;

  /** Solo las monedas con partidas en esta sección (D-29). */
  protected readonly footer = computed<FooterRow[]>(() =>
    this.totals()
      .map((total) => ({ currency: total.currency, totals: total[this.side()] }))
      .filter((row) => row.totals.entryCount > 0),
  );
}
