import { ChangeDetectionStrategy, Component, computed, ElementRef, inject, input, output, signal } from '@angular/core';
import { BudgetGrid, GridCell, GridCurrencyTotals, GridRow } from '../../api';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { PeriodPipe } from '../../shared/pipes/period.pipe';
import { CellPosition, nextCell } from './grid-nav';

type Currency = GridCurrencyTotals.CurrencyEnum;

/** Una fila con el lugar que ocupa entre todas las de la grilla, para recorrerla con el teclado. */
export interface IndexedRow {
  index: number;
  row: GridRow;
}

/** Las filas de ingresos o de gastos. */
export interface Section {
  kind: GridRow.KindEnum;
  title: string;
  rows: IndexedRow[];
}

const ORIGIN_LABELS: Record<string, string> = {
  ONE_OFF: 'Puntuales',
  CARRIED_OVER: 'Saldos postergados',
  CLOSING_DIFFERENCE: 'Diferencias de cierre',
};

/**
 * La grilla de varios meses (HU-36, RN-44): Conceptos en filas y meses en columnas, con el estimado de cada partida y
 * las marcas «Editada», «Consolidada» y «Parcial» como texto. No suma ni calcula nada: estimados y totales vienen del
 * backend. Cada celda es enfocable (un solo punto de tabulación; flechas, Inicio y Fin para recorrerla) y, con clic,
 * Enter o Espacio, pide abrir el mes de su columna. El encabezado de meses queda fijo arriba, los nombres a la izquierda
 * y los totales cierran la tabla.
 */
@Component({
  selector: 'app-budget-grid-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MoneyPipe, PeriodPipe],
  templateUrl: './budget-grid-table.html',
  styleUrl: './budget-grid-table.scss',
})
export class BudgetGridTable {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly grid = input.required<BudgetGrid>();
  /** Se pide abrir el presupuesto de este mes (`YYYY-MM`). */
  readonly openMonth = output<string>();

  /** La celda que se alcanza con Tab; las demás se recorren con flechas. */
  protected readonly active = signal<CellPosition>({ row: 0, column: 0 });

  protected readonly sections = computed<Section[]>(() => {
    const rows = this.grid().rows;
    const sections: Section[] = [];
    rows.forEach((row, index) => {
      let section = sections.find((s) => s.kind === row.kind);
      if (!section) {
        section = { kind: row.kind, title: row.kind === 'INCOME' ? 'Ingresos' : 'Gastos', rows: [] };
        sections.push(section);
      }
      section.rows.push({ index, row });
    });
    return sections;
  });

  /** Las monedas que tienen totales en algún mes, en el orden en que las informa el backend (ARS, USD). */
  protected readonly currencies = computed<Currency[]>(() =>
    (['ARS', 'USD'] as const).filter((currency) =>
      this.grid().totals.some((month) => month.currencies.some((c) => c.currency === currency)),
    ),
  );

  protected label(row: GridRow): string {
    return row.name ?? ORIGIN_LABELS[row.origin ?? ''] ?? '';
  }

  protected total(index: number, currency: Currency): GridCurrencyTotals | undefined {
    return this.grid().totals[index]?.currencies.find((c) => c.currency === currency);
  }

  protected isClosed(index: number): boolean {
    return this.grid().months[index]?.status === 'CLOSED';
  }

  protected isCurrent(index: number): boolean {
    return this.grid().months[index]?.period === this.grid().currentPeriod;
  }

  protected showPartial(cell: GridCell): boolean {
    return cell.status === 'PARTIAL';
  }

  protected isActive(row: number, column: number): boolean {
    const active = this.active();
    return active.row === row && active.column === column;
  }

  protected open(row: number, column: number): void {
    this.active.set({ row, column });
    this.openMonth.emit(this.grid().months[column].period);
  }

  protected onKeydown(event: KeyboardEvent, row: number, column: number): void {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      this.open(row, column);
      return;
    }
    const target = nextCell({ row, column }, event.key, event.ctrlKey, this.grid().rows.length, this.grid().months.length);
    if (!target) {
      return;
    }
    event.preventDefault();
    this.active.set(target);
    this.host.nativeElement
      .querySelector<HTMLElement>(`[data-row="${target.row}"][data-column="${target.column}"]`)
      ?.focus();
  }
}
