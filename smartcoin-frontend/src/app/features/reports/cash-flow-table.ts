import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { CashFlow, CashFlowRow } from '../../api';
import { DATE_FORMAT } from '../../core/locale';
import { MoneyPipe } from '../../shared/pipes/money.pipe';

/** Texto en pantalla de si una fila ya ocurrió o está prevista (HU-34). */
export const CASH_FLOW_STATUS_LABELS: Record<CashFlowRow.StatusEnum, string> = {
  REAL: 'Real',
  PLANNED: 'Prevista',
};

/**
 * La tabla del flujo de caja (HU-34): el saldo de arranque arriba, una fila por cosa con su saldo acumulado y el saldo
 * final al pie. No suma ni ordena nada: todo viene calculado del backend. Lo real y lo previsto se distinguen por el
 * texto de la columna «Estado», por el borde de su marca (continuo o punteado) y por el estilo de la fila, no solo por
 * el color; un saldo negativo lleva el signo menos y la marca «Faltante».
 */
@Component({
  selector: 'app-cash-flow-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MoneyPipe],
  templateUrl: './cash-flow-table.html',
  styleUrl: './cash-flow-table.scss',
})
export class CashFlowTable {
  readonly flow = input.required<CashFlow>();

  protected readonly dateFormat = DATE_FORMAT;
  protected readonly statusLabels = CASH_FLOW_STATUS_LABELS;

  /** Un saldo por debajo de 0 es el faltante que la pantalla quiere anticipar. */
  protected isShortfall(balance: number): boolean {
    return balance < 0;
  }
}
